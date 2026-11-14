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
 *  - Vertical swipe on LEFT  -> screen brightness
 *  - Vertical swipe on RIGHT -> media volume
 *  - Portrait middle swipe down -> minimize to mini player
 *  - Double-tap LEFT / RIGHT -> ripple seek -10s / +10s (using exact touched PlayerView width)
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
    private val onDoubleTapSeek: ((forward: Boolean) -> Unit)? = null,
    private val onSpeedHoldChanged: ((holding2x: Boolean) -> Unit)? = null
) {
    private enum class Mode { NONE, BRIGHTNESS, VOLUME, MINIMIZE, SPEED_HOLD, IGNORE }

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
                if (forward) {
                    PlaybackCenter.seekTo(PlaybackCenter.positionMs + 10_000L)
                } else {
                    PlaybackCenter.seekTo((PlaybackCenter.positionMs - 10_000L).coerceAtLeast(0L))
                }
                hud.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onDoubleTapSeek?.invoke(forward)
                return true
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
                mode = Mode.NONE
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
                }
                when (mode) {
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
            return if (x < w / 2f) Mode.BRIGHTNESS else Mode.VOLUME
        }
        return when {
            x < w * 0.32f -> Mode.BRIGHTNESS
            x > w * 0.68f -> Mode.VOLUME
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
        showHud(if (f < 0.5f) R.drawable.ic_np_volume_low else R.drawable.ic_np_volume_high, f)
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
