package com.sparktube.app.ui.music

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small shared motion helpers (iOS-like press feedback and staggered entrance). */
object Motion {

    /** Scales the view down slightly while pressed and springs back. Never consumes the touch. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, pressedScale: Float = 0.96f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(pressedScale).scaleY(pressedScale)
                        .setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(2f)).setDuration(220).start()
                }
            }
            false
        }
    }

    /** Fade + rise in, staggered. Only the first [limit] items animate; the rest just appear. */
    fun enter(views: List<View>, enabled: Boolean, rise: Float, limit: Int = 8) {
        if (!enabled) return
        views.take(limit).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = rise
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 45L).setDuration(320)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }
}
