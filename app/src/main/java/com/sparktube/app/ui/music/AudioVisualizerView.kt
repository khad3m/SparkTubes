package com.sparktube.app.ui.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Beat-reactive neon spectrum waveform bar visualizer for the Apple Music
 * Now Playing screen. Smoothly animates 28 rounded vertical frequency bars
 * with a crimson-to-white gradient while playback is active, and settles
 * gracefully to a calm baseline when paused.
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 28
    private val barHeights = FloatArray(barCount) { 0.15f }
    private val barRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var active = false

    fun setPlaying(playing: Boolean) {
        if (active == playing) return
        active = playing
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFFFF0033.toInt(), 0xFFFF4D6A.toInt(), 0xCCFFFFFF.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis() / 1000.0
        val totalSlots = barCount * 1.65f
        val barWidth = (w / totalSlots).coerceAtLeast(3f)
        val gap = (w - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
        val radius = barWidth / 2f

        var anyMoving = false
        for (i in 0 until barCount) {
            val target = if (active) {
                val centerWeight = 1f - abs(i - barCount / 2f) / (barCount / 2f) * 0.45f
                val wave1 = sin(now * 6.4 + i * 0.55).toFloat()
                val wave2 = sin(now * 9.8 - i * 0.85).toFloat()
                val wave3 = sin(now * 3.7 + i * 1.15).toFloat()
                val raw = 0.22f + 0.78f * abs(wave1 * 0.48f + wave2 * 0.34f + wave3 * 0.18f)
                (raw * centerWeight).coerceIn(0.16f, 1f)
            } else {
                0.15f
            }
            val current = barHeights[i]
            val next = current + (target - current) * 0.28f
            barHeights[i] = next
            if (abs(next - target) > 0.01f) anyMoving = true

            val barH = (next * h).coerceAtLeast(barWidth)
            val left = i * (barWidth + gap)
            val top = (h - barH) / 2f
            barRect.set(left, top, left + barWidth, top + barH)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }

        if (active || anyMoving) {
            postInvalidateOnAnimation()
        }
    }
}
