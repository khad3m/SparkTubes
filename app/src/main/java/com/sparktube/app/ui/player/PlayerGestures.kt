package com.sparktube.app.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.sparktube.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch gestures on the video surface:
 *  - vertical swipe on the LEFT  -> screen brightness
 *  - vertical swipe on the RIGHT -> media volume
 *  - portrait only: swipe down in the middle -> minimize to the mini player
 *
 * Landscape (fullscreen) uses the whole surface for brightness/volume. In portrait only the
 * outer thirds do, so the swipe-down-to-minimize gesture keeps working where people expect it.
 * A plain tap is never consumed, so the controller still toggles normally.
 */
class PlayerGestures(
    private val activity: Activity,
    private val hud: View,
    private val hudIcon: ImageView,
    private val hudBar: ProgressBar,
    private val hudText: TextView,
    private val isLandscape: () -> Boolean,
    private val onMinimize: () -> Unit
) {
    private enum class Mode { NONE, BRIGHTNESS, VOLUME, MINIMIZE, IGNORE }

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val minimizeDistance = 80f * activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.IGNORE
    private var downX = 0f
    private var downY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0f
    private var minimized = false

    private val hideRunnable = Runnable {
        hud.animate().alpha(0f).setDuration(180).withEndAction { hud.visibility = View.GONE }.start()
    }

    /** Set as the PlayerView's OnTouchListener. Returns true only while a gesture owns the touch. */
    fun onTouch(v: View, e: MotionEvent): Boolean {
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
                val dy = e.y - downY
                val dx = abs(e.x - downX)
                if (mode == Mode.NONE && abs(dy) > slop * 1.5f && abs(dy) > dx * 1.4f) {
                    mode = decide(v, downX, dy)
                }
                when (mode) {
                    Mode.BRIGHTNESS -> {
                        setBrightness(startBrightness - dy / (v.height * 0.75f))
                        return true
                    }
                    Mode.VOLUME -> {
                        setVolume(startVolume - dy / (v.height * 0.75f))
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
                // Do-Not-Disturb policy can refuse; keep the HUD honest by not updating it
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
