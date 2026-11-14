package com.sparktube.app.ui.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.ActivityNowPlayingBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.player.DownloadSheet
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.PanelTransitions
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Apple Music Full-Screen Now Playing Sheet:
 * - Dynamic artwork-sampled ambient gradient backdrop
 * - Spring-animated floating album artwork (1.0f playing, 0.80f paused)
 * - Interactive drag-down-to-dismiss that follows the finger
 * - Scrubber with elapsed time, audio badge, and negative remaining time (-3:12)
 * - Hardware volume slider bound to AudioManager.STREAM_MUSIC
 * - Interactive Lyrics / Track Info overlay toggle, Station builder, Download, and Up Next Queue
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var volumeDragging = false
    private var lyricsVisible = false
    private var boundUrl: String? = null
    private var backdropRequest: Disposable? = null
    private var lastSlowTick = 0L
    private var skipCloseAnim = false

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) = bind()
        override fun onPlaybackStateChanged(isPlaying: Boolean) = bindPlayButton()
        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving
        }
        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = bindFavorite()
        override fun onQueueChanged() = bindUpNext()
    }

    // Fast tick only while playing; slow tick (downloads/disk checks) at most once a second.
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateProgress()
            syncVolumeBar()
            val now = SystemClock.uptimeMillis()
            if (now - lastSlowTick >= 1000L) {
                lastSlowTick = now
                updateDownloadState()
            }
            handler.postDelayed(this, if (PlaybackCenter.isPlaying) 250L else 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        PanelTransitions.install(this)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PlaybackCenter.hasMedia) {
            finish()
            return
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestNotificationPermissionIfNeeded()

        binding.backButton.setOnClickListener { finish() }

        binding.playPauseButton.setOnClickListener { view ->
            bounceButton(view)
            PlaybackCenter.togglePlayPause()
        }
        binding.prevButton.setOnClickListener { view ->
            bounceButton(view)
            PlaybackCenter.previous()
        }
        binding.nextButton.setOnClickListener { view ->
            bounceButton(view)
            PlaybackCenter.next()
        }

        binding.favoriteButton.setOnClickListener { view ->
            bounceButton(view)
            val nowFav = PlaybackCenter.toggleFavoriteCurrent()
            Toast.makeText(
                this,
                if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.moreOptionsButton.setOnClickListener {
            showAppleMusicOptionsDialog()
        }

        binding.lyricsButton.setOnClickListener { view ->
            bounceButton(view)
            toggleLyricsOverlay()
        }
        binding.radioButton.setOnClickListener { view ->
            bounceButton(view)
            restartRadio()
        }
        binding.downloadButton.setOnClickListener { view ->
            bounceButton(view)
            onDownloadClicked()
        }
        binding.queueButton.setOnClickListener { view ->
            bounceButton(view)
            showQueue()
        }
        binding.upNextCard.setOnClickListener { showQueue() }

        // Lyrics / info card: sits above the artwork (elevation), rounded, same square as the art
        val density = resources.displayMetrics.density
        binding.lyricsOverlay.apply {
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xE6141418.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = 40f * density
        }

        // Keep artwork (and the lyrics card) square inside the available space
        binding.artContainer.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = minOf(r - l, b - t).coerceAtLeast(0)
            val lp = binding.art.layoutParams
            if (size > 0 && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                binding.art.layoutParams = lp
                val olp = binding.lyricsOverlay.layoutParams as FrameLayout.LayoutParams
                olp.width = size
                olp.height = size
                olp.gravity = Gravity.CENTER
                binding.lyricsOverlay.layoutParams = olp
            }
        }

        // Apple Music Scrubber
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = PlaybackCenter.durationMs.coerceAtLeast(0L)
                    binding.position.text = formatTime(progress.toLong())
                    val rem = (dur - progress.toLong()).coerceAtLeast(0L)
                    binding.duration.text = "-${formatTime(rem)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                seekBar?.animate()?.scaleY(1.6f)?.setDuration(150)?.start()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                seekBar?.animate()?.scaleY(1f)?.setDuration(150)?.start()
                seekBar?.let { PlaybackCenter.seekTo(it.progress.toLong()) }
            }
        })

        // Apple Music Volume Slider
        setupVolumeSlider()
        // bind() runs from onStart so it also refreshes when returning to the screen
    }

    private fun setupVolumeSlider() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSeekBar.max = maxVol
        binding.volumeSeekBar.progress = curVol
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                volumeDragging = true
                seekBar?.animate()?.scaleY(1.5f)?.setDuration(140)?.start()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                volumeDragging = false
                seekBar?.animate()?.scaleY(1f)?.setDuration(140)?.start()
            }
        })
    }

    private fun syncVolumeBar() {
        if (isFinishing || volumeDragging) return
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (binding.volumeSeekBar.progress != curVol) {
            binding.volumeSeekBar.progress = curVol
        }
    }

    private fun bounceButton(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.animate().cancel()
        view.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .setDuration(180)
                    .start()
            }
            .start()
    }

    private fun toggleLyricsOverlay() {
        lyricsVisible = !lyricsVisible
        binding.lyricsOverlay.isVisible = lyricsVisible
        binding.art.alpha = if (lyricsVisible) 0.18f else 1f
        binding.lyricsButton.alpha = if (lyricsVisible) 1f else 0.75f
        binding.lyricsButton.setColorFilter(
            if (lyricsVisible) getColor(R.color.music_green) else Color.WHITE
        )
        updateLyricsContent()
    }

    private fun updateLyricsContent() {
        val entry = PlaybackCenter.currentEntry ?: return
        val desc = PlaybackCenter.catalog?.description?.trim().orEmpty()
        val sb = SpannableStringBuilder()
        val titleStart = sb.length
        sb.append(entry.title).append('\n')
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length, 0)
        sb.setSpan(RelativeSizeSpan(1.3f), titleStart, sb.length, 0)
        val artistStart = sb.length
        sb.append(entry.uploader).append("\n\n")
        sb.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), artistStart, sb.length, 0)
        sb.append(if (desc.isNotBlank()) desc else "No description available for this track.")
        binding.lyricsText.text = sb
    }

    private fun showAppleMusicOptionsDialog() {
        val entry = PlaybackCenter.currentEntry ?: return
        ActionSheet.show(
            this, entry.title, entry.uploader,
            listOf(
                ActionSheet.Item("Start Station") { restartRadio() },
                ActionSheet.Item("Download") { onDownloadClicked() },
                ActionSheet.Item("Share Song Link") {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${entry.title} — ${entry.url}")
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_via)))
                }
            )
        )
    }

    /**
     * Ambient backdrop from the artwork. Decodes a tiny 48px copy (not the full-size thumbnail),
     * cancels any in-flight request when the track changes, and cross-fades to the new colors.
     */
    private fun applyDynamicArtworkBackdrop(url: String) {
        backdropRequest?.dispose()
        val req = ImageRequest.Builder(this)
            .data(url)
            .size(48, 48)
            .allowHardware(false)
            .target { drawable ->
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return@target
                if (isFinishing || isDestroyed) return@target
                // Artwork color pulled slightly toward the app's red so every track feels on-brand
                val red = 0xFFFF0033.toInt()
                val top = ColorUtils.blendARGB(tone(averageColor(bmp, 0, bmp.height / 2), 0.46f), red, 0.14f)
                val mid = ColorUtils.blendARGB(tone(averageColor(bmp, bmp.height / 2, bmp.height), 0.22f), red, 0.07f)
                val bottom = tone(averageColor(bmp, bmp.height / 2, bmp.height), 0.08f)
                val next = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(top, mid, bottom)
                )
                val current = binding.nowPlayingRoot.background
                val from = (if (current is TransitionDrawable) current.getDrawable(1) else current)
                    ?: ColorDrawable(0xFF08080C.toInt())
                val td = TransitionDrawable(arrayOf(from, next))
                binding.nowPlayingRoot.background = td
                td.startTransition(if (AppPrefs.animations) 450 else 0)
            }
            .build()
        backdropRequest = imageLoader.enqueue(req)
    }

    private fun averageColor(bmp: Bitmap, y0: Int, y1: Int): Int {
        val step = maxOf(1, bmp.width / 12)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                x += step
            }
            y += step
        }
        if (n == 0L) return 0xFF121217.toInt()
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun tone(color: Int, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    // ----- Interactive drag-down to collapse (follows the finger, flings away, or springs back) -----

    private var downX = 0f
    private var downY = 0f
    private var dragOriginY = 0f
    private var canDrag = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private val hitRect = android.graphics.Rect()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                canDrag = !isOnInteractiveArea(ev)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (canDrag && !dragging) {
                    val dy = ev.rawY - downY
                    val dx = kotlin.math.abs(ev.rawX - downX)
                    val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
                    if (dy > slop && dy > dx * 1.5f) {
                        dragging = true
                        dragOriginY = ev.rawY
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (dragging) {
                    val dy = (ev.rawY - dragOriginY).coerceAtLeast(0f)
                    val h = binding.nowPlayingRoot.height.coerceAtLeast(1)
                    binding.nowPlayingRoot.translationY = dy
                    binding.nowPlayingRoot.alpha = 1f - (dy / h) * 0.35f
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                if (wasDragging) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    releaseDrag(velocity?.yVelocity ?: 0f)
                }
                dragging = false
                canDrag = false
                velocity?.recycle()
                velocity = null
                if (wasDragging) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun releaseDrag(vy: Float) {
        val root = binding.nowPlayingRoot
        val h = root.height.coerceAtLeast(1)
        if (root.translationY > h * 0.22f || vy > 1400f) {
            root.animate()
                .translationY(h.toFloat())
                .alpha(0.6f)
                .setInterpolator(AccelerateInterpolator(1.2f))
                .setDuration(200)
                .withEndAction {
                    skipCloseAnim = true
                    finish()
                }
                .start()
        } else {
            root.animate()
                .translationY(0f)
                .alpha(1f)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .setDuration(240)
                .start()
        }
    }

    private fun isOnInteractiveArea(ev: MotionEvent): Boolean {
        val slack = (16 * resources.displayMetrics.density).toInt()
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        if (binding.seekBar.getGlobalVisibleRect(hitRect)) {
            hitRect.inset(-slack, -slack)
            if (hitRect.contains(x, y)) return true
        }
        if (binding.volumeSeekBar.getGlobalVisibleRect(hitRect)) {
            hitRect.inset(-slack, -slack)
            if (hitRect.contains(x, y)) return true
        }
        // Scrolling the lyrics/info card must not collapse the player
        if (lyricsVisible && binding.lyricsOverlay.getGlobalVisibleRect(hitRect) &&
            hitRect.contains(x, y)
        ) return true
        return false
    }

    override fun finish() {
        super.finish()
        if (skipCloseAnim) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } else {
            PanelTransitions.applyClose(this)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        PlaybackCenter.addListener(playbackListener)
        bind()
        handler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
        Themes.recreateIfNeeded(this)
    }

    override fun onStop() {
        super.onStop()
        PlaybackCenter.removeListener(playbackListener)
        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        backdropRequest?.dispose()
        backdropRequest = null
        velocity?.recycle()
        velocity = null
        handler.removeCallbacksAndMessages(null)
        if (::binding.isInitialized) binding.art.animate().cancel()
        super.onDestroy()
    }

    private fun bind() {
        val entry = PlaybackCenter.currentEntry ?: run { finish(); return }
        binding.title.text = entry.title
        binding.title.isSelected = true
        binding.subtitle.text = entry.uploader
        if (entry.thumbnailUrl != boundUrl) {
            boundUrl = entry.thumbnailUrl
            Thumbs.load(binding.art, entry.thumbnailUrl)
            applyDynamicArtworkBackdrop(entry.thumbnailUrl)
        }
        if (lyricsVisible) updateLyricsContent()
        bindPlayButton()
        bindFavorite()
        bindUpNext()
        updateProgress()
        updateDownloadState()
    }

    private fun bindPlayButton() {
        val isPlaying = PlaybackCenter.isPlaying
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.playPauseButton.contentDescription = getString(
            if (isPlaying) R.string.cd_pause else R.string.cd_play
        )
        // Apple Music signature spring artwork scale: 1.0f playing, 0.80f paused
        val scale = if (isPlaying) 1f else 0.80f
        val elev = (if (isPlaying) 28f else 8f) * resources.displayMetrics.density
        binding.art.elevation = elev
        if (AppPrefs.animations) {
            binding.art.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setInterpolator(OvershootInterpolator(1.6f))
                .setDuration(340)
                .start()
            binding.lyricsOverlay.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setInterpolator(OvershootInterpolator(1.6f))
                .setDuration(340)
                .start()
        } else {
            binding.art.animate().cancel()
            binding.art.scaleX = scale
            binding.art.scaleY = scale
            binding.lyricsOverlay.animate().cancel()
            binding.lyricsOverlay.scaleX = scale
            binding.lyricsOverlay.scaleY = scale
        }
    }

    private fun bindUpNext() {
        if (isFinishing) return
        val queue = PlaybackCenter.queue
        val radio = PlaybackCenter.radioMode
        val next = queue.getOrNull(PlaybackCenter.queueIndex + 1)

        binding.playingLabel.setText(
            if (radio) R.string.playing_from_radio else R.string.now_playing
        )
        binding.queueButton.alpha = if (queue.size > 1 || radio) 1f else 0.5f

        if (next != null) {
            binding.upNextCard.isVisible = true
            binding.upNextLabel.setText(
                if (radio) R.string.up_next_radio_label else R.string.up_next_label
            )
            binding.upNextTitle.text = "${next.title} · ${next.uploader}"
        } else {
            binding.upNextCard.isVisible = radio
            binding.upNextLabel.setText(R.string.up_next_radio_label)
            binding.upNextTitle.setText(R.string.queue_loading)
        }
    }

    private fun showQueue() {
        QueueSheet(this).show()
    }

    private fun bindFavorite() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.favoriteButton.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.favoriteButton.setColorFilter(
            if (isFav) getColor(R.color.music_green) else Color.WHITE
        )
    }

    private fun updateProgress() {
        if (userSeeking || isFinishing) return
        val duration = PlaybackCenter.durationMs.coerceAtLeast(0L)
        val position = PlaybackCenter.positionMs.coerceAtLeast(0L)
        binding.seekBar.max = if (duration > 0) duration.toInt() else 0
        binding.seekBar.setProgress(position.toInt(), PlaybackCenter.isPlaying)
        binding.position.text = formatTime(position)
        val remaining = (duration - position).coerceAtLeast(0L)
        binding.duration.text = "-${formatTime(remaining)}"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun restartRadio() {
        val entry = PlaybackCenter.currentEntry ?: return
        PlaybackCenter.playMusic(entry, radio = true)
        Toast.makeText(this, R.string.radio_started, Toast.LENGTH_SHORT).show()
        showQueue()
    }

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (DownloadCenter.isDownloaded(this, entry.url)) {
            Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadSheet()
    }

    private fun updateDownloadState() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (isFinishing) return
        if (DownloadCenter.hasActiveDownload(this, entry.url)) {
            DownloadCenter.refreshStatuses(this)
        }
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_check)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                binding.downloadStatus.isVisible = false
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(Color.WHITE)
                val percent = DownloadCenter.downloadProgress(this, entry.url)
                binding.downloadStatus.text = getString(R.string.downloading_fmt, percent)
                binding.downloadStatus.isVisible = true
            }
            else -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(Color.WHITE)
                binding.downloadStatus.isVisible = false
            }
        }
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadSheet(this, entry, catalog, audioOnly = true).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 4712
        private const val SWIPE_MIN_DISTANCE_PX = 260f

        fun start(context: Context) {
            context.startActivity(Intent(context, NowPlayingActivity::class.java))
        }
    }
}
