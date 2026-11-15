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
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.LyricLine
import com.sparktube.app.data.LyricsResult
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityNowPlayingBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.ui.auth.YouTubeLoginActivity
import com.sparktube.app.ui.player.DownloadSheet
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.PanelTransitions
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/**
 * Apple Music Full-Screen Now Playing Sheet with:
 * - Upper-left [Audio] and [Video] mini switcher buttons
 * - Live Beat-Reactive Audio Spectrum Visualizer (`AudioVisualizerView`)
 * - Interactive Tap-to-Seek Synced Lyrics (tap any line to jump to that timestamp) + Offline `.lrc` Cache
 * - Luminous Progress Scrubber with breathing glow & drag scale effect
 * - Real-time Offline Download Progress Bar (`downloadProgressCard` & `downloadProgressBar`) with Tap-to-Pause/Resume/Retry
 * - Smooth Audio Crossfade (`Off / 2s / 4s / 6s / 8s`), Sleep Timer & Hardware Audio Equalizer / Bass Boost sheet
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var volumeDragging = false
    private var lyricsVisible = false
    private var boundArtKey: String? = null
    private var backdropRequest: Disposable? = null
    private var lastSlowTick = 0L
    private var skipCloseAnim = false

    // Real-Time Synced Lyrics state
    private var lyricsJob: Job? = null
    private var lyricsForUrl: String? = null
    private var currentLyrics: LyricsResult? = null
    private var lastHighlightedLyricIdx = -2
    private var downloadResolveJob: Job? = null

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) = bind()
        override fun onPlaybackStateChanged(isPlaying: Boolean) = bindPlayButton()
        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving
        }
        override fun onCatalogReady() {
            updateDownloadState()
        }
        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = bindFavorite()
        override fun onQueueChanged() = bindUpNext()
        override fun onError(message: String) {
            binding.loading.isVisible = false
            Toast.makeText(this@NowPlayingActivity, message, Toast.LENGTH_LONG).show()
        }
        override fun onNotice(message: String) {
            Toast.makeText(this@NowPlayingActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateProgress()
            syncVolumeBar()
            val entry = PlaybackCenter.currentEntry
            val activeDl = entry != null && DownloadCenter.hasActiveDownload(this@NowPlayingActivity, entry.url)
            val now = SystemClock.uptimeMillis()
            if (activeDl || now - lastSlowTick >= 1000L) {
                lastSlowTick = now
                updateDownloadState()
                updateCenterStatusBadge()
            }
            val delayMs = when {
                activeDl -> 250L
                PlaybackCenter.isPlaying -> 200L
                else -> 800L
            }
            handler.postDelayed(this, delayMs)
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
        installArtGestures()
        binding.shuffleButton.setOnClickListener { view ->
            bounceButton(view)
            val on = PlaybackCenter.toggleShuffle()
            updateRepeatShuffleButtons()
            Toast.makeText(this, if (on) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show()
        }
        binding.repeatButton.setOnClickListener { view ->
            bounceButton(view)
            val mode = PlaybackCenter.cycleRepeatMode()
            updateRepeatShuffleButtons()
            Toast.makeText(
                this,
                when (mode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" },
                Toast.LENGTH_SHORT
            ).show()
        }
        PlaybackCenter.applyRepeatShuffle()
        updateRepeatShuffleButtons()

        binding.lyricsText.movementMethod = LinkMovementMethod.getInstance()
        binding.lyricsText.highlightColor = Color.TRANSPARENT

        // Upper-left dual mini buttons: [Audio] and [Video]
        binding.miniMusicBtn.setOnClickListener { view ->
            bounceButton(view)
            if (PlaybackCenter.mode != PlaybackCenter.Mode.AUDIO && !PlaybackCenter.audioOnlyMode) {
                PlaybackCenter.switchToAudioPlayerMode()
            }
            Toast.makeText(this, R.string.switched_to_audio_player, Toast.LENGTH_SHORT).show()
        }

        binding.miniVideoBtn.setOnClickListener { view ->
            bounceButton(view)
            if (PlaybackCenter.hasMedia) {
                PlaybackCenter.switchToVideoPlayerMode()
                Toast.makeText(this, R.string.switched_to_video_player, Toast.LENGTH_SHORT).show()
                PlayerActivity.startResume(this)
                finish()
            }
        }

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
        binding.sleepTimerButton.setOnClickListener { view ->
            bounceButton(view)
            showSleepTimerAndEqSheet()
        }
        binding.scrubberEffectBadge.setOnClickListener { view ->
            bounceButton(view)
            showSleepTimerAndEqSheet()
        }
        binding.downloadButton.setOnClickListener { view ->
            bounceButton(view)
            onDownloadClicked()
        }
        binding.downloadProgressCard.setOnClickListener { view ->
            bounceButton(view)
            val entry = PlaybackCenter.currentEntry ?: return@setOnClickListener
            DownloadCenter.togglePauseResumeOrRetry(this, entry.url)
            updateDownloadState()
        }
        binding.queueButton.setOnClickListener { view ->
            bounceButton(view)
            showQueue()
        }
        binding.upNextCard.setOnClickListener { showQueue() }

        val density = resources.displayMetrics.density
        binding.lyricsOverlay.apply {
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(0xD9121218.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = 40f * density
        }

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
                seekBar?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                seekBar?.animate()?.scaleY(1.85f)?.setDuration(160)?.start()
                binding.scrubberEffectBadge.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(160)?.start()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                seekBar?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                seekBar?.animate()?.scaleY(1f)?.setDuration(180)?.start()
                binding.scrubberEffectBadge.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                seekBar?.let { PlaybackCenter.seekTo(it.progress.toLong()) }
            }
        })

        setupVolumeSlider()
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

    /**
     * Album art gestures: swipe left/right = next/previous track (art slides out and back in),
     * double-tap left/right half = seek -10s / +10s. Disabled while lyrics are open so lyrics can scroll.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installArtGestures() {
        val density = resources.displayMetrics.density
        val detector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (lyricsVisible) return false
                    val forward = e.x >= binding.artContainer.width / 2f
                    val dur = PlaybackCenter.durationMs
                    val pos = PlaybackCenter.positionMs
                    val target = if (forward) pos + 10_000L else (pos - 10_000L).coerceAtLeast(0L)
                    PlaybackCenter.seekTo(if (dur > 0L) target.coerceAtMost(dur) else target)
                    binding.art.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val nudge = (if (forward) 14 else -14) * density
                    binding.art.animate().translationX(nudge).setDuration(90).withEndAction {
                        binding.art.animate().translationX(0f)
                            .setInterpolator(OvershootInterpolator(2.4f)).setDuration(220).start()
                    }.start()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
                ): Boolean {
                    if (lyricsVisible || e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) > 72 * density &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                        kotlin.math.abs(velocityX) > 700f
                    ) {
                        swipeTrack(next = dx < 0)
                        return true
                    }
                    return false
                }
            }
        )
        binding.artContainer.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun updateRepeatShuffleButtons() {
        val green = getColor(R.color.music_green)
        val dim = Color.argb(140, 255, 255, 255)
        binding.shuffleButton.setColorFilter(if (AppPrefs.musicShuffle) green else dim)
        val mode = AppPrefs.musicRepeatMode
        binding.repeatButton.setImageResource(
            if (mode == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        binding.repeatButton.setColorFilter(if (mode != 0) green else dim)
    }

    private fun swipeTrack(next: Boolean) {
        val art = binding.art
        art.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val dist = 80 * resources.displayMetrics.density
        val dir = if (next) -1f else 1f
        art.animate().cancel()
        art.animate().translationX(dir * dist).alpha(0.3f).setDuration(120).withEndAction {
            if (next) PlaybackCenter.next() else PlaybackCenter.previous()
            art.translationX = -dir * dist
            art.animate().translationX(0f).alpha(1f)
                .setInterpolator(DecelerateInterpolator(1.6f)).setDuration(240).start()
        }.start()
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
        // A failed/empty earlier lookup must not be permanent: retry when the user opens lyrics.
        if (lyricsVisible && currentLyrics == null) {
            lyricsForUrl = null
            PlaybackCenter.currentEntry?.let { fetchLyricsIfNeeded(it) }
        }
        binding.lyricsOverlay.isVisible = lyricsVisible
        binding.art.alpha = if (lyricsVisible) 0.16f else 1f
        binding.lyricsButton.alpha = if (lyricsVisible) 1f else 0.8f
        binding.lyricsButton.setColorFilter(
            if (lyricsVisible) getColor(R.color.music_green) else Color.WHITE
        )
        lastHighlightedLyricIdx = -2
        renderLyricsAtPosition(PlaybackCenter.positionMs)
    }

    private fun offlineLrcFile(url: String): File {
        val dir = File(DownloadCenter.resolveDownloadDir(this, DownloadCenter.TYPE_AUDIO), "lyrics").apply { mkdirs() }
        return File(dir, "${DownloadCenter.videoIdOf(url)}.lrc")
    }

    private fun saveOfflineLrcIfNeeded(url: String, res: LyricsResult) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val f = offlineLrcFile(url)
                if (res.syncedLines.isNotEmpty()) {
                    val content = res.syncedLines.joinToString("\n") { line ->
                        val min = line.timeMs / 60_000L
                        val sec = (line.timeMs % 60_000L) / 1000L
                        val cs = (line.timeMs % 1000L) / 10L
                        String.format(Locale.US, "[%02d:%02d.%02d]%s", min, sec, cs, line.text)
                    }
                    f.writeText(content)
                } else if (res.plainLyrics.isNotBlank()) {
                    f.writeText(res.plainLyrics)
                }
            }
        }
    }

    private suspend fun loadOfflineLrc(url: String): LyricsResult? = withContext(Dispatchers.IO) {
        runCatching {
            val f = offlineLrcFile(url)
            if (!f.exists()) return@withContext null
            val raw = f.readText()
            if (raw.isBlank()) return@withContext null
            val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")
            val lines = mutableListOf<LyricLine>()
            raw.lineSequence().forEach { line ->
                val m = regex.find(line.trim()) ?: return@forEach
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    1 -> (frac.toLongOrNull() ?: 0L) * 100L
                    2 -> (frac.toLongOrNull() ?: 0L) * 10L
                    3 -> frac.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val txt = m.groupValues[4].trim()
                if (txt.isNotBlank()) lines.add(LyricLine(min * 60_000L + sec * 1000L + ms, txt))
            }
            LyricsResult(lines, if (lines.isEmpty()) raw else "")
        }.getOrNull()
    }

    private fun fetchLyricsIfNeeded(entry: QueueEntry) {
        if (lyricsForUrl == entry.url) return
        lyricsForUrl = entry.url
        currentLyrics = null
        lastHighlightedLyricIdx = -2
        lyricsJob?.cancel()
        lyricsJob = lifecycleScope.launch {
            val cached = loadOfflineLrc(entry.url)
            val res = cached ?: YtRepository.fetchSyncedLyrics(entry.title, entry.uploader, entry.durationSec)?.also {
                saveOfflineLrcIfNeeded(entry.url, it)
            }
            if (lyricsForUrl != entry.url) return@launch
            currentLyrics = res
            lastHighlightedLyricIdx = -2
            updateCenterStatusBadge()
            if (lyricsVisible) {
                renderLyricsAtPosition(PlaybackCenter.positionMs)
            }
        }
    }

    private fun renderLyricsAtPosition(positionMs: Long) {
        if (!lyricsVisible) return
        val entry = PlaybackCenter.currentEntry ?: return
        val lrc = currentLyrics
        val synced = lrc?.syncedLines.orEmpty()

        if (synced.isNotEmpty()) {
            val activeIdx = synced.indexOfLast { it.timeMs <= positionMs + 250L }.coerceAtLeast(0)
            if (activeIdx == lastHighlightedLyricIdx) return
            lastHighlightedLyricIdx = activeIdx

            val sb = SpannableStringBuilder()
            val headerStart = sb.length
            sb.append("♪ SYNCED LYRICS (TAP LINE TO SEEK)\n\n")
            sb.setSpan(ForegroundColorSpan(0xFFFF4D6A.toInt()), headerStart, sb.length, 0)
            sb.setSpan(RelativeSizeSpan(0.72f), headerStart, sb.length, 0)
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), headerStart, sb.length, 0)

            var activeCharOffset = 0
            synced.forEachIndexed { idx, line ->
                val start = sb.length
                if (idx == activeIdx) activeCharOffset = start
                sb.append(line.text).append("\n\n")
                val end = sb.length

                val targetMs = line.timeMs
                sb.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            widget.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            PlaybackCenter.seekTo(targetMs)
                            lastHighlightedLyricIdx = -2
                            renderLyricsAtPosition(targetMs)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = false
                        }
                    },
                    start,
                    end,
                    0
                )

                if (idx == activeIdx) {
                    sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, 0)
                    sb.setSpan(RelativeSizeSpan(1.22f), start, end, 0)
                    sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, 0)
                } else {
                    sb.setSpan(ForegroundColorSpan(0x66FFFFFF), start, end, 0)
                }
            }
            binding.lyricsText.text = sb
            binding.lyricsText.post {
                val layout = binding.lyricsText.layout ?: return@post
                val lineNum = layout.getLineForOffset(activeCharOffset.coerceIn(0, sb.length))
                val targetY = (layout.getLineTop(lineNum) - binding.lyricsOverlay.height / 3).coerceAtLeast(0)
                binding.lyricsOverlay.smoothScrollTo(0, targetY)
            }
            return
        }

        if (lastHighlightedLyricIdx == -1) return
        lastHighlightedLyricIdx = -1
        val plain = lrc?.plainLyrics?.takeIf { it.isNotBlank() }
            ?: PlaybackCenter.catalogForCurrentEntry()?.description?.trim().orEmpty()
        val sb = SpannableStringBuilder()
        val titleStart = sb.length
        sb.append(entry.title).append('\n')
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length, 0)
        sb.setSpan(RelativeSizeSpan(1.25f), titleStart, sb.length, 0)
        val artistStart = sb.length
        sb.append(entry.uploader).append("\n\n")
        sb.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), artistStart, sb.length, 0)
        sb.append(if (plain.isNotBlank()) plain else "Fetching synced lyrics…")
        binding.lyricsText.text = sb
    }

    private fun updateCenterStatusBadge() {
        val sleepRem = PlaybackCenter.sleepTimerRemainingMs
        val hasSynced = currentLyrics?.syncedLines?.isNotEmpty() == true
        val eqActive = !PlaybackCenter.currentEqPresetLabel.startsWith("Flat")
        val fadeSec = AppPrefs.crossfadeSeconds
        val fadeTag = if (fadeSec > 0) " · XFADE ${fadeSec}S" else ""
        val isOffline = PlaybackCenter.currentEntry?.url?.startsWith("file://") == true
        val sourceTag = if (isOffline) "OFFLINE" else "HIGH BITRATE"

        binding.sleepTimerButton.setColorFilter(
            if (sleepRem > 0L || eqActive || fadeSec > 0) getColor(R.color.music_green) else Color.WHITE
        )

        binding.scrubberEffectBadge.text = when {
            sleepRem > 0L -> "☾ ${(sleepRem / 60_000L) + 1}M · ${PlaybackCenter.currentEqPresetLabel.substringBefore(" ").uppercase(Locale.US)}$fadeTag"
            hasSynced -> "♪ SYNCED LYRICS · ${PlaybackCenter.currentEqPresetLabel.substringBefore(" ").uppercase(Locale.US)}$fadeTag"
            else -> "$sourceTag · ${PlaybackCenter.currentEqPresetLabel.substringBefore(" ").uppercase(Locale.US)}$fadeTag"
        }
    }

    private fun showSleepTimerAndEqSheet() {
        val fadeLabel = if (AppPrefs.crossfadeSeconds > 0) "${AppPrefs.crossfadeSeconds}s" else "Off"
        ActionSheet.show(
            this,
            "Sleep Timer, Crossfade & Audio Studio",
            "Preset: ${PlaybackCenter.currentEqPresetLabel} · Crossfade: $fadeLabel",
            listOf(
                ActionSheet.Item("⇄ Audio Crossfade: 4s (Smooth)", accent = AppPrefs.crossfadeSeconds == 4) {
                    AppPrefs.crossfadeSeconds = 4
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio Crossfade: 4 seconds", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("⇄ Audio Crossfade: 8s (Club Mix)", accent = AppPrefs.crossfadeSeconds == 8) {
                    AppPrefs.crossfadeSeconds = 8
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio Crossfade: 8 seconds", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("⇄ Audio Crossfade: Off") {
                    AppPrefs.crossfadeSeconds = 0
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio Crossfade: Off", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("☾ Sleep Timer: 15 Minutes") {
                    PlaybackCenter.startSleepTimer(15)
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Sleep timer set for 15 minutes", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("☾ Sleep Timer: 30 Minutes") {
                    PlaybackCenter.startSleepTimer(30)
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Sleep timer set for 30 minutes", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("☾ Sleep Timer: 60 Minutes") {
                    PlaybackCenter.startSleepTimer(60)
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Sleep timer set for 60 minutes", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("☾ Turn Off Sleep Timer") {
                    PlaybackCenter.startSleepTimer(0)
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("🎛 EQ: Flat (Default)", accent = PlaybackCenter.currentEqPresetLabel.startsWith("Flat")) {
                    PlaybackCenter.applyAudioPreset(0, "Flat")
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio EQ: Flat", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("🎛 EQ: Bass Boost", accent = PlaybackCenter.currentEqPresetLabel.startsWith("Bass")) {
                    PlaybackCenter.applyAudioPreset(1, "Bass Boost")
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio EQ: Bass Boost", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("🎛 EQ: Vocal & Acoustic", accent = PlaybackCenter.currentEqPresetLabel.startsWith("Vocal")) {
                    PlaybackCenter.applyAudioPreset(2, "Vocal Studio")
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio EQ: Vocal Studio", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("🎛 EQ: Electronic / Club", accent = PlaybackCenter.currentEqPresetLabel.startsWith("Club")) {
                    PlaybackCenter.applyAudioPreset(3, "Club Electronic")
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio EQ: Club Electronic", Toast.LENGTH_SHORT).show()
                },
                ActionSheet.Item("🎛 EQ: Treble / Clarity", accent = PlaybackCenter.currentEqPresetLabel.startsWith("Treble")) {
                    PlaybackCenter.applyAudioPreset(4, "Treble Clarity")
                    updateCenterStatusBadge()
                    Toast.makeText(this, "Audio EQ: Treble Clarity", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun showAppleMusicOptionsDialog() {
        val entry = PlaybackCenter.currentEntry ?: return
        val shareUrl = if (entry.url.startsWith("file://")) {
            DownloadCenter.recordsFor(this, entry.url).firstOrNull()?.sourceUrl?.takeIf { it.startsWith("http") }
                ?: "https://www.youtube.com/watch?v=${DownloadCenter.videoIdOf(entry.url)}"
        } else {
            entry.url
        }
        ActionSheet.show(
            this, entry.title, entry.uploader,
            listOf(
                ActionSheet.Item("Switch to Video Player") {
                    PlaybackCenter.switchToVideoPlayerMode()
                    PlayerActivity.startResume(this)
                    finish()
                },
                ActionSheet.Item("Crossfade, Sleep Timer & Equalizer") {
                    showSleepTimerAndEqSheet()
                },
                ActionSheet.Item("Start Station") { restartRadio() },
                ActionSheet.Item("Download (Audio + .lrc Lyrics)") { onDownloadClicked() },
                ActionSheet.Item("YouTube Account (${YouTubeAuth.accountSummary()})") {
                    YouTubeLoginActivity.start(this)
                },
                ActionSheet.Item("Share Song Link") {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${entry.title} — $shareUrl")
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_via)))
                }
            )
        )
    }

    private fun applyDynamicArtworkBackdrop(url: String) {
        backdropRequest?.dispose()
        val req = ImageRequest.Builder(this)
            .data(url)
            .size(48, 48)
            .allowHardware(false)
            .target { drawable ->
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return@target
                if (isFinishing || isDestroyed) return@target
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
        if (binding.topLeftModeSwitcher.getGlobalVisibleRect(hitRect)) {
            hitRect.inset(-slack, -slack)
            if (hitRect.contains(x, y)) return true
        }
        if (binding.seekBar.getGlobalVisibleRect(hitRect)) {
            hitRect.inset(-slack, -slack)
            if (hitRect.contains(x, y)) return true
        }
        if (binding.volumeSeekBar.getGlobalVisibleRect(hitRect)) {
            hitRect.inset(-slack, -slack)
            if (hitRect.contains(x, y)) return true
        }
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
        binding.audioVisualizer.setPlaying(false)
    }

    override fun onDestroy() {
        downloadResolveJob?.cancel()
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
        val artKey = "${entry.url}|${entry.thumbnailUrl}"
        if (artKey != boundArtKey) {
            boundArtKey = artKey
            Thumbs.load(binding.art, entry.thumbnailUrl)
            applyDynamicArtworkBackdrop(entry.thumbnailUrl)
        }
        fetchLyricsIfNeeded(entry)
        bindPlayButton()
        bindFavorite()
        bindUpNext()
        updateProgress()
        updateDownloadState()
        updateCenterStatusBadge()
    }

    private fun bindPlayButton() {
        val isPlaying = PlaybackCenter.isPlaying
        binding.audioVisualizer.setPlaying(isPlaying && AppPrefs.animations)
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.playPauseButton.contentDescription = getString(
            if (isPlaying) R.string.cd_pause else R.string.cd_play
        )
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
        if (PlaybackCenter.isPlaying && AppPrefs.animations) {
            val pulse = 0.88f + 0.12f * sin(SystemClock.uptimeMillis() / 280.0).toFloat()
            binding.seekBar.alpha = pulse
        } else {
            binding.seekBar.alpha = 1f
        }
        binding.position.text = formatTime(position)
        val remaining = (duration - position).coerceAtLeast(0L)
        binding.duration.text = "-${formatTime(remaining)}"
        if (lyricsVisible) {
            renderLyricsAtPosition(position)
        }
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
        val targetUrl = if (entry.url.startsWith("file://")) {
            DownloadCenter.recordsFor(this, entry.url).firstOrNull()?.sourceUrl?.takeIf { it.startsWith("http") }
                ?: DownloadCenter.videoIdOf(entry.url).takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
                ?: entry.url
        } else {
            entry.url
        }
        PlaybackCenter.playMusic(entry.copy(url = targetUrl, isMusic = true), radio = true)
        Toast.makeText(this, R.string.radio_started, Toast.LENGTH_SHORT).show()
        showQueue()
    }

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        currentLyrics?.let { saveOfflineLrcIfNeeded(entry.url, it) }
        when {
            entry.url.startsWith("file://") || DownloadCenter.isDownloaded(this, entry.url) -> {
                Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            }
            DownloadCenter.hasActiveDownload(this, entry.url) ||
                DownloadCenter.hasPausedDownload(this, entry.url) ||
                DownloadCenter.hasFailedDownload(this, entry.url) -> {
                DownloadCenter.togglePauseResumeOrRetry(this, entry.url)
                updateDownloadState()
            }
            else -> showDownloadSheet()
        }
    }

    private fun updateDownloadState() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (isFinishing) return
        if (DownloadCenter.hasActiveDownload(this, entry.url)) {
            DownloadCenter.refreshStatuses(this)
        }
        when {
            entry.url.startsWith("file://") || DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_check)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                binding.downloadProgressCard.isVisible = false
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_pause)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                val percent = DownloadCenter.downloadProgress(this, entry.url).coerceIn(0, 100)
                val bytesText = DownloadCenter.downloadBytesText(this, entry.url)
                val baseText = getString(R.string.downloading_fmt, percent)
                binding.downloadStatus.text = if (bytesText.isNotBlank()) {
                    "⏸ $baseText ($bytesText) · Tap to Pause"
                } else {
                    "⏸ $baseText · Tap to Pause"
                }
                binding.downloadProgressBar.isIndeterminate = percent == 0 && bytesText.isBlank()
                binding.downloadProgressBar.progress = percent
                binding.downloadProgressBar.alpha = 1.0f
                binding.downloadProgressCard.isVisible = true
            }
            DownloadCenter.hasPausedDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_play_arrow)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                val percent = DownloadCenter.downloadProgress(this, entry.url).coerceIn(0, 100)
                val bytesText = DownloadCenter.downloadBytesText(this, entry.url)
                val baseText = getString(R.string.paused_fmt, percent)
                binding.downloadStatus.text = if (bytesText.isNotBlank()) {
                    "▶ $baseText ($bytesText) · Tap to Resume"
                } else {
                    "▶ $baseText · Tap to Resume"
                }
                binding.downloadProgressBar.isIndeterminate = false
                binding.downloadProgressBar.progress = percent
                binding.downloadProgressBar.alpha = 0.6f
                binding.downloadProgressCard.isVisible = true
            }
            DownloadCenter.hasFailedDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_player_replay)
                binding.downloadButton.setColorFilter(Color.WHITE)
                binding.downloadStatus.text = "↻ Download failed · Tap to Retry"
                binding.downloadProgressBar.isIndeterminate = false
                binding.downloadProgressBar.progress = 0
                binding.downloadProgressCard.isVisible = true
            }
            else -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(Color.WHITE)
                binding.downloadProgressCard.isVisible = false
            }
        }
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val verifiedCatalog = PlaybackCenter.catalogForCurrentEntry()
        if (verifiedCatalog != null) {
            DownloadSheet(this, entry, verifiedCatalog, audioOnly = true) {
                updateDownloadState()
            }.show()
            return
        }
        downloadResolveJob?.cancel()
        binding.loading.isVisible = true
        downloadResolveJob = lifecycleScope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (PlaybackCenter.currentEntry?.url != entry.url) return@launch
                val freshCatalog = StreamCatalog(info, entry.url)
                DownloadSheet(this@NowPlayingActivity, entry, freshCatalog, audioOnly = true) {
                    updateDownloadState()
                }.show()
            } catch (_: Exception) {
                Toast.makeText(this@NowPlayingActivity, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            } finally {
                binding.loading.isVisible = PlaybackCenter.isResolving
            }
        }
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
