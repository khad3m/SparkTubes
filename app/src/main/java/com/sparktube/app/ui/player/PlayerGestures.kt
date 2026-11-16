package com.sparktube.app.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.sparktube.app.R
import com.sparktube.app.playback.PlaybackCenter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch gestures on the video surface:
 *  - Screen Lock guard: when `isLocked()` is true, blocks all swipes/double-taps/speed-holds and triggers `onLockedTap`
 *  - Fullscreen (landscape) only: vertical swipe LEFT -> brightness, RIGHT -> media volume
 *  - Portrait: vertical swipe down in the middle -> minimize to mini player (no volume/brightness HUD, v1.7.0)
 *  - Double-tap LEFT / RIGHT -> seek -10s / +10s; keep tapping within ~0.8s to chain (+20s, +30s ...)
 *  - Horizontal swipe -> scrub (shows target time; seeks on release)
 *  - Touches starting at the very screen edge are ignored (system back gesture zone)
 *  - Long-press anywhere -> temporary 2.0x speed while finger is held down
 */
class PlayerGestures(
    private val activity: Activity,
    private val hud: View,
    private val hudIcon: ImageView,
    private val hudBar: ProgressBar,
    private val hudText: TextView,
    private val isLandscape: () -> Boolean,
    private val isLocked: () -> Boolean = { false },
    private val onLockedTap: (() -> Unit)? = null,
    private val onMinimize: () -> Unit,
    private val onDoubleTapSeek: ((forward: Boolean, totalSec: Int) -> Unit)? = null,
    private val onSpeedHoldChanged: ((holding2x: Boolean) -> Unit)? = null,
    /** Non-null text = show scrub label, null = hide it. */
    private val onScrub: ((text: String?) -> Unit)? = null
) {
    private enum class Mode { NONE, BRIGHTNESS, VOLUME, MINIMIZE, SPEED_HOLD, SEEK, IGNORE }

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val minimizeDistance = 80f * activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.IGNORE
    private var downX = 0f
    private var downY = 0f
    private var lastViewWidth = 0
    private var startBrightness = 0.5f
    private var startVolume = 0f
    private var minimized = false
    private var savedSpeedBeforeHold = 1.0f

    // chained double-tap seeking
    private var chainUntil = 0L
    private var chainForward = true
    private var chainSeconds = 0
    private var chainConsumedUp = false

    // horizontal scrubbing
    private var scrubStartPos = 0L
    private var scrubTarget = 0L
    private var lastLimitFraction = -1f
    private val edgeGuard = 20f * activity.resources.displayMetrics.density

    private val hideRunnable = Runnable {
        hud.animate().alpha(0f).setDuration(180).withEndAction { hud.visibility = View.GONE }.start()
    }

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked()) return true
                val w = lastViewWidth.takeIf { it > 0 }
                    ?: activity.window.decorView.width.takeIf { it > 0 }
                    ?: 1000
                val forward = e.x >= w * 0.5f
                chainSeek(forward, restart = forward != chainForward)
                chainConsumedUp = true
                return true
            }

            // Extra taps shortly after a double-tap keep seeking (YouTube-style).
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isLocked()) return false
                if (System.currentTimeMillis() < chainUntil) {
                    val w = lastViewWidth.takeIf { it > 0 } ?: 1000
                    val forward = e.x >= w * 0.5f
                    chainSeek(forward, restart = forward != chainForward)
                    chainConsumedUp = true
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked()) return
                if (mode == Mode.NONE && PlaybackCenter.isPlaying) {
                    mode = Mode.SPEED_HOLD
                    savedSpeedBeforeHold = PlaybackCenter.playbackSpeed
                    PlaybackCenter.setSpeed(2.0f)
                    hud.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSpeedHoldChanged?.invoke(true)
                }
            }
        }
    )

    private fun chainSeek(forward: Boolean, restart: Boolean) {
        if (restart || System.currentTimeMillis() >= chainUntil) chainSeconds = 0
        chainForward = forward
        chainSeconds += 10
        chainUntil = System.currentTimeMillis() + 800L
        val dur = PlaybackCenter.durationMs
        val target = if (forward) PlaybackCenter.positionMs + 10_000L
        else (PlaybackCenter.positionMs - 10_000L).coerceAtLeast(0L)
        PlaybackCenter.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
        hud.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onDoubleTapSeek?.invoke(forward, chainSeconds)
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    fun onTouch(v: View, e: MotionEvent): Boolean {
        lastViewWidth = v.width
        if (isLocked()) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                onLockedTap?.invoke()
            }
            return true
        }

        gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastLimitFraction = -1f
                // Ignore swipes that start on the very edge: that is the system back-gesture zone.
                mode = if (e.x < edgeGuard || e.x > v.width - edgeGuard) Mode.IGNORE else Mode.NONE
                minimized = false
                startBrightness = currentBrightness()
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.SPEED_HOLD) return true
                val dy = e.y - downY
                val dx = abs(e.x - downX)
                if (mode == Mode.NONE && abs(dy) > slop * 1.5f && abs(dy) > dx * 1.4f) {
                    mode = decide(v, downX, dy)
                } else if (mode == Mode.NONE && dx > slop * 2f && dx > abs(dy) * 1.8f &&
                    PlaybackCenter.durationMs > 0L && System.currentTimeMillis() >= chainUntil
                ) {
                    mode = Mode.SEEK
                    scrubStartPos = PlaybackCenter.positionMs
                    scrubTarget = scrubStartPos
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (mode) {
                    Mode.SEEK -> {
                        val dur = PlaybackCenter.durationMs
                        // Full-width swipe ~ 2 minutes (or the whole video if shorter).
                        val span = minOf(dur, 120_000L).toFloat()
                        val delta = ((e.x - downX) / v.width.coerceAtLeast(1)) * span
                        scrubTarget = (scrubStartPos + delta.toLong()).coerceIn(0L, dur)
                        val diffSec = ((scrubTarget - scrubStartPos) / 1000).toInt()
                        val sign = if (diffSec >= 0) "+" else "−"
                        onScrub?.invoke("${fmt(scrubTarget)} / ${fmt(dur)}  ($sign${abs(diffSec)}s)")
                        return true
                    }
                    Mode.BRIGHTNESS -> {
                        setBrightness(startBrightness - dy / (v.height.coerceAtLeast(1) * 0.75f))
                        return true
                    }
                    Mode.VOLUME -> {
                        setVolume(startVolume - dy / (v.height.coerceAtLeast(1) * 0.75f))
                        return true
                    }
                    Mode.MINIMIZE -> {
                        if (!minimized && dy > minimizeDistance) {
                            minimized = true
                            onMinimize()
                        }
                        return true
                    }
                    else -> return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SPEED_HOLD) {
                    PlaybackCenter.setSpeed(savedSpeedBeforeHold)
                    onSpeedHoldChanged?.invoke(false)
                    mode = Mode.IGNORE
                    return true
                }
                if (mode == Mode.SEEK) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) PlaybackCenter.seekTo(scrubTarget)
                    onScrub?.invoke(null)
                    hud.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    mode = Mode.IGNORE
                    return true
                }
                if (chainConsumedUp) {
                    chainConsumedUp = false
                    mode = Mode.IGNORE
                    return true
                }
                val owned = mode == Mode.BRIGHTNESS || mode == Mode.VOLUME || mode == Mode.MINIMIZE
                if (mode == Mode.BRIGHTNESS || mode == Mode.VOLUME) {
                    handler.removeCallbacks(hideRunnable)
                    handler.postDelayed(hideRunnable, 650)
                }
                mode = Mode.IGNORE
                return owned
            }
        }
        return false
    }

    private fun decide(v: View, x: Float, dy: Float): Mode {
        val w = v.width.toFloat()
        if (isLandscape()) {
            // Fullscreen (landscape): YouTube-style brightness / volume zones.
            return if (x < w / 2f) Mode.BRIGHTNESS else Mode.VOLUME
        }
        // Portrait (not fullscreen): no volume/brightness controls — a swipe
        // down in the middle simply minimizes to the mini player (v1.7.0).
        return when {
            dy > 0 -> Mode.MINIMIZE
            else -> Mode.IGNORE
        }
    }

    private fun currentBrightness(): Float {
        val window = activity.window.attributes.screenBrightness
        if (window >= 0f) return window
        return try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        val b = value.coerceIn(0.02f, 1f)
        val lp = activity.window.attributes
        lp.screenBrightness = b
        activity.window.attributes = lp
        limitHaptic(b)
        showHud(R.drawable.ic_np_brightness, b)
    }

    private fun setVolume(value: Float) {
        val f = value.coerceIn(0f, 1f)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (f * max).roundToInt()
        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            } catch (_: SecurityException) {
                return
            }
        }
        limitHaptic(f)
        showHud(if (f < 0.5f) R.drawable.ic_np_volume_low else R.drawable.ic_np_volume_high, f)
    }

    /** One tick when the slider hits the top or bottom, so you can feel the limit. */
    private fun limitHaptic(f: Float) {
        val atLimit = if (f <= 0.03f) 0f else if (f >= 0.99f) 1f else -1f
        if (atLimit >= 0f && atLimit != lastLimitFraction) {
            hud.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastLimitFraction = atLimit
    }

    private fun showHud(icon: Int, fraction: Float) {
        handler.removeCallbacks(hideRunnable)
        hudIcon.setImageResource(icon)
        val pct = (fraction * 100).roundToInt()
        hudBar.progress = pct
        hudText.text = "$pct%"
        if (hud.visibility != View.VISIBLE) {
            hud.alpha = 0f
            hud.visibility = View.VISIBLE
        }
        hud.animate().cancel()
        hud.animate().alpha(1f).setDuration(100).start()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }
}
