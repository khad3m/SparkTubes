package com.sparktube.app.ui.music

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.util.Themes

/**
 * One shared, iOS-style grouped action sheet.
 * Replaces the per-screen hand-built dialogs so we inflate less and keep a single look.
 */
object ActionSheet {

    class Item(val label: String, val accent: Boolean = false, val action: () -> Unit)

    fun show(ctx: Context, title: String, subtitle: String?, items: List<Item>) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(22), px(16), px(24))
        }

        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Themes.onSurfaceColor(ctx))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(4), 0, px(4), 0)
        })
        if (!subtitle.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Themes.onSurfaceVariantColor(ctx))
                setPadding(px(4), px(2), px(4), 0)
            })
        }

        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.bg_glass_card)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(16) }
        }

        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val rippleRes = ripple.getResourceId(0, 0)
        ripple.recycle()

        items.forEachIndexed { i, item ->
            if (i > 0) {
                group.addView(View(ctx).apply {
                    setBackgroundColor(Themes.onSurfaceVariantColor(ctx))
                    alpha = 0.18f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = px(16) }
                })
            }
            group.addView(TextView(ctx).apply {
                text = item.label
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = px(52)
                setPadding(px(18), px(12), px(18), px(12))
                setTextColor(
                    if (item.accent) ctx.getColor(R.color.music_green) else Themes.onSurfaceColor(ctx)
                )
                if (rippleRes != 0) setBackgroundResource(rippleRes)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    sheet.dismiss()
                    item.action()
                }
            })
        }
        root.addView(group)

        sheet.setContentView(root)
        // Frosted sheet: translucent surface with large top corners; real background blur on Android 12+
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
            GradientDrawable().apply {
                setColor(ctx.getColor(R.color.glass_sheet))
                cornerRadii = floatArrayOf(
                    px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), 0f, 0f, 0f, 0f
                )
                setStroke(1, ctx.getColor(R.color.glass_stroke))
            }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { sheet.window?.setBackgroundBlurRadius(48) } catch (_: Throwable) { }
        }
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }
}
