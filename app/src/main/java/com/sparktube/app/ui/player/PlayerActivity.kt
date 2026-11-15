package com.sparktube.app.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3R
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.data.CommentsPage
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.SponsorSegment
import com.sparktube.app.data.SubtitleCue
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityPlayerBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.playback.effectiveHeight
import com.sparktube.app.playback.toVideoEntry
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.ui.auth.YouTubeLoginActivity
import com.sparktube.app.ui.channel.ChannelActivity
import com.sparktube.app.ui.common.CommentAdapter
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Avatars
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.PanelTransitions
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * YouTube-style watch page with:
 * - Upper-left dual Audio/Video mini switcher buttons
 * - Screen Lock (`lockButton` & `unlockScreenButton`) blocking accidental touches, swipes, and back-presses
 * - Auto-Translated Bangla Subtitles (`subtitleBanner`, `ccButton`, `&tlang=bn` + GTX fallback)
 * - Live Offline Download Progress Bar (`downloadProgressContainer`, `downloadProgressBar`) with Tap-to-Pause/Resume/Retry
 * - Fullscreen upper-right button opening a semi-transparent scrollable 2-column Video Card Grid
 * - YouTube Ambient Cinema Glow (`ambientCinemaGlow`) sampled from video artwork
 * - Double-Tap Ripple Seek (-10s / +10s) & Long-Press 2.0x Speed Hold (`gestureSeekPill`)
 * - Automatic SponsorBlock segment skipping & ReturnYouTubeDislike counter
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var relatedAdapter: VideoAdapter
    private lateinit var fullscreenGridAdapter: FullscreenGridVideoAdapter
    private var channelSubCount: Long = -1L

    private lateinit var gestures: PlayerGestures
    private var ambientGlowRequest: Disposable? = null

    private var controllerVisible = false
    private var fullscreenGridOpen = false
    private var isScreenLocked = false

    private var descExpanded = false
    private var descBoundFor: String? = null

    private var commentsFor: String? = null
    private var commentsPage: CommentsPage? = null
    private var commentsJob: Job? = null

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var lastSkippedSegmentStart: Long = -1L
    private var sponsorJob: Job? = null
    private var dislikeJob: Job? = null

    // Bangla Auto-Translate & Original CC Subtitles state
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitleJob: Job? = null
    private var subtitlesLoadedForKey: String? = null
    private var downloadResolveJob: Job? = null
    private var awaitingLoginRetry: Boolean = false

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) {
            if (entry != null && entry.url != loadedUrl) {
                loadedUrl = entry.url
                bind(entry)
            }
        }

        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving && !binding.errorView.isVisible
        }

        override fun onCatalogReady() {
            bindCatalog()
            updateDownloadUi()
        }

        override fun onError(message: String) {
            binding.loading.isVisible = false
            binding.errorText.text = message
            binding.retryButton.isVisible = true
            binding.errorSignInButton.isVisible = !YouTubeAuth.isLoggedIn()
            binding.errorView.isVisible = true
        }

        override fun onLiveContentBlocked() {
            Toast.makeText(this@PlayerActivity, R.string.live_not_supported, Toast.LENGTH_SHORT).show()
            finish()
        }

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateFavoriteUi()

        override fun onAudioOnlyChanged(audioOnly: Boolean) {
            updateModeSwitcherUi()
            bindCatalog()
        }

        override fun onNotice(message: String) {
            Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private var loadedUrl: String? = null
    private val downloadHandler = Handler(Looper.getMainLooper())
    private val hidePillRunnable = Runnable {
        binding.gestureSeekPill.animate().alpha(0f).setDuration(160).withEndAction {
            binding.gestureSeekPill.isVisible = false
        }.start()
    }
    private val hideUnlockBtnRunnable = Runnable {
        binding.unlockScreenButton.animate().alpha(0f).setDuration(180).withEndAction {
            binding.unlockScreenButton.isVisible = false
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)

        PanelTransitions.install(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isScreenLocked) {
                    revealUnlockPillTemporarily()
                    Toast.makeText(this@PlayerActivity, R.string.screen_locked_toast, Toast.LENGTH_SHORT).show()
                } else if (fullscreenGridOpen) {
                    toggleFullscreenGrid(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.fullscreenButton.isVisible = false
        binding.lockButton.isVisible = false
        binding.ccButton.isVisible = false
        binding.scaleButton.isVisible = false
        binding.speedButton.isVisible = false
        binding.fullscreenVideosGridBtn.isVisible = false
        binding.playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    controllerVisible = !isScreenLocked && (visibility == View.VISIBLE)
                    syncFullscreenButtonVisibility()
                }
            }
        )

        // Upper-left dual mini buttons: [Audio] and [Video]
        binding.miniMusicBtn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (PlaybackCenter.hasMedia) {
                PlaybackCenter.switchToAudioPlayerMode()
                Toast.makeText(this, R.string.switched_to_audio_player, Toast.LENGTH_SHORT).show()
                NowPlayingActivity.start(this)
                finish()
            }
        }
        binding.miniVideoBtn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (PlaybackCenter.audioOnlyMode || PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
                PlaybackCenter.switchToVideoPlayerMode()
                PlaybackCenter.attachView(binding.playerView)
                updateModeSwitcherUi()
                Toast.makeText(this, R.string.switched_to_video_player, Toast.LENGTH_SHORT).show()
            }
        }
        updateModeSwitcherUi()

        // Screen Lock & Unlock handlers
        binding.lockButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            setScreenLocked(true)
        }
        binding.unlockScreenButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setScreenLocked(false)
        }

        // Subtitles / CC button (Bangla Auto-Translate)
        binding.ccButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSubtitleModeSheet()
        }
        binding.actionSubtitles.setOnClickListener {
            showSubtitleModeSheet()
        }
        updateSubtitleUiBadge()

        binding.actionRow.isVisible = true
        binding.subscribeButton.isVisible = false

        binding.description.setLinkTextColor(Themes.accentColor(this))
        binding.descCard.setOnClickListener { toggleDescription() }
        binding.descToggle.setOnClickListener { toggleDescription() }
        binding.commentsCard.setOnClickListener { openComments() }

        relatedAdapter = VideoAdapter(
            onClick = { model ->
                val entry = model.toQueueEntry()
                PlaybackCenter.playVideo(entry)
            },
            onAudioClick = { model ->
                val entry = model.toQueueEntry(isMusic = true)
                PlaybackCenter.playMusic(entry, radio = true)
                NowPlayingActivity.start(this)
                finish()
            }
        )
        binding.relatedList.layoutManager = LinearLayoutManager(this)
        binding.relatedList.adapter = relatedAdapter

        fullscreenGridAdapter = FullscreenGridVideoAdapter { model ->
            toggleFullscreenGrid(false)
            PlaybackCenter.playVideo(model.toQueueEntry())
        }
        binding.fullscreenGridRecycler.layoutManager = GridLayoutManager(this, 2)
        binding.fullscreenGridRecycler.adapter = fullscreenGridAdapter

        binding.fullscreenVideosGridBtn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleFullscreenGrid(!fullscreenGridOpen)
        }
        binding.closeFullscreenGridBtn.setOnClickListener {
            toggleFullscreenGrid(false)
        }

        binding.retryButton.setOnClickListener { retry() }
        binding.errorSignInButton.setOnClickListener {
            awaitingLoginRetry = true
            YouTubeLoginActivity.start(this)
        }

        binding.actionFavorite.setOnClickListener { toggleFavorite() }
        binding.actionPlaylist.setOnClickListener { showPlaylistSheet() }
        binding.actionShare.setOnClickListener { shareVideo() }
        binding.actionDownload.setOnClickListener { onDownloadClicked() }
        binding.downloadProgressContainer.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val entry = PlaybackCenter.currentEntry ?: return@setOnClickListener
            DownloadCenter.togglePauseResumeOrRetry(this, entry.url)
            updateDownloadUi()
        }
        binding.actionBackground.setOnClickListener {
            PlaybackCenter.switchToAudioPlayerMode()
            Toast.makeText(this, R.string.switched_to_audio_player, Toast.LENGTH_SHORT).show()
            NowPlayingActivity.start(this)
            finish()
        }
        binding.actionPip.setOnClickListener { enterPipOrToast() }

        binding.channelInfo.setOnClickListener { openChannel() }
        binding.subscribeButton.setOnClickListener { toggleSubscribe() }

        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }

        binding.scaleButton.setOnClickListener { cycleScaleMode() }
        binding.speedButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSpeedSheet()
        }
        applyScaleMode()

        gestures = PlayerGestures(
            activity = this,
            hud = binding.gestureHud,
            hudIcon = binding.hudIcon,
            hudBar = binding.hudBar,
            hudText = binding.hudText,
            isLandscape = {
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            },
            isLocked = { isScreenLocked },
            onLockedTap = { revealUnlockPillTemporarily() },
            onMinimize = { finish() },
            onDoubleTapSeek = { forward, totalSec ->
                showGestureSeekPill(if (forward) "⏩ +${totalSec}s" else "⏪ -${totalSec}s", autoHide = true)
            },
            onScrub = { text ->
                if (text != null) showGestureSeekPill(text, autoHide = false)
                else downloadHandler.post(hidePillRunnable)
            },
            onSpeedHoldChanged = { holding2x ->
                if (holding2x) {
                    showGestureSeekPill("⏩ 2.0x Speed", autoHide = false)
                } else {
                    downloadHandler.post(hidePillRunnable)
                }
            }
        )
        binding.playerView.setOnTouchListener { v, event ->
            if (isScreenLocked) {
                gestures.onTouch(v, event)
            } else if (fullscreenGridOpen) {
                toggleFullscreenGrid(false)
                true
            } else {
                gestures.onTouch(v, event)
            }
        }
        binding.playerView.controllerShowTimeoutMs = 3500

        hookGearMenu()

        applyOrientation(resources.configuration)

        if (intent.getBooleanExtra(EXTRA_ATTACH, false) && PlaybackCenter.hasMedia) {
            if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO || PlaybackCenter.audioOnlyMode) {
                PlaybackCenter.switchToVideoPlayerMode()
            } else {
                PlaybackCenter.switchBackToVideo()
            }
            loadedUrl = PlaybackCenter.currentEntry?.url
            PlaybackCenter.currentEntry?.let(::bind)
            bindCatalog()
            updateModeSwitcherUi()
        } else {
            val entry = entryFromIntent()
            if (entry == null) {
                finish()
                return
            }
            if (PlaybackCenter.currentEntry?.url == entry.url && PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO) {
                PlaybackCenter.switchBackToVideo()
                loadedUrl = entry.url
                bind(entry)
                bindCatalog()
            } else if (PlaybackCenter.currentEntry?.url == entry.url && PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
                PlaybackCenter.switchToVideoPlayerMode()
                loadedUrl = entry.url
                bind(entry)
                bindCatalog()
            } else {
                PlaybackCenter.playVideo(entry)
            }
            updateModeSwitcherUi()
        }
    }

    private fun setScreenLocked(locked: Boolean) {
        isScreenLocked = locked
        if (locked) {
            toggleFullscreenGrid(false)
            binding.playerView.hideController()
            binding.playerView.useController = false
            controllerVisible = false
            syncFullscreenButtonVisibility()
            revealUnlockPillTemporarily()
            Toast.makeText(this, R.string.screen_locked_toast, Toast.LENGTH_SHORT).show()
        } else {
            downloadHandler.removeCallbacks(hideUnlockBtnRunnable)
            binding.unlockScreenButton.isVisible = false
            binding.playerView.useController = true
            binding.playerView.showController()
            controllerVisible = true
            syncFullscreenButtonVisibility()
            Toast.makeText(this, R.string.screen_unlocked_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun revealUnlockPillTemporarily() {
        downloadHandler.removeCallbacks(hideUnlockBtnRunnable)
        binding.unlockScreenButton.alpha = 1f
        binding.unlockScreenButton.isVisible = true
        binding.unlockScreenButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        downloadHandler.postDelayed(hideUnlockBtnRunnable, 3000L)
    }

    private fun showGestureSeekPill(text: String, autoHide: Boolean) {
        downloadHandler.removeCallbacks(hidePillRunnable)
        binding.gestureSeekPill.text = text
        binding.gestureSeekPill.alpha = 1f
        binding.gestureSeekPill.isVisible = true
        if (autoHide) {
            downloadHandler.postDelayed(hidePillRunnable, 750L)
        }
    }

    private fun applyAmbientCinemaGlow(thumbUrl: String) {
        ambientGlowRequest?.dispose()
        if (thumbUrl.isBlank()) return
        val req = ImageRequest.Builder(this)
            .data(thumbUrl)
            .size(48, 48)
            .allowHardware(false)
            .target { drawable ->
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return@target
                if (isFinishing || isDestroyed) return@target
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                val step = maxOf(1, bmp.width / 8)
                var y = 0
                while (y < bmp.height) {
                    var x = 0
                    while (x < bmp.width) {
                        val c = bmp.getPixel(x, y)
                        r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                        x += step
                    }
                    y += step
                }
                val avg = if (n > 0) Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()) else 0xFFFF0033.toInt()
                val glowTop = ColorUtils.setAlphaComponent(avg, 145)
                val glowMid = ColorUtils.setAlphaComponent(avg, 65)
                binding.ambientCinemaGlow.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(glowTop, glowMid, Color.TRANSPARENT)
                )
            }
            .build()
        ambientGlowRequest = imageLoader.enqueue(req)
    }

    private fun toggleFullscreenGrid(open: Boolean) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fullscreenGridOpen = open && landscape && !isScreenLocked
        binding.fullscreenGridOverlay.isVisible = fullscreenGridOpen
        if (fullscreenGridOpen) {
            binding.playerView.hideController()
        }
    }

    private fun updateModeSwitcherUi() {
        val isAudio = PlaybackCenter.audioOnlyMode || PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO
        binding.miniMusicBtn.setBackgroundResource(
            if (isAudio) R.drawable.bg_mode_switch_active else R.drawable.bg_mode_switch_inactive
        )
        binding.miniVideoBtn.setBackgroundResource(
            if (!isAudio) R.drawable.bg_mode_switch_active else R.drawable.bg_mode_switch_inactive
        )
        binding.miniMusicIcon.setColorFilter(if (isAudio) Color.WHITE else 0xCCFFFFFF.toInt())
        binding.miniVideoIcon.setColorFilter(if (!isAudio) Color.WHITE else 0xCCFFFFFF.toInt())
    }

    private fun hookGearMenu() {
        val gear = binding.playerView.findViewById<View>(Media3R.id.exo_settings)
        gear?.setOnClickListener { showVideoSettingsSheet() }
    }

    private fun syncFullscreenButtonVisibility() {
        val visible = !isInPictureInPictureMode && controllerVisible && !isScreenLocked
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.fullscreenButton.isVisible = visible
        binding.lockButton.isVisible = false
        binding.ccButton.isVisible = false
        binding.scaleButton.isVisible = false
        binding.speedButton.isVisible = false
        binding.fullscreenVideosGridBtn.isVisible = visible && landscape
        binding.topLeftModeSwitcher.isVisible = !isInPictureInPictureMode && !isScreenLocked && (!landscape || controllerVisible)
    }

    private var lastTopInset = 0

    private fun applyPlayerTopInset() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topMargin = if (landscape || isInPictureInPictureMode) 0 else lastTopInset
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val rtl = root.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val startInset = if (rtl) bars.right else bars.left
            val endInset = if (rtl) bars.left else bars.right

            binding.topLeftModeSwitcher.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(8)
                marginStart = dp(10) + startInset
            }
            binding.backButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginStart = dp(4) + startInset
            }
            binding.fullscreenButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginEnd = endInset
            }
            binding.lockButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginEnd = endInset
            }
            binding.ccButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginEnd = endInset
            }
            binding.scaleButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginEnd = endInset
            }
            binding.fullscreenVideosGridBtn.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(4)
                marginEnd = endInset
            }
            lastTopInset = bars.top
            applyPlayerTopInset()
            binding.scrollArea.setPadding(
                binding.scrollArea.paddingLeft,
                binding.scrollArea.paddingTop,
                binding.scrollArea.paddingRight,
                bars.bottom + dp(24)
            )
            insets
        }
    }

    private fun applyImmersive(landscape: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyScaleMode() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mode = if (landscape) when (AppPrefs.fullscreenScale) {
            AppPrefs.SCALE_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AppPrefs.SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = mode
    }

    private fun cycleScaleMode() {
        val next = when (AppPrefs.fullscreenScale) {
            AppPrefs.SCALE_FIT -> AppPrefs.SCALE_CROP
            AppPrefs.SCALE_CROP -> AppPrefs.SCALE_STRETCH
            else -> AppPrefs.SCALE_FIT
        }
        AppPrefs.fullscreenScale = next
        applyScaleMode()
        val label = when (next) {
            AppPrefs.SCALE_CROP -> getString(R.string.scale_crop)
            AppPrefs.SCALE_STRETCH -> getString(R.string.scale_stretch)
            else -> getString(R.string.scale_fit)
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        binding.loading.isVisible = PlaybackCenter.isResolving && !binding.errorView.isVisible
        downloadHandler.post(downloadPoll)
        if (PlaybackCenter.hasMedia) {
            PlaybackCenter.player.playWhenReady = true
        }
        updateModeSwitcherUi()
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
        applyCloseTransition()
        if (!isInPictureInPictureMode) {
            PlaybackCenter.attachView(binding.playerView)
        }
        updateModeSwitcherUi()
        updateSubtitleUiBadge()
        updateDownloadUi()
        if (awaitingLoginRetry && YouTubeAuth.isLoggedIn()) {
            awaitingLoginRetry = false
            retry()
        }
    }

    private fun applyCloseTransition() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (AppPrefs.animations) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_down_out)
        } else {
            clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && AppPrefs.animations) {
            overridePendingTransition(0, R.anim.slide_down_out)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            PlaybackCenter.detachView(binding.playerView)
        }
    }

    override fun onStop() {
        super.onStop()
        downloadHandler.removeCallbacks(downloadPoll)
        downloadHandler.removeCallbacks(hideUnlockBtnRunnable)
        PlaybackCenter.removeListener(playbackListener)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onDestroy() {
        downloadResolveJob?.cancel()
        ambientGlowRequest?.dispose()
        ambientGlowRequest = null
        subtitleJob?.cancel()
        if (::gestures.isInitialized) gestures.release()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && PlaybackCenter.hasMedia &&
            PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO && !PlaybackCenter.audioOnlyMode
        ) {
            enterPip()
        }
    }

    private fun enterPipOrToast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPip()
        } else {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlaybackCenter.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.topLeftModeSwitcher.isVisible = false
            binding.backButton.isVisible = false
            binding.fullscreenButton.isVisible = false
            binding.lockButton.isVisible = false
            binding.ccButton.isVisible = false
            binding.scaleButton.isVisible = false
            binding.speedButton.isVisible = false
            binding.fullscreenVideosGridBtn.isVisible = false
            binding.fullscreenGridOverlay.isVisible = false
            binding.unlockScreenButton.isVisible = false
            binding.ambientCinemaGlow.isVisible = false
            binding.scrollArea.isVisible = false
            binding.loading.isVisible = false
            binding.errorView.isVisible = false
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
            applyPlayerTopInset()
        } else {
            binding.ambientCinemaGlow.isVisible = true
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = 0
                dimensionRatio = "16:9"
            }
            applyPlayerTopInset()
            syncFullscreenButtonVisibility()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.fullscreenButton.setImageResource(
            if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        applyImmersive(landscape)
        binding.ambientCinemaGlow.isVisible = !landscape
        if (landscape) {
            binding.scrollArea.isVisible = false
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            toggleFullscreenGrid(false)
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = 0
                dimensionRatio = "16:9"
            }
        }
        applyScaleMode()
        applyPlayerTopInset()
        syncFullscreenButtonVisibility()
    }

    private fun entryFromIntent(): QueueEntry? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        return QueueEntry(
            url = url,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty(),
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
            durationSec = intent.getLongExtra(EXTRA_DURATION, 0L)
        )
    }

    private fun bind(entry: QueueEntry) {
        binding.errorView.isVisible = false
        binding.title.text = entry.title
        binding.views.text = ""
        binding.channelName.text = entry.uploader
        binding.channelSubs.text = ""
        binding.channelAvatar.setImageResource(0)
        binding.subtitleBanner.isVisible = false
        subtitleCues = emptyList()
        subtitlesLoadedForKey = null
        applyAmbientCinemaGlow(entry.thumbnailUrl)
        Avatars.load(binding.channelAvatar, "", entry.uploader)
        loadChannelInfo()
        loadSponsorAndDislikes(entry.url)

        updateFavoriteUi()
        updateDownloadUi()
        updateModeSwitcherUi()

        bindCatalog()
    }

    private fun loadSponsorAndDislikes(url: String) {
        sponsorSegments = emptyList()
        lastSkippedSegmentStart = -1L
        sponsorJob?.cancel()
        dislikeJob?.cancel()
        if (url.startsWith("file://")) return

        sponsorJob = lifecycleScope.launch {
            if (AppPrefs.sponsorBlock) {
                sponsorSegments = YtRepository.fetchSponsorSegments(url)
            }
        }
        dislikeJob = lifecycleScope.launch {
            val dislikes = YtRepository.fetchDislikeCount(url)
            val likes = PlaybackCenter.catalogForCurrentEntry()?.likeCount ?: -1L
            updateLikesDislikesLabel(likes, dislikes)
        }
    }

    private fun updateLikesDislikesLabel(likes: Long, dislikes: Long) {
        val likeText = if (likes >= 0) Formatters.formatViewCount(likes) else "Like"
        val dislikeText = if (dislikes >= 0) " · 👎 ${Formatters.formatViewCount(dislikes)}" else ""
        binding.actionLikesDislikesLabel.text = "$likeText$dislikeText"
    }

    private fun bindCatalog() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalogForCurrentEntry()

        binding.actionRow.isVisible = true

        if (catalog != null) {
            binding.title.text = catalog.name.ifBlank { entry.title }
            bindDescription(catalog, entry.url)
            maybeLoadComments(entry.url)
            maybeLoadSubtitles(catalog, entry.url)
            binding.channelName.text = catalog.uploaderName.ifBlank { entry.uploader }
            if (catalog.uploaderAvatarUrl.isNotBlank()) {
                Thumbs.load(binding.channelAvatar, catalog.uploaderAvatarUrl)
            }
            val relatedModels = catalog.related.map { it.toUiModel() }
            relatedAdapter.submitList(relatedModels)
            fullscreenGridAdapter.submitList(relatedModels)
            if (catalog.likeCount >= 0 && !binding.actionLikesDislikesLabel.text.contains("👎")) {
                updateLikesDislikesLabel(catalog.likeCount, -1L)
            }
        } else {
            binding.descCard.isVisible = false
            binding.subtitleBanner.isVisible = false
            descBoundFor = null
            resetComments()
        }
        updateSubscribeUi()
    }

    private fun maybeLoadSubtitles(catalog: StreamCatalog, url: String, forceReload: Boolean = false) {
        updateSubtitleUiBadge()
        val mode = AppPrefs.subtitleMode
        if (mode == AppPrefs.SUBTITLE_MODE_OFF || catalog.subtitles.isEmpty()) {
            subtitleJob?.cancel()
            subtitleCues = emptyList()
            binding.subtitleBanner.isVisible = false
            return
        }
        val key = "$url|$mode"
        if (!forceReload && subtitlesLoadedForKey == key && subtitleCues.isNotEmpty()) return
        subtitlesLoadedForKey = key
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val cues = YtRepository.fetchSubtitles(catalog.subtitles, mode)
            if (subtitlesLoadedForKey != key) return@launch
            subtitleCues = cues
            updateActiveSubtitleCue()
        }
    }

    private fun updateActiveSubtitleCue() {
        if (AppPrefs.subtitleMode == AppPrefs.SUBTITLE_MODE_OFF || subtitleCues.isEmpty()) {
            binding.subtitleBanner.isVisible = false
            return
        }
        val pos = PlaybackCenter.positionMs
        val cue = subtitleCues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
        if (cue != null && cue.text.isNotBlank()) {
            binding.subtitleBanner.text = cue.text
            binding.subtitleBanner.isVisible = true
        } else {
            binding.subtitleBanner.isVisible = false
        }
    }

    private fun updateSubtitleUiBadge() {
        val mode = AppPrefs.subtitleMode
        val active = mode != AppPrefs.SUBTITLE_MODE_OFF
        val accent = Themes.accentColor(this)
        binding.ccButton.setColorFilter(if (active) accent else Color.WHITE)
        binding.actionSubtitlesIcon.setColorFilter(if (active) accent else getColor(R.color.on_surface))
        binding.actionSubtitlesLabel.text = subtitleModeSummary()
    }

    private fun subtitleModeSummary(): String = when (AppPrefs.subtitleMode) {
        AppPrefs.SUBTITLE_MODE_BANGLA -> getString(R.string.subtitles_bangla_auto)
        AppPrefs.SUBTITLE_MODE_ORIGINAL -> getString(R.string.subtitles_original)
        else -> getString(R.string.subtitles_off)
    }

    private fun showSubtitleModeSheet() {
        val catalog = PlaybackCenter.catalogForCurrentEntry()
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.subtitles_section)))

        val hasCaptions = catalog?.subtitles?.isNotEmpty() == true
        if (!hasCaptions) {
            root.addView(hintRow(getString(R.string.subtitles_unavailable)))
        }

        val current = AppPrefs.subtitleMode
        root.addView(
            chipGrid(
                listOf(
                    AppPrefs.SUBTITLE_MODE_BANGLA to getString(R.string.subtitles_bangla_auto),
                    AppPrefs.SUBTITLE_MODE_ORIGINAL to getString(R.string.subtitles_original),
                    AppPrefs.SUBTITLE_MODE_OFF to getString(R.string.subtitles_off)
                ).map { (mode, label) ->
                    Triple(label, current == mode) {
                        AppPrefs.subtitleMode = mode
                        updateSubtitleUiBadge()
                        val entry = PlaybackCenter.currentEntry
                        if (catalog != null && entry != null) {
                            maybeLoadSubtitles(catalog, entry.url, forceReload = true)
                        }
                        Toast.makeText(this, "${getString(R.string.subtitles_section)}: $label", Toast.LENGTH_SHORT).show()
                        sheet.dismiss()
                    }
                },
                perRow = 3
            )
        )
        showSheet(sheet, root, peekDp = 320)
    }

    private fun bestQualityLabel(catalog: StreamCatalog): String {
        val best = (catalog.videoOnly + catalog.muxed)
            .maxByOrNull { it.effectiveHeight() * 100 + it.fps }
            ?: return getString(R.string.quality_auto_short)
        val h = best.effectiveHeight()
        return when {
            h >= 2160 -> "4K"
            h > 0 && best.fps > 30 -> "${h}p${best.fps}"
            h > 0 -> "${h}p"
            else -> getString(R.string.quality_auto_short)
        }
    }

    private fun bindDescription(catalog: StreamCatalog, url: String) {
        val meta = listOfNotNull(
            catalog.viewCount.takeIf { it >= 0 }
                ?.let { getString(R.string.views_fmt, Formatters.formatViewCount(it)) },
            catalog.uploadDateText.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        binding.views.text = meta
        binding.qualityBadgeInfo.text = bestQualityLabel(catalog)
        binding.descCard.isVisible = true
        com.sparktube.app.ui.music.Motion.pressable(binding.descCard, 0.98f)

        if (descBoundFor == url) return
        descBoundFor = url
        descExpanded = false
        applyDescriptionState(animate = false)

        val text = CommentAdapter.bodyToText(catalog.description, catalog.descriptionIsHtml)
        val hasDescription = text.isNotBlank() || meta.isNotBlank()
        binding.description.isVisible = text.isNotBlank()
        binding.descCard.isClickable = hasDescription
        binding.descChevron.isVisible = hasDescription
        if (text.isNotBlank()) {
            binding.description.text = text
        }
    }

    private fun toggleDescription() {
        if (!binding.descCard.isClickable) return
        descExpanded = !descExpanded
        applyDescriptionState(animate = true)
    }

    private fun applyDescriptionState(animate: Boolean) {
        val animated = animate && AppPrefs.animations
        if (animated) {
            android.transition.TransitionManager.beginDelayedTransition(
                binding.scrollContent,
                android.transition.ChangeBounds().apply { duration = 200 }
            )
        }
        binding.descBody.isVisible = descExpanded
        binding.description.maxLines = Int.MAX_VALUE
        binding.description.movementMethod =
            if (descExpanded) LinkMovementMethod.getInstance() else null
        val target = if (descExpanded) 0f else 180f
        if (animated) {
            binding.descChevron.animate().rotation(target).setDuration(200).start()
        } else {
            binding.descChevron.animate().cancel()
            binding.descChevron.rotation = target
        }
    }

    private fun resetComments() {
        commentsJob?.cancel()
        commentsJob = null
        commentsFor = null
        commentsPage = null
        binding.commentsCard.isVisible = false
    }

    private fun maybeLoadComments(url: String) {
        if (url.startsWith("file://")) {
            binding.commentsCard.isVisible = false
            return
        }
        binding.commentsCard.isVisible = true
        if (commentsFor == url) return
        commentsFor = url
        commentsPage = null
        binding.commentsCount.text = ""
        binding.commentsPreviewRow.isVisible = false
        binding.commentsStatus.setText(R.string.comments_loading)
        binding.commentsStatus.isVisible = true
        commentsJob?.cancel()
        commentsJob = lifecycleScope.launch {
            try {
                val page = YtRepository.comments(url)
                if (commentsFor != url) return@launch
                commentsPage = page
                bindCommentsPreview(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (commentsFor != url) return@launch
                binding.commentsStatus.setText(R.string.comments_error)
                binding.commentsStatus.isVisible = true
            }
        }
    }

    private fun bindCommentsPreview(page: CommentsPage) {
        binding.commentsCount.text =
            if (page.total > 0) Formatters.formatViewCount(page.total.toLong()) else ""
        when {
            page.disabled -> {
                binding.commentsPreviewRow.isVisible = false
                binding.commentsStatus.setText(R.string.comments_disabled)
                binding.commentsStatus.isVisible = true
            }
            page.items.isEmpty() -> {
                binding.commentsPreviewRow.isVisible = false
                binding.commentsStatus.setText(R.string.comments_empty)
                binding.commentsStatus.isVisible = true
            }
            else -> {
                val top = page.items.first()
                Avatars.load(binding.commentsPreviewAvatar, top.avatarUrl, top.author)
                binding.commentsPreviewText.text = CommentAdapter.commentText(top).toString()
                binding.commentsPreviewRow.isVisible = true
                binding.commentsStatus.isVisible = false
            }
        }
    }

    private fun openComments() {
        val url = PlaybackCenter.currentEntry?.url ?: return
        if (url.startsWith("file://")) return
        CommentsSheet(
            context = this,
            scope = lifecycleScope,
            videoUrl = url,
            initial = commentsPage,
            onLoaded = { loaded ->
                if (commentsFor == url) {
                    commentsPage = loaded
                    bindCommentsPreview(loaded)
                }
            }
        ).show()
    }

    private fun loadChannelInfo() {
        val uploaderUrl = PlaybackCenter.catalogForCurrentEntry()?.uploaderUrl ?: return
        lifecycleScope.launch {
            try {
                val channel = YtRepository.channelInfo(uploaderUrl)
                channelSubCount = channel.subscriberCount
                binding.channelSubs.text =
                    getString(R.string.subscribers_fmt, Formatters.formatViewCount(channel.subscriberCount))
                updateSubscribeUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun updateFavoriteUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.actionFavoriteIcon.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.actionFavoriteIcon.setColorFilter(
            if (isFav) Themes.accentColor(this) else getColor(R.color.on_surface)
        )
        binding.actionFavoriteLabel.text =
            getString(if (isFav) R.string.saved_short else R.string.action_favorite)
    }

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                confirmDeleteDownload()
            }
            DownloadCenter.hasActiveDownload(this, entry.url) ||
                DownloadCenter.hasPausedDownload(this, entry.url) ||
                DownloadCenter.hasFailedDownload(this, entry.url) -> {
                DownloadCenter.togglePauseResumeOrRetry(this, entry.url)
                updateDownloadUi()
            }
            entry.url.startsWith("file://") -> {
                Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            }
            else -> showDownloadSheet()
        }
    }

    private fun confirmDeleteDownload() {
        val entry = PlaybackCenter.currentEntry ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_confirm)
            .setMessage(entry.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadCenter.recordsFor(this, entry.url).forEach { record ->
                    DownloadCenter.delete(this, record)
                }
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
                updateDownloadUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentVideoEntry(): com.sparktube.app.data.VideoEntry? =
        PlaybackCenter.currentEntry?.toVideoEntry()

    private fun showPlaylistSheet() {
        val video = currentVideoEntry() ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.playlist_sheet_title)))

        root.addView(
            sheetRow("+ " + getString(R.string.new_playlist), selected = false) {
                sheet.dismiss()
                showNewPlaylistDialog()
            }
        )

        val playlists = LocalStore.playlists(this)
        if (playlists.isEmpty()) {
            root.addView(hintRow(getString(R.string.playlist_sheet_empty)))
        } else {
            playlists.forEach { playlist ->
                val added = playlist.items.any { it.url == video.url }
                root.addView(
                    sheetMenuRow(
                        playlist.name,
                        if (added) getString(R.string.playlist_added) else ""
                    ) {
                        if (added) {
                            LocalStore.removeFromPlaylist(this, playlist.id, video.url)
                            Toast.makeText(
                                this,
                                getString(R.string.removed_from_playlist_fmt, playlist.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            LocalStore.addToPlaylist(this, playlist.id, video)
                            Toast.makeText(
                                this,
                                getString(R.string.saved_to_playlist_fmt, playlist.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        sheet.dismiss()
                    }
                )
            }
        }

        showSheet(sheet, root, peekDp = 380)
    }

    private fun showNewPlaylistDialog() {
        val video = currentVideoEntry() ?: return
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.new_playlist_hint)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_playlist)
            .setView(container)
            .setPositiveButton(R.string.create_playlist) { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(
                        this, R.string.playlist_name_empty, Toast.LENGTH_SHORT
                    ).show()

                    else -> {
                        val created = LocalStore.createPlaylist(this, name)
                        if (created == null) {
                            Toast.makeText(
                                this, R.string.playlist_name_exists, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            LocalStore.addToPlaylist(this, created.id, video)
                            Toast.makeText(
                                this,
                                getString(R.string.saved_to_playlist_fmt, name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hintRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(4), dp(10), dp(4), dp(6))
        setTextColor(getColor(R.color.on_surface_variant))
    }

    private fun updateDownloadUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        when {
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                val percent = DownloadCenter.downloadProgress(this, entry.url).coerceIn(0, 100)
                val bytesText = DownloadCenter.downloadBytesText(this, entry.url)
                val dlLabel = getString(R.string.downloading_fmt, percent)
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_pause)
                binding.actionDownloadIcon.setColorFilter(Themes.accentColor(this))
                binding.actionDownloadLabel.text = dlLabel

                binding.downloadProgressContainer.isVisible = true
                binding.downloadProgressLabel.text = "⏸ $dlLabel · Tap to Pause"
                binding.downloadBytesLabel.text = bytesText
                binding.downloadProgressBar.isIndeterminate = percent == 0 && bytesText.isBlank()
                binding.downloadProgressBar.progress = percent
                binding.downloadProgressBar.alpha = 1.0f
            }
            DownloadCenter.hasPausedDownload(this, entry.url) -> {
                val percent = DownloadCenter.downloadProgress(this, entry.url).coerceIn(0, 100)
                val bytesText = DownloadCenter.downloadBytesText(this, entry.url)
                val pausedLabel = getString(R.string.paused_fmt, percent)
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_play_arrow)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.music_green))
                binding.actionDownloadLabel.text = pausedLabel

                binding.downloadProgressContainer.isVisible = true
                binding.downloadProgressLabel.text = "▶ $pausedLabel · Tap to Resume"
                binding.downloadBytesLabel.text = bytesText
                binding.downloadProgressBar.isIndeterminate = false
                binding.downloadProgressBar.progress = percent
                binding.downloadProgressBar.alpha = 0.6f
            }
            DownloadCenter.hasFailedDownload(this, entry.url) -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_player_replay)
                binding.actionDownloadIcon.setColorFilter(Themes.accentColor(this))
                binding.actionDownloadLabel.text = getString(R.string.retry)

                binding.downloadProgressContainer.isVisible = true
                binding.downloadProgressLabel.text = "↻ Download failed · Tap to Retry"
                binding.downloadBytesLabel.text = ""
                binding.downloadProgressBar.isIndeterminate = false
                binding.downloadProgressBar.progress = 0
            }
            entry.url.startsWith("file://") || DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_check)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.music_green))
                binding.actionDownloadLabel.text = getString(R.string.downloaded_label)
                binding.downloadProgressContainer.isVisible = false
            }
            else -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_download)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.on_surface))
                binding.actionDownloadLabel.text = getString(R.string.action_download)
                binding.downloadProgressContainer.isVisible = false
            }
        }
    }

    private val downloadPoll = object : Runnable {
        override fun run() {
            val entry = PlaybackCenter.currentEntry
            if (entry != null && !isFinishing) {
                if (DownloadCenter.hasActiveDownload(this@PlayerActivity, entry.url)) {
                    DownloadCenter.refreshStatuses(this@PlayerActivity)
                }
                updateDownloadUi()
                checkSponsorBlockSkip()
                updateActiveSubtitleCue()
            }
            downloadHandler.postDelayed(this, 300)
        }
    }

    private fun checkSponsorBlockSkip() {
        if (!AppPrefs.sponsorBlock || !PlaybackCenter.isPlaying || sponsorSegments.isEmpty()) return
        val pos = PlaybackCenter.positionMs
        val hit = sponsorSegments.firstOrNull { pos >= it.startMs && pos < it.endMs - 400L } ?: return
        if (hit.startMs == lastSkippedSegmentStart) return
        lastSkippedSegmentStart = hit.startMs
        PlaybackCenter.seekTo(hit.endMs)
        Toast.makeText(
            this,
            "SponsorBlock: Skipped ${hit.category}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleFavorite() {
        val nowFav = PlaybackCenter.toggleFavoriteCurrent()
        Toast.makeText(
            this,
            if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareVideo() {
        val entry = PlaybackCenter.currentEntry ?: return
        val shareUrl = if (entry.url.startsWith("file://")) {
            DownloadCenter.recordsFor(this, entry.url).firstOrNull()?.sourceUrl?.takeIf { it.startsWith("http") }
                ?: "https://www.youtube.com/watch?v=${DownloadCenter.videoIdOf(entry.url)}"
        } else {
            entry.url
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareUrl)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    private fun openChannel() {
        val entry = PlaybackCenter.currentEntry ?: return
        val channelUrl = PlaybackCenter.catalogForCurrentEntry()?.uploaderUrl ?: return
        if (channelUrl.isBlank()) return
        ChannelActivity.start(this, channelUrl, entry.uploader)
    }

    private fun toggleSubscribe() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalogForCurrentEntry() ?: return
        val channelUrl = catalog.uploaderUrl
        if (channelUrl.isBlank()) return
        val channelEntry = com.sparktube.app.data.ChannelEntry(
            url = channelUrl,
            name = catalog.uploaderName.ifBlank { entry.uploader },
            avatarUrl = catalog.uploaderAvatarUrl,
            subscriberCount = channelSubCount
        )
        val nowSubscribed = LocalStore.toggleSubscription(this, channelEntry)
        Toast.makeText(
            this,
            if (nowSubscribed) R.string.subscribed_toast else R.string.unsubscribed_toast,
            Toast.LENGTH_SHORT
        ).show()
        updateSubscribeUi()
    }

    private fun updateSubscribeUi() {
        val channelUrl = PlaybackCenter.catalogForCurrentEntry()?.uploaderUrl
        if (channelUrl.isNullOrBlank()) {
            binding.subscribeButton.isVisible = false
            return
        }
        binding.subscribeButton.isVisible = true
        val subscribed = LocalStore.isSubscribed(this, channelUrl)
        binding.subscribeButton.text = getString(if (subscribed) R.string.subscribed else R.string.subscribe)
        binding.subscribeButton.setBackgroundResource(
            if (subscribed) R.drawable.bg_subscribe_on else R.drawable.bg_subscribe_off
        )
        binding.subscribeButton.setTextColor(
            getColor(if (subscribed) R.color.subscribe_on_text else R.color.white)
        )
    }

    private fun retry() {
        binding.errorView.isVisible = false
        binding.loading.isVisible = true
        PlaybackCenter.retryCurrent()
    }

    private fun sheetRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(getColor(R.color.on_surface))
        setTypeface(null, Typeface.BOLD)
    }

    private fun sheetRow(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(if (selected) getColor(R.color.white) else Themes.onSurfaceColor(this@PlayerActivity))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(
                    if (selected) Themes.accentColor(this@PlayerActivity)
                    else Themes.elevatedColor(this@PlayerActivity)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /** Premium grid: section caption above a chip block (v1.7.1). */
    private fun sheetSectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(Themes.accentColor(this@PlayerActivity))
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(2), dp(12), 0, dp(4))
    }

    /** One row of premium pill chips — the Audio Studio look (v1.7.1). */
    private fun chipRow(chips: List<Triple<String, Boolean, () -> Unit>>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }
        chips.forEach { (label, active, action) ->
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    minHeight = dp(42)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setTextColor(if (active) getColor(R.color.white) else Themes.onSurfaceColor(this@PlayerActivity))
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(21).toFloat()
                        if (active) {
                            setColor(Themes.accentColor(this@PlayerActivity))
                        } else {
                            setColor(Themes.elevatedColor(this@PlayerActivity))
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.marginEnd = dp(6) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        action()
                    }
                }
            )
        }
        return row
    }

    /** Wraps chips into rows of [perRow]. */
    private fun chipGrid(
        chips: List<Triple<String, Boolean, () -> Unit>>,
        perRow: Int = 3
    ): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < chips.size) {
            column.addView(chipRow(chips.subList(i, minOf(i + perRow, chips.size))))
            i += perRow
        }
        return column
    }

    private fun sheetMenuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_card_soft)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@PlayerActivity).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(this@PlayerActivity).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
            addView(
                android.widget.ImageView(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(getColor(R.color.on_surface_variant))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                }
            )
        }

    private fun showSheet(
        sheet: BottomSheetDialog,
        root: LinearLayout,
        peekDp: Int = 460,
        lockExpanded: Boolean = false
    ) {
        root.setBackgroundResource(R.drawable.bg_glass_reddish_sheet)
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0x66FFFFFF)
            }
        }
        root.addView(handle, 0)
        // v1.7.4: scroll-performance fixes — the sheet used to feel sticky on
        // low-RAM devices because the glass tiles (semi-transparent Gradient-
        // Drawables) were re-composited on every scroll frame over the video:
        //  - a hardware layer renders the (static) sheet content once and then
        //    scrolls it as a GPU texture;
        //  - scrollingCache=off stops the per-scroll bitmap churn;
        //  - the sheet opens fully expanded and never re-collapses, so
        //    BottomSheetBehavior no longer fights the ScrollView for gestures.
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isScrollingCacheEnabled = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(root)
        }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(peekDp)
        // Only the (long, scrollable) settings grid is locked fully expanded —
        // the queue/comment sheets keep their normal drag behavior.
        if (lockExpanded) {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.isDraggable = false
        }
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            if (lockExpanded) {
                sheet.behavior.state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.show()
    }

    private data class SettingsGridTile(
        val iconRes: Int,
        val title: String,
        val value: String,
        val onClick: () -> Unit
    )

    private fun settingsGridCard(tile: SettingsGridTile): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_glass_reddish_tile)
            isClickable = true
            isFocusable = true
            com.sparktube.app.ui.music.Motion.pressable(this, 0.95f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tile.onClick()
            }

            val topRow = LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val iconBadge = android.widget.FrameLayout(this@PlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                setBackgroundResource(R.drawable.bg_glass_reddish_circle)
                addView(
                    android.widget.ImageView(this@PlayerActivity).apply {
                        setImageResource(tile.iconRes)
                        setColorFilter(Color.WHITE)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = android.widget.FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
                    }
                )
            }
            topRow.addView(iconBadge)
            topRow.addView(android.widget.Space(this@PlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            topRow.addView(
                TextView(this@PlayerActivity).apply {
                    text = tile.value
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = dp(86)
                    setTextColor(0xFFFFE4E8.toInt())
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_glass_reddish_pill)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                }
            )
            addView(topRow)

            addView(
                TextView(this@PlayerActivity).apply {
                    text = tile.title
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(12) }
                }
            )
        }

    private fun scaleModeSummary(): String = when (AppPrefs.fullscreenScale) {
        AppPrefs.SCALE_CROP -> getString(R.string.scale_crop)
        AppPrefs.SCALE_STRETCH -> getString(R.string.scale_stretch)
        else -> getString(R.string.scale_fit)
    }

    private fun showVideoSettingsSheet() {
        val catalog = PlaybackCenter.catalogForCurrentEntry()

        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(
            sheetTitle(getString(R.string.video_settings)).apply {
                setTextColor(Color.WHITE)
                setPadding(dp(2), 0, 0, dp(10))
            }
        )

        val tiles = mutableListOf<SettingsGridTile>()
        if (catalog != null) {
            tiles.add(
                SettingsGridTile(R.drawable.ic_tune, getString(R.string.settings_quality_section), qualitySummary(catalog)) {
                    sheet.dismiss()
                    showQualitySheet()
                }
            )
        }
        tiles.add(
            SettingsGridTile(R.drawable.ic_subtitles, getString(R.string.subtitles_section), subtitleModeSummary()) {
                sheet.dismiss()
                showSubtitleModeSheet()
            }
        )
        tiles.add(
            SettingsGridTile(R.drawable.ic_speed, getString(R.string.settings_speed_section), speedSummary()) {
                sheet.dismiss()
                showSpeedSheet()
            }
        )
        tiles.add(
            SettingsGridTile(R.drawable.ic_lock, getString(R.string.action_screen_lock), getString(R.string.tap_to_unlock).substringBefore(" ")) {
                sheet.dismiss()
                setScreenLocked(true)
            }
        )
        tiles.add(
            SettingsGridTile(R.drawable.ic_fullscreen, getString(R.string.action_scale_mode), scaleModeSummary()) {
                sheet.dismiss()
                cycleScaleMode()
            }
        )
        tiles.add(
            SettingsGridTile(
                R.drawable.ic_player_replay,
                "SponsorBlock",
                if (AppPrefs.sponsorBlock) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.sponsorBlock = !AppPrefs.sponsorBlock
                sheet.dismiss()
                Toast.makeText(
                    this,
                    if (AppPrefs.sponsorBlock) "SponsorBlock enabled" else "SponsorBlock disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        tiles.add(
            SettingsGridTile(R.drawable.ic_info, getString(R.string.youtube_account_title), YouTubeAuth.accountSummary()) {
                sheet.dismiss()
                awaitingLoginRetry = true
                YouTubeLoginActivity.start(this)
            }
        )
        if (catalog != null && catalog.audioTracks.size > 1) {
            tiles.add(
                SettingsGridTile(R.drawable.ic_audiotrack, getString(R.string.settings_audio_track_section), audioTrackSummary(catalog)) {
                    sheet.dismiss()
                    showAudioTrackSheet()
                }
            )
        }

        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(10) }
            }
            val left = settingsGridCard(tiles[i]).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = dp(5) }
            }
            row.addView(left)
            if (i + 1 < tiles.size) {
                val right = settingsGridCard(tiles[i + 1]).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.marginStart = dp(5) }
                }
                row.addView(right)
            } else {
                row.addView(
                    android.widget.Space(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).also { it.marginStart = dp(5) }
                    }
                )
            }
            root.addView(row)
            i += 2
        }

        showSheet(sheet, root, peekDp = 480, lockExpanded = true)
    }

    private fun qualitySummary(catalog: StreamCatalog): String {
        val current = PlaybackCenter.effectiveSelectedHeight(catalog)
            ?: return getString(R.string.quality_auto_short)
        if (current == -1) return getString(R.string.quality_audio_only)
        val fps = catalog.videoOnly.firstOrNull { it.effectiveHeight() == current }?.fps
            ?: catalog.muxed.firstOrNull { it.effectiveHeight() == current }?.fps ?: 0
        return if (fps > 30) "${current}p $fps" else "${current}p"
    }

    private fun speedSummary(): String =
        if (PlaybackCenter.playbackSpeed == 1.0f) {
            getString(R.string.speed_normal)
        } else {
            "${PlaybackCenter.playbackSpeed}x"
        }

    private fun audioTrackSummary(catalog: StreamCatalog): String {
        val selected = PlaybackCenter.selectedAudioTrackId
        val track = catalog.audioTracks.firstOrNull { it.id == selected }
            ?: catalog.audioTracks.firstOrNull()
            ?: return ""
        return track.label + if (track.isOriginal) {
            " (${getString(R.string.audio_track_original)})"
        } else {
            ""
        }
    }

    private fun showQualitySheet() {
        val catalog = PlaybackCenter.catalogForCurrentEntry() ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_quality_section)))

        val currentHeight = PlaybackCenter.effectiveSelectedHeight(catalog)
        val chips = mutableListOf<Triple<String, Boolean, () -> Unit>>(
            Triple(getString(R.string.quality_auto), currentHeight == null) {
                PlaybackCenter.setVideoQuality(null)
                binding.qualityBadgeInfo.text = "${qualitySummary(catalog)} · ${speedSummary()}"
                sheet.dismiss()
            }
        )
        val useMuxed = catalog.videoOnly.isEmpty()
        val candidates = if (useMuxed) catalog.muxed else catalog.videoOnly
        candidates.forEach { v ->
            val h = v.effectiveHeight()
            val label = "${h}p" +
                (if (v.fps > 30) " ${v.fps}fps" else "") +
                (if (useMuxed) " (muxed)" else "")
            chips.add(Triple(label, currentHeight == h) {
                PlaybackCenter.setVideoQuality(h)
                binding.qualityBadgeInfo.text = "${qualitySummary(catalog)} · ${speedSummary()}"
                sheet.dismiss()
            })
        }
        chips.add(Triple(getString(R.string.quality_audio_only), currentHeight == -1) {
            PlaybackCenter.setVideoQuality(-1)
            updateModeSwitcherUi()
            binding.qualityBadgeInfo.text = "${qualitySummary(catalog)} · ${speedSummary()}"
            sheet.dismiss()
        })
        root.addView(chipGrid(chips, perRow = 3))
        showSheet(sheet, root)
    }

    private fun showSpeedSheet() {
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_speed_section)))

        // 0.5x – 3.0x range (v1.7.0), premium chip grid (v1.7.1).
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        val currentSpeed = PlaybackCenter.playbackSpeed
        root.addView(
            chipGrid(
                speeds.map { s ->
                    Triple(
                        if (s == 1.0f) getString(R.string.speed_normal) else "${s}x",
                        kotlin.math.abs(s - currentSpeed) < 0.01f
                    ) {
                        PlaybackCenter.setSpeed(s)
                        PlaybackCenter.catalogForCurrentEntry()?.let {
                            binding.qualityBadgeInfo.text = "${qualitySummary(it)} · ${speedSummary()}"
                        }
                        sheet.dismiss()
                    }
                },
                perRow = 3
            )
        )
        showSheet(sheet, root)
    }

    private fun showAudioTrackSheet() {
        val catalog = PlaybackCenter.catalogForCurrentEntry() ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_audio_track_section)))

        val currentTrack = PlaybackCenter.selectedAudioTrackId
        root.addView(
            chipGrid(
                catalog.audioTracks.map { t ->
                    val label = t.label + if (t.isOriginal) {
                        " (${getString(R.string.audio_track_original)})"
                    } else {
                        ""
                    }
                    Triple(label, currentTrack == t.id) {
                        PlaybackCenter.setAudioTrack(t.id)
                        Toast.makeText(
                            this,
                            getString(R.string.audio_track_switched_to, label),
                            Toast.LENGTH_SHORT
                        ).show()
                        sheet.dismiss()
                    }
                },
                perRow = 2
            )
        )
        showSheet(sheet, root)
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val verifiedCatalog = PlaybackCenter.catalogForCurrentEntry()
        if (verifiedCatalog != null) {
            DownloadSheet(this, entry, verifiedCatalog) {
                updateDownloadUi()
            }.show()
            return
        }
        if (entry.url.startsWith("file://")) {
            Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        downloadResolveJob?.cancel()
        binding.loading.isVisible = true
        downloadResolveJob = lifecycleScope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (PlaybackCenter.currentEntry?.url != entry.url) return@launch
                val freshCatalog = StreamCatalog(info, entry.url)
                DownloadSheet(this@PlayerActivity, entry, freshCatalog) {
                    updateDownloadUi()
                }.show()
            } catch (_: Exception) {
                Toast.makeText(this@PlayerActivity, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            } finally {
                binding.loading.isVisible = PlaybackCenter.isResolving
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_UPLOADER = "extra_uploader"
        private const val EXTRA_THUMB = "extra_thumb"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_ATTACH = "extra_attach"
        private const val DESC_COLLAPSED_LINES = 3

        fun start(context: Context, model: com.sparktube.app.ui.common.VideoUiModel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, model.url)
                putExtra(EXTRA_TITLE, model.title)
                putExtra(EXTRA_UPLOADER, model.uploader)
                putExtra(EXTRA_THUMB, model.thumbnailUrl)
                putExtra(EXTRA_DURATION, model.durationSec)
            }
            context.startActivity(intent)
        }

        fun startResume(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_ATTACH, true)
            }
            context.startActivity(intent)
        }
    }
}
