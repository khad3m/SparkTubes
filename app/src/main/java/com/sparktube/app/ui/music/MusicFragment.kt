package com.sparktube.app.ui.music

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.imageview.ShapeableImageView
import com.sparktube.app.R
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import com.sparktube.app.util.DeviceTuning
import com.sparktube.app.databinding.FragmentMusicBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.SkeletonPulse
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.ScrollsToTop
import com.sparktube.app.ui.search.SearchActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.launch

private data class Genre(val label: String, val query: String?, val top: Int, val bottom: Int)

/**
 * Apple Music "Home / Listen Now" experience:
 * - Large bold header with music super-label
 * - Quick Library rows (Favorites & Downloaded)
 * - "Top Picks for You" tall editorial cards with blurred footer caption
 * - "Trending Now" 156dp rounded artwork shelf
 * - "Top Songs" ranked chart rows with interactive ••• song action sheet
 * - "Browse by Category" Apple Music vibrant gradient cards
 */
class MusicFragment : Fragment(), ScrollsToTop {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var loadJob: kotlinx.coroutines.Job? = null
    private var skeletonPulse: SkeletonPulse? = null
    private var cachedModels: List<VideoUiModel> = emptyList()
    private var loadedCountry: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.greeting.setText(greetingRes())
        binding.errorRetry.setOnClickListener { load() }
        binding.swipe.setColorSchemeColors(requireContext().getColor(R.color.music_green))
        binding.swipe.setOnRefreshListener { load(fromPull = true) }
        Motion.pressable(binding.favoritesButton, 0.97f)
        Motion.pressable(binding.downloadsButton, 0.97f)
        Motion.pressable(binding.playlistsButton, 0.97f)
        Motion.pressable(binding.searchButton, 0.98f)
        Motion.pressable(binding.profileBadge, 0.9f)
        setupQuickMixes()

        binding.searchButton.setOnClickListener {
            startActivity(SearchActivity.intent(requireContext(), music = true))
        }
        binding.favoritesButton.setOnClickListener {
            MusicListActivity.start(requireContext(), favourites = true)
        }
        binding.downloadsButton.setOnClickListener {
            MusicListActivity.start(requireContext(), favourites = false)
        }
        binding.playlistsButton.setOnClickListener {
            showPlaylistMusicPicker()
        }
        binding.profileBadge.setOnClickListener {
            if (PlaybackCenter.hasMedia) {
                NowPlayingActivity.start(requireContext())
            } else if (cachedModels.isNotEmpty()) {
                playSong(cachedModels.first(), radio = true)
            }
        }
        binding.playAllPopular.setOnClickListener {
            if (cachedModels.isNotEmpty()) {
                val entries = cachedModels.map { it.toQueueEntry(isMusic = true) }
                PlaybackCenter.playMusicQueue(entries, 0)
                NowPlayingActivity.start(requireContext())
            }
        }

        buildGenreTiles()
        // Tab views are destroyed when the tab is not visible (saves RAM); rebuild from cache, no network.
        if (cachedModels.isNotEmpty() && loadedCountry == AppPrefs.countryOrDefault) {
            bindTrending(binding, cachedModels)
        } else {
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshIfNeeded()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshIfNeeded()
    }

    /** Reload when the country changed in Menu, or when a previous load left the shelf empty. */
    private fun refreshIfNeeded() {
        if (_binding == null || isHidden) return
        val stale = loadedCountry != null && loadedCountry != AppPrefs.countryOrDefault
        val empty = binding.shelfRow.childCount == 0
        if (stale || (empty && loadJob?.isActive != true)) load()
    }

    private fun greetingRes(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }
    }

    private fun playSong(model: VideoUiModel, radio: Boolean) {
        PlaybackCenter.playMusic(model.toQueueEntry(isMusic = true), radio = radio)
        NowPlayingActivity.start(requireContext())
    }

    private fun showPlaylistMusicPicker() {
        val ctx = requireContext()
        val playlists = LocalStore.playlists(ctx)
        val labels = (listOf("+ Create New Playlist") + playlists.map { "${it.name} (${it.items.size})" }).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Music Playlists")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog()
                } else {
                    val chosen = playlists[which - 1]
                    if (chosen.items.isEmpty()) {
                        Toast.makeText(ctx, "This playlist is empty — tap ••• on any song to add", Toast.LENGTH_SHORT).show()
                    } else {
                        val entries = chosen.items.map { it.toUiModel().toQueueEntry(isMusic = true) }
                        PlaybackCenter.playMusicQueue(entries, 0, allowRadio = true)
                        NowPlayingActivity.start(ctx)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.new_playlist_hint)
            setSingleLine()
        }
        val wrap = FrameLayout(ctx).apply {
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.new_playlist)
            .setView(wrap)
            .setPositiveButton(R.string.create_playlist) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                when {
                    name.isEmpty() -> Toast.makeText(ctx, R.string.playlist_name_empty, Toast.LENGTH_SHORT).show()
                    LocalStore.createPlaylist(ctx, name) == null ->
                        Toast.makeText(ctx, R.string.playlist_name_exists, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(ctx, "Playlist \"$name\" created! Tap ••• on any song to add.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun load(fromPull: Boolean = false) {
        loadJob?.cancel()
        val b = _binding ?: return
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.errorView.isVisible = false
            if (!fromPull) showMusicSkeleton(b)
            try {
                val country = AppPrefs.countryOrDefault
                val warm = run {
                    var w = LocalStore.readFeedCache(requireContext(), true, country, "music", DeviceTuning.feedCacheTtlMs(requireContext()))
                    if (w.isEmpty()) w = LocalStore.readFeedCache(requireContext(), true, country, "music", 0L)
                    w // v1.9.6: stale cards render instantly, fresh list still refreshes below
                }
                if (warm.isNotEmpty() && cachedModels.isEmpty()) {
                    cachedModels = warm.map { it.toUiModel() }
                    loadedCountry = country
                    clearMusicSkeleton(b)
                    bindTrending(b, cachedModels, animate = false)
                }
                // v1.9.4: usage-based music home — artists/keywords the user
                // actually listens to drive the feed, trending is the fallback.
                val snap = RecommendEngine.snapshot(requireContext())
                val result = if (snap.hasSignal) {
                    YtRepository.personalizedMusicFeed(country, snap)
                } else {
                    YtRepository.musicTrending(country)
                }
                val models = result.items.map { it.toUiModel() }
                LocalStore.saveFeedCache(requireContext(), true, country, "music", models.map { it.toEntry() })
                cachedModels = models
                loadedCountry = country
                if (!fromPull) clearMusicSkeleton(b)
                // The entrance moment plays only on a first load, never on refresh or tab return
                bindTrending(b, models, animate = !fromPull)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fromPull && cachedModels.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(), "Couldn't refresh. Check your connection.", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    clearMusicSkeleton(b)
                    b.errorText.text = Formatters.friendlyException(e)
                    b.errorView.isVisible = true
                }
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun showMusicSkeleton(b: FragmentMusicBinding) {
        clearMusicSkeleton(b)
        b.shelfTitle.isVisible = true
        b.shelfScroller.isVisible = true
        b.popularTitle.isVisible = true
        val pulse = SkeletonPulse()
        skeletonPulse = pulse
        repeat(8) { i ->
            val card = layoutInflater.inflate(R.layout.item_skeleton_music_card, b.shelfRow, false)
            b.shelfRow.addView(card)
            pulse.attach(card, i)
        }
        repeat(6) { i ->
            val row = layoutInflater.inflate(R.layout.item_skeleton_row, b.popularList, false)
            b.popularList.addView(row)
            pulse.attach(row, i)
        }
    }

    private fun clearMusicSkeleton(b: FragmentMusicBinding) {
        skeletonPulse?.cancel()
        skeletonPulse = null
        b.topPicksRow.removeAllViews()
        b.shelfRow.removeAllViews()
        b.popularList.removeAllViews()
    }

    private fun bindTrending(b: FragmentMusicBinding, models: List<VideoUiModel>, animate: Boolean = false) {
        val hasItems = models.isNotEmpty()
        b.topPicksTitle.isVisible = hasItems
        b.topPicksScroller.isVisible = hasItems
        b.shelfTitle.isVisible = hasItems
        b.shelfScroller.isVisible = hasItems
        b.popularTitle.isVisible = hasItems

        // 1. Apple Music "Top Picks for You" Editorial Cards (first 4 items)
        b.topPicksRow.removeAllViews()
        val tints = intArrayOf(
            0xFF2A0A12.toInt(),
            0xFF1C0F14.toInt(),
            0xFF2B1218.toInt(),
            0xFF171114.toInt()
        )
        models.take(4).forEachIndexed { idx, model ->
            b.topPicksRow.addView(
                buildEditorialCard(
                    model = model,
                    footerColor = tints[idx % tints.size]
                )
            )
        }

        // 2. Apple Music "Trending Now" Shelf (items 4..14)
        b.shelfRow.removeAllViews()
        models.drop(4).take(10).ifEmpty { models.take(10) }.forEach { model ->
            val card = layoutInflater.inflate(R.layout.item_music_card, b.shelfRow, false)
            Thumbs.load(card.findViewById(R.id.art), model.thumbnailUrl)
            card.findViewById<TextView>(R.id.title).text = model.title
            card.findViewById<TextView>(R.id.subtitle).text = model.uploader
            card.setOnClickListener { playSong(model, radio = true) }
            card.setOnLongClickListener {
                showAppleMusicTrackSheet(model)
                true
            }
            Motion.pressable(card, 0.95f)
            b.shelfRow.addView(card)
        }

        // 3. Apple Music "Top Songs" Ranked List
        b.popularList.removeAllViews()
        models.drop(10).take(15).ifEmpty { models.take(12) }.forEachIndexed { index, model ->
            val row = layoutInflater.inflate(R.layout.item_music_row, b.popularList, false)
            Thumbs.load(row.findViewById(R.id.art), model.thumbnailUrl)
            row.findViewById<TextView>(R.id.rank)?.apply {
                text = (index + 1).toString()
                isVisible = true
                // keep the hairline divider aligned with the text, not the rank number
                (row.findViewById<View>(R.id.divider)?.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?.let { it.marginStart += dp(26) }
            }
            row.findViewById<TextView>(R.id.title).text = model.title
            row.findViewById<TextView>(R.id.subtitle).text = model.uploader
            row.findViewById<TextView>(R.id.duration).text = model.durationLabel
            row.setOnClickListener { playSong(model, radio = true) }
            row.setOnLongClickListener {
                showAppleMusicTrackSheet(model)
                true
            }
            Motion.pressable(row, 0.985f)
            b.popularList.addView(row)
        }

        // One orchestrated moment: the hero cards rise in on first load only.
        // Everything else appears instantly so the screen never feels slow.
        if (animate) {
            Motion.enter(b.topPicksRow.children.toList(), AppPrefs.animations, dp(18).toFloat(), limit = 4)
        }
    }

    /** Builds an authentic Apple Music tall editorial card (240dp x 300dp). */
    private fun buildEditorialCard(
        model: VideoUiModel,
        footerColor: Int
    ): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(236), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            }
        }

        val cardFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(236), dp(286))
            contentDescription = "${model.title}, ${model.uploader}"
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(footerColor)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { playSong(model, radio = true) }
            setOnLongClickListener {
                showAppleMusicTrackSheet(model)
                true
            }
            Motion.pressable(this, 0.97f)
        }

        val artView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210),
                Gravity.TOP
            )
        }
        Thumbs.load(artView, model.thumbnailUrl)
        cardFrame.addView(artView)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(footerColor, Color.BLACK)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(82),
                Gravity.BOTTOM
            )
        }

        footer.addView(
            TextView(ctx).apply {
                text = model.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
        )
        footer.addView(
            TextView(ctx).apply {
                text = model.uploader
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xCCFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
        )

        cardFrame.addView(footer)
        container.addView(cardFrame)
        return container
    }

    /** Long-press context sheet (shared iOS-style ActionSheet). */
    private fun showAppleMusicTrackSheet(model: VideoUiModel) {
        val ctx = requireContext()
        val items = mutableListOf(
            ActionSheet.Item("Play Now") { playSong(model, radio = false) },
            ActionSheet.Item("Start Station") { playSong(model, radio = true) },
            // v1.7.4: file the song into (or remove it from) a playlist.
            ActionSheet.Item("Add to Playlist") {
                PlaylistPicker.show(ctx, model.toEntry(), model.title, model.uploader)
            }
        )
        // Favorite only toggles the track that is actually loaded in the player, so it never
        // starts playback by surprise or flips the wrong song.
        if (PlaybackCenter.currentEntry?.url == model.url) {
            val isFav = LocalStore.isFavorite(ctx, model.url)
            items += ActionSheet.Item(if (isFav) "Remove from Favorites" else "Add to Favorites", accent = true) {
                val nowFav = PlaybackCenter.toggleFavoriteCurrent()
                Toast.makeText(
                    ctx,
                    if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        ActionSheet.show(ctx, model.title, model.uploader, items)
    }

    private fun buildGenreTiles() {
        val grid = binding.genreGrid
        grid.removeAllViews()
        val margin = dp(6)
        GENRES.forEach { genre ->
            val ctx = requireContext()
            val tile = FrameLayout(ctx).apply {
                minimumHeight = dp(90)
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(14).toFloat()
                    colors = intArrayOf(genre.top, genre.bottom)
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                isClickable = true
                isFocusable = true
                Motion.pressable(this, 0.95f)

                addView(
                    ImageView(ctx).apply {
                        // v1.9.6: per-genre premium watermark glyph
                        setImageResource(
                            when (genre.label) {
                                "Top Charts" -> R.drawable.ic_speed
                                "Hip-Hop" -> R.drawable.ic_headset
                                "Bollywood" -> R.drawable.ic_audiotrack
                                "Rock" -> R.drawable.ic_speed
                                "Dance & EDM" -> R.drawable.ic_audiotrack
                                else -> R.drawable.ic_music_note
                            }
                        )
                        setColorFilter(Color.WHITE)
                        alpha = 0.22f
                        rotation = 18f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(88), dp(88), Gravity.END or Gravity.TOP).apply {
                        marginEnd = -dp(8)
                        topMargin = -dp(8)
                    }
                )
                addView(
                    TextView(ctx).apply {
                        text = genre.label
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.START or Gravity.BOTTOM
                    ).apply { setMargins(dp(14), dp(14), dp(14), dp(12)) }
                )
                setOnClickListener {
                    if (genre.query == null) {
                        binding.scroll.smoothScrollTo(0, 0)
                        load()
                    } else {
                        // v1.9.6: preset loads results in-app — no visible YouTube search page.
                        com.sparktube.app.ui.music.MusicListActivity.startPreset(
                            requireContext(), genre.label + " Songs", genre.query.orEmpty()
                        )
                    }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                topMargin = margin
                bottomMargin = margin
                marginStart = margin / 2
                marginEnd = margin / 2
            }
            grid.addView(tile, params)
        }
    }

    override fun scrollToTop() {
        _binding?.scroll?.smoothScrollTo(0, 0)
    }

    private fun setupQuickMixes() {
        val row = binding.quickMixRow
        row.removeAllViews()
        val history = LocalStore.musicHistory(requireContext())
        val searches = LocalStore.searches(requireContext(), music = true)
        val mixes = listOf(
            Triple("My Favorites", "favorites", R.drawable.ic_favorite),
            Triple("Recently Played", "recent", R.drawable.ic_history),
            Triple("Daily Chill", "lofi songs", R.drawable.ic_headset),
            Triple("Bangla Hits", "bangla songs", R.drawable.ic_music_note),
            Triple("Workout", "workout music", R.drawable.ic_play_arrow)
        )
        mixes.forEachIndexed { index, mix ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(requireContext().getColor(R.color.music_green).let { c ->
                        android.graphics.Color.argb(28, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                    })
                    setStroke(dp(1), android.graphics.Color.argb(48, 255,255,255))
                }
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(requireContext()).apply {
                setImageResource(mix.third)
                setColorFilter(requireContext().getColor(R.color.music_green))
                contentDescription = null
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            val title = TextView(requireContext()).apply {
                text = mix.first
                setTextColor(requireContext().getColor(R.color.on_surface))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(title, LinearLayout.LayoutParams(dp(105), dp(30)))
            Motion.pressable(card, 0.96f)
            card.setOnClickListener {
                when (mix.second) {
                    "favorites" -> {
                        val favs = LocalStore.musicFavorites(requireContext())
                        if (favs.isEmpty()) Toast.makeText(requireContext(), "No favorite songs yet", Toast.LENGTH_SHORT).show()
                        else PlaybackCenter.playMusic(favs.first().toUiModel().toQueueEntry(isMusic = true), radio = true)
                    }
                    "recent" -> {
                        if (history.isEmpty()) Toast.makeText(requireContext(), "Your music history is empty", Toast.LENGTH_SHORT).show()
                        else PlaybackCenter.playMusic(history.first().toUiModel().toQueueEntry(isMusic = true), radio = true)
                    }
                    else -> com.sparktube.app.ui.music.MusicListActivity.startPreset(
                        requireContext(), mix.first, mix.second
                    )
                }
                if (mix.second == "favorites" || mix.second == "recent") NowPlayingActivity.start(requireContext())
            }
            row.addView(card, LinearLayout.LayoutParams(dp(116), dp(112)).apply {
                marginEnd = dp(10)
            })
        }
        if (searches.isNotEmpty()) {
            binding.quickMixHint.text = "Based on your listening"
        } else if (history.isNotEmpty()) {
            binding.quickMixHint.text = "Based on your history"
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroyView() {
        // Stop pulsing skeleton animators and any in-flight load: they hold the old views.
        loadJob?.cancel()
        loadJob = null
        skeletonPulse?.cancel()
        skeletonPulse = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val GENRES = listOf(
            Genre("Top Charts", null, 0xFFFF0033.toInt(), 0xFF8A0019.toInt()),
            Genre("Pop", "pop hits", 0xFFFF3B5C.toInt(), 0xFFB0123B.toInt()),
            Genre("Hip-Hop", "hip hop hits", 0xFF3A3A44.toInt(), 0xFF111116.toInt()),
            Genre("Bollywood", "bollywood songs", 0xFFFF7A3D.toInt(), 0xFFE0112B.toInt()),
            Genre("Bangla", "bangla songs", 0xFFFF4D4D.toInt(), 0xFF8E1020.toInt()),
            Genre("Rock", "rock hits", 0xFFE5173F.toInt(), 0xFF4A0010.toInt()),
            Genre("Dance & EDM", "edm hits", 0xFFFF2E93.toInt(), 0xFF7A1FA2.toInt()),
            Genre("Chill & Lofi", "lofi songs", 0xFF8C3B5C.toInt(), 0xFF3A1A2E.toInt()),
            Genre("Classical", "classical music", 0xFFC2475A.toInt(), 0xFF5C1A36.toInt()),
            Genre("Indie", "indie songs", 0xFFFF6B6B.toInt(), 0xFFC2185B.toInt()),
            Genre("Acoustic", "acoustic songs", 0xFFFFA14A.toInt(), 0xFFB5352F.toInt()),
            Genre("Essentials", "instrumental music", 0xFF55555F.toInt(), 0xFF1E1E24.toInt())
        )
    }
}
