package com.sparktube.app.ui.shorts

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityShortsBinding
import com.sparktube.app.data.YtRepository
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shorts / Reels player (v1.7.2): a REAL fullscreen portrait feed —
 *  - every page is a full-screen video that plays inline (auto-plays when
 *    it snaps into place, powered by the shared PlaybackCenter engine);
 *  - tap the middle of the video to play/pause;
 *  - swipe up / down to move to the next / previous short;
 *  - top-right button toggles Fit (whole video) / Fill (crop) scale mode;
 *  - loading state on every page while the stream resolves.
 *
 * Feed sources are unchanged from v1.7.1: the InnerTube shorts feed with the
 * country preference and, when Google-signed-in, account personalization.
 */
class ShortsActivity : AppCompatActivity(), PlaybackCenter.Listener {

    private lateinit var binding: ActivityShortsBinding
    private lateinit var adapter: ShortsPagerAdapter
    private val shorts = mutableListOf<com.sparktube.app.ui.common.VideoUiModel>()

    /** Page whose PlayerView currently owns the shared player surface. */
    private var attachedPage: ShortsPageView? = null

    /** Position the snap helper last settled on. */
    private var settledPosition = -1
    private var loadingMore = false
    private var morePage = 0

    /**
     * v1.7.4: shorts already watched in THIS app session. Every open of the
     * shorts screen now serves fresh items instead of the same feed, and the
     * list recycles only when nothing unseen is left. It lives in memory, so
     * it is gone when the app process exits (same lifecycle as the prefetch
     * cache).
     */
    private object ShortsSeenStore {
        val seen = LinkedHashSet<String>()

        /** True when [url] was watched and there are still unseen candidates. */
        fun shouldSkip(url: String, candidatesLeft: Int): Boolean =
            url in seen && candidatesLeft > UNSEEN_FLOOR

        /** Filter [items] down to unseen ones, recycling when exhausted. */
        fun <T> fresh(items: List<T>, urlOf: (T) -> String): List<T> {
            if (seen.isEmpty()) return items
            val unseen = items.filter { urlOf(it) !in seen }
            // If almost nothing unseen remains, start the cycle over instead
            // of showing an empty / tiny feed.
            return if (unseen.size >= UNSEEN_FLOOR || unseen.size == items.size) {
                unseen
            } else {
                seen.clear()
                items
            }
        }

        private const val UNSEEN_FLOOR = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityShortsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enterImmersiveMode()

        binding.backButton.setOnClickListener {
            performHaptic()
            finish()
        }
        binding.fitButton.setOnClickListener {
            performHaptic()
            AppPrefs.shortsFillMode = !AppPrefs.shortsFillMode
            applyFillModeToAttachedPage()
            updateFitButtonIcon()
        }
        updateFitButtonIcon()

        adapter = ShortsPagerAdapter(shorts, onVideoTap = {
            performHaptic()
            togglePlayPause()
        })
        binding.recycler.layoutManager =
            LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.recycler.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(binding.recycler)
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
                ) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val position = lm.findFirstCompletelyVisibleItemPosition()
                    if (position >= 0 && position != settledPosition) {
                        onPageSettled(position)
                    }
                }
            }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
        PlaybackCenter.addListener(this)
        enterImmersiveMode()
        // Re-attach the surface and resume the current page.
        if (settledPosition in shorts.indices) {
            onPageSettled(settledPosition, resume = true)
        }
    }

    override fun onPause() {
        super.onPause()
        PlaybackCenter.removeListener(this)
        // Leaving the shorts screen pauses playback (same as the YouTube app).
        if (PlaybackCenter.isPlaying) {
            runCatching { PlaybackCenter.togglePlayPause() }
        }
    }

    private fun performHaptic() {
        binding.recycler.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun enterImmersiveMode() {
        runCatching {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, binding.root)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateFitButtonIcon() {
        // ic_fullscreen shown in FIT mode (tap = fill/crop); exit icon in FILL mode.
        binding.fitButton.setImageResource(
            if (AppPrefs.shortsFillMode) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
    }

    private fun applyFillModeToAttachedPage() {
        attachedPage?.videoSlot?.resizeMode =
            if (AppPrefs.shortsFillMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
    }

    /** A new short snapped into view: make it the current playback. */
    private fun onPageSettled(position: Int, resume: Boolean = false) {
        if (position !in shorts.indices) return
        settledPosition = position
        val page = adapter.pageAt(position) ?: return
        val model = shorts[position]

        if (PlaybackCenter.currentEntry?.url != model.url) {
            PlaybackCenter.playVideo(model.toQueueEntry(isMusic = false))
        } else if (resume) {
            runCatching { PlaybackCenter.player.play() }
        }

        // Move the shared player surface to the newly settled page AFTER playVideo
        // ensures playerRef is initialized.
        if (attachedPage !== page || page.videoSlot.player == null) {
            attachedPage?.let { if (it !== page) PlaybackCenter.detachView(it.videoSlot) }
            attachedPage = page
            applyFillModeToAttachedPage()
            PlaybackCenter.attachView(page.videoSlot)
        }
        updatePageUi(page)
        // v1.7.4: remember this short (so it never repeats this session) and
        // resolve the next 5 streams ahead of time for instant swipes.
        ShortsSeenStore.seen.add(model.url)
        PlaybackCenter.prefetchShorts(
            shorts.drop(position + 1).take(5).map { it.url }
        )
        if (position >= shorts.size - 5) {
            loadMoreShorts()
        }
    }

    private fun loadMoreShorts() {
        if (loadingMore) return
        loadingMore = true
        val pageIdx = morePage++
        lifecycleScope.launch {
            try {
                val extra = YtRepository.shortsFeedMore(AppPrefs.countryOrDefault, pageIdx)
                val known = shorts.map { it.url }.toHashSet()
                val additions = extra.mapNotNull { item ->
                    val url = "https://www.youtube.com/watch?v=${item.videoId}"
                    if (known.add(url)) {
                        com.sparktube.app.ui.common.VideoUiModel(
                            url = url,
                            title = item.title,
                            uploader = item.uploader,
                            thumbnailUrl = item.thumbnailUrl,
                            durationSec = 0L,
                            viewCount = 0L,
                            uploadDate = ""
                        )
                    } else null
                }
                if (additions.isNotEmpty()) {
                    val start = shorts.size
                    // v1.7.4: never append shorts that were already watched.
                    val fresh = ShortsSeenStore.fresh(additions) { it.url }
                    shorts.addAll(fresh)
                    adapter.notifyItemRangeInserted(start, fresh.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loadingMore = false
            }
        }
    }

    /** Center tap: play/pause (restart the short when it already ended). */
    private fun togglePlayPause() {
        if (!PlaybackCenter.hasMedia) return
        runCatching {
            val player = PlaybackCenter.player
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
                player.play()
            } else {
                PlaybackCenter.togglePlayPause()
            }
        }
        attachedPage?.let { updatePageUi(it) }
    }

    /** Mirrors PlaybackCenter state onto the visible page (clean UI). */
    private fun updatePageUi(page: ShortsPageView) {
        val isCurrent = page === attachedPage
        if (!isCurrent) return
        val hasMedia = PlaybackCenter.hasMedia
        val resolving = PlaybackCenter.isResolving
        val buffering = hasMedia && runCatching {
            PlaybackCenter.player.playbackState == Player.STATE_BUFFERING
        }.getOrDefault(false)
        val ended = hasMedia && runCatching {
            PlaybackCenter.player.playbackState == Player.STATE_ENDED
        }.getOrDefault(false)
        val playing = PlaybackCenter.isPlaying

        page.loading.isVisible = resolving || buffering
        // The cover stays until the very first frame is rendered.
        page.cover.isVisible = !hasMedia || ended || resolving
        page.centerPlay.isVisible = !playing && !resolving && !buffering
        page.centerPlay.setImageResource(R.drawable.ic_play_arrow)
    }

    private fun updateCurrentPageUi() {
        attachedPage?.let { page ->
            if (page.videoSlot.player == null && PlaybackCenter.hasMedia) {
                applyFillModeToAttachedPage()
                PlaybackCenter.attachView(page.videoSlot)
            }
            updatePageUi(page)
        }
    }

    override fun onDestroy() {
        attachedPage?.let { PlaybackCenter.detachView(it.videoSlot) }
        attachedPage = null
        // v1.7.4: leaving the shorts screen ends the preload session — the
        // next-open feed and prefetch cache start from scratch.
        PlaybackCenter.clearShortsPrefetch()
        super.onDestroy()
    }

    // ----- PlaybackCenter.Listener -----

    override fun onItemChanged(entry: com.sparktube.app.playback.QueueEntry?) = updateCurrentPageUi()
    override fun onResolvingChanged(isResolving: Boolean) = updateCurrentPageUi()
    override fun onPlaybackStateChanged(isPlaying: Boolean) = updateCurrentPageUi()
    override fun onCatalogReady() = updateCurrentPageUi()
    override fun onError(message: String) {
        updateCurrentPageUi()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun onLiveContentBlocked() {
        updateCurrentPageUi()
        Toast.makeText(this, R.string.shorts_empty, Toast.LENGTH_SHORT).show()
    }

    // ----- Feed loading -----

    private fun load() {
        binding.emptyView.isVisible = false
        binding.progress.isVisible = true
        lifecycleScope.launch {
            try {
                val items = fetchShorts()
                shorts.clear()
                shorts.addAll(items)
                adapter.notifyDataSetChanged()
                binding.progress.isVisible = false
                binding.emptyView.isVisible = shorts.isEmpty()
                if (shorts.isNotEmpty()) {
                    binding.recycler.post {
                        val lm = binding.recycler.layoutManager as? LinearLayoutManager
                        val pos = lm?.findFirstCompletelyVisibleItemPosition() ?: 0
                        onPageSettled(if (pos >= 0) pos else 0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.progress.isVisible = false
                binding.emptyView.isVisible = shorts.isEmpty()
                Toast.makeText(this@ShortsActivity, R.string.shorts_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Shorts / Reels feed (v1.7.1), in priority order:
     *  1. InnerTube shorts feed — country-preference aware, and PERSONALIZED
     *     for the logged-in Google account (session cookies are attached).
     *  2. Trending items filtered to ≤ 3 minutes.
     *  3. A search top-up so the screen is never empty.
     */
    private suspend fun fetchShorts(): List<com.sparktube.app.ui.common.VideoUiModel> {
        val out = LinkedHashMap<String, com.sparktube.app.ui.common.VideoUiModel>()

        // 1) Real shorts feed (country + account personalization)
        runCatching {
            YtRepository.shortsFeed(AppPrefs.countryOrDefault)
        }.getOrDefault(emptyList()).forEach { item ->
            val url = "https://www.youtube.com/watch?v=${item.videoId}"
            if (out.containsKey(url)) return@forEach
            out[url] = com.sparktube.app.ui.common.VideoUiModel(
                url = url,
                title = item.title,
                uploader = item.uploader,
                thumbnailUrl = item.thumbnailUrl,
                durationSec = 0L,
                viewCount = 0L,
                uploadDate = ""
            )
        }

        // 2) Trending shorts (≤ 3 minutes)
        if (out.size < 12) {
            runCatching {
                YtRepository.trending(AppPrefs.countryOrDefault)
            }.getOrDefault(emptyList())
                .filter { it.duration in 1L..180L }
                .forEach { item ->
                    val model = item.toUiModel()
                    if (model.url.isNotBlank()) out.putIfAbsent(model.url, model)
                }
        }

        // 3) Search top-up
        if (out.size < 12) {
            runCatching {
                YtRepository.searchVideos("hindi romantic trending reels shorts", null).items
            }.getOrDefault(emptyList())
                .filter { it.duration in 1L..180L }
                    .forEach { item ->
                        val model = item.toUiModel()
                        if (model.url.isNotBlank()) out.putIfAbsent(model.url, model)
                    }
        }
        // v1.7.4: serve unseen shorts first — the same feed no longer repeats
        // on every open (recycles only when the pool is exhausted).
        return ShortsSeenStore.fresh(out.values.toList()) { it.url }
    }

    /**
     * One full-screen shorts page: video surface + cover placeholder,
     * loading spinner, center play button, bottom title/channel scrim.
     */
    private class ShortsPageView(
        context: Context,
        onVideoTap: () -> Unit
    ) : android.widget.FrameLayout(context) {

        val videoSlot: PlayerView
        val cover: ShapeableImageView
        val loading: android.widget.ProgressBar
        val centerPlay: ImageButton
        val title: TextView
        val subtitle: TextView
        val duration: TextView

        init {
            val d = context.resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()

            videoSlot = PlayerView(context).apply {
                useController = false
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setShutterBackgroundColor(Color.BLACK)
            }
            addView(videoSlot)

            cover = ShapeableImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            addView(cover)

            val scrim = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xD9000000.toInt(), 0x00000000)
                )
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(200), Gravity.BOTTOM
                )
            }
            addView(scrim)

            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                ).also { it.setMargins(px(18), 0, px(18), px(22)) }
            }

            duration = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = px(9).toFloat()
                    setColor(0x88FF0033.toInt())
                }
                setPadding(px(10), px(4), px(10), px(4))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            info.addView(duration)

            title = TextView(context).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = px(8) }
            }
            info.addView(title)

            subtitle = TextView(context).apply {
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = px(4) }
            }
            info.addView(subtitle)
            addView(info)

            loading = android.widget.ProgressBar(context).apply {
                layoutParams = LayoutParams(px(46), px(46), Gravity.CENTER)
            }
            addView(loading)

            centerPlay = ImageButton(context).apply {
                setBackgroundResource(R.drawable.bg_glass_circle_inset)
                setImageResource(R.drawable.ic_play_arrow)
                setColorFilter(Color.WHITE)
                layoutParams = LayoutParams(px(84), px(84), Gravity.CENTER)
                setOnClickListener { onVideoTap() }
            }
            addView(centerPlay)
        }
    }

    private class ShortsPagerAdapter(
        private val items: List<com.sparktube.app.ui.common.VideoUiModel>,
        private val onVideoTap: () -> Unit
    ) : RecyclerView.Adapter<ShortsPagerAdapter.VH>() {

        private val pages = HashMap<Int, ShortsPageView>()

        class VH(val page: ShortsPageView) : RecyclerView.ViewHolder(page)

        fun pageAt(position: Int): ShortsPageView? =
            pages[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val page = ShortsPageView(parent.context, onVideoTap).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return VH(page)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val model = items[position]
            val page = holder.page
            pages[position] = page
            Thumbs.load(page.cover, model.thumbnailUrl)
            page.title.text = model.title
            page.subtitle.text = model.uploader
            val label = model.durationLabel
            page.duration.isVisible = label.isNotBlank()
            page.duration.text = label
            page.videoSlot.resizeMode =
                if (AppPrefs.shortsFillMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            page.contentDescription = "${model.title}, ${model.uploader}"
            page.setOnClickListener { onVideoTap() }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ShortsActivity::class.java))
        }
    }
}
