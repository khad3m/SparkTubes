package com.sparktube.app.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import com.sparktube.app.util.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Premium Glassmorphic In-App Update UI (Bangla):
 * - Full release details, current -> target version pill, and changelog in Bengali
 * - Live glassmorphic download progress sheet with percentage pill & byte counter
 * - One-tap APK installer sheet upon completion
 */
object UpdateDownloadUi {

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private fun createGlassSheet(activity: Activity): Pair<BottomSheetDialog, LinearLayout> {
        val sheet = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 26))
            setBackgroundResource(R.drawable.bg_glass_reddish_sheet)
        }
        val handle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(activity, 16)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 2).toFloat()
                setColor(0x70FFFFFF)
            }
        }
        root.addView(handle)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        return sheet to root
    }

    private fun headerCard(
        activity: Activity,
        titleText: String,
        subtitleText: String,
        badgeText: String
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val iconBox = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 46))
                setBackgroundResource(R.drawable.bg_glass_reddish_circle)
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_update)
                    setColorFilter(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24), Gravity.CENTER)
                })
            }
            addView(iconBox)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(activity, 14) }
                addView(TextView(activity).apply {
                    text = titleText
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = subtitleText
                    textSize = 12.5f
                    setTextColor(0xCCFFD6DC.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(activity, 2) }
                })
            }
            addView(textCol)

            if (badgeText.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = badgeText
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_glass_reddish_pill)
                    setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
                })
            }
        }
    }

    private fun actionButton(
        activity: Activity,
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(activity, 16), dp(activity, 13), dp(activity, 16), dp(activity, 13))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 16).toFloat()
                if (primary) {
                    setColor(Themes.accentColor(activity))
                } else {
                    setColor(0x26FFFFFF)
                    setStroke(dp(activity, 1), 0x40FFFFFF)
                }
            }
            isClickable = true
            isFocusable = true
            Motion.pressable(this, 0.96f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
    }

    /** Shows the full Bengali premium Update Available sheet with changelog & version details. */
    fun showAvailableSheet(activity: Activity, release: UpdateChecker.Release, scope: CoroutineScope) {
        if (activity.isFinishing || activity.isDestroyed) return
        val (sheet, root) = createGlassSheet(activity)

        root.addView(
            headerCard(
                activity,
                titleText = "নতুন আপডেট পাওয়া গেছে!",
                subtitleText = "বর্তমান: v${BuildConfig.VERSION_NAME}  →  নতুন: ${release.tag}",
                badgeText = release.tag
            )
        )

        val notesBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_reddish_tile)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 14) }
        }
        notesBox.addView(TextView(activity).apply {
            text = "আপডেটের বিস্তারিত তথ্য (Changelog):"
            textSize = 12f
            setTextColor(0xFFFFB3C0.toInt())
            setTypeface(null, Typeface.BOLD)
        })
        val rawBody = release.body.ifBlank { release.name }.ifBlank {
            "• প্লেব্যাক ও স্পিড পারফরম্যান্স আরও উন্নত করা হয়েছে\n• নতুন প্রিমিয়াম UI ও বাগ ফিক্স যুক্ত করা হয়েছে"
        }
        notesBox.addView(ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 6) }
            addView(TextView(activity).apply {
                text = rawBody
                textSize = 13f
                lineSpacingMultiplier = 1.25f
                setTextColor(0xF2FFFFFF.toInt())
            })
        })
        root.addView(notesBox)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 16) }
        }
        btnRow.addView(
            actionButton(activity, "পরে করবো", primary = false) {
                sheet.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = dp(activity, 6) }
            }
        )
        btnRow.addView(
            actionButton(activity, "এখনই আপডেট করুন", primary = true) {
                sheet.dismiss()
                start(activity, release, scope)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                    .also { it.marginStart = dp(activity, 6) }
            }
        )
        root.addView(btnRow)

        sheet.setContentView(root)
        sheet.show()
    }

    fun start(activity: Activity, release: UpdateChecker.Release, scope: CoroutineScope) {
        val (sheet, root) = createGlassSheet(activity)
        sheet.setCancelable(false)

        val pctBadge = TextView(activity).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_glass_reddish_pill)
            setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
        }

        val header = headerCard(
            activity,
            titleText = "আপডেট ডাউনলোড হচ্ছে...",
            subtitleText = "SparkTube ${release.tag} ইন-অ্যাপ ডাউনলোড",
            badgeText = ""
        )
        header.addView(pctBadge)
        root.addView(header)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_reddish_tile)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 14) }
        }
        val status = TextView(activity).apply {
            text = "সার্ভারের সাথে সংযোগ করা হচ্ছে..."
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        val size = TextView(activity).apply {
            text = "দয়া করে অপেক্ষা করুন"
            textSize = 12f
            setTextColor(0xCCFFD6DC.toInt())
            setPadding(0, dp(activity, 4), 0, 0)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressDrawable = activity.getDrawable(R.drawable.np_scrubber)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 8)
            ).also { it.topMargin = dp(activity, 12) }
        }
        card.addView(status)
        card.addView(size)
        card.addView(bar)
        root.addView(card)

        var userCancelled = false
        root.addView(
            actionButton(activity, "ডাউনলোড বাতিল করুন", primary = false) {
                userCancelled = true
                sheet.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(activity, 14) }
            }
        )

        sheet.setContentView(root)
        sheet.show()

        val cancelled = { userCancelled || activity.isFinishing || activity.isDestroyed }
        scope.launch {
            val file = UpdateChecker.downloadApk(activity, release) { bytes, total ->
                if (cancelled()) return@downloadApk
                activity.runOnUiThread {
                    if (cancelled()) return@runOnUiThread
                    if (total > 0L) {
                        val pct = ((bytes * 100L) / total).toInt().coerceIn(0, 100)
                        bar.isIndeterminate = false
                        bar.max = 100
                        bar.progress = pct
                        pctBadge.text = "$pct%"
                        status.text = "ডাউনলোড সম্পন্ন হয়েছে: $pct%"
                        size.text = "${Formatters.formatBytes(bytes)} / ${Formatters.formatBytes(total)}"
                    } else {
                        size.text = "ডাউনলোড হয়েছে: ${Formatters.formatBytes(bytes)}"
                    }
                }
            }
            if (userCancelled) return@launch
            sheet.dismiss()
            if (file != null) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                showReadyToInstallSheet(activity, release, file)
            } else if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "আপডেট ডাউনলোড ব্যর্থ হয়েছে। আবার চেষ্টা করুন।", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showReadyToInstallSheet(
        activity: Activity,
        release: UpdateChecker.Release,
        file: java.io.File
    ) {
        val (readySheet, readyRoot) = createGlassSheet(activity)
        readyRoot.addView(
            headerCard(
                activity,
                titleText = "আপডেট ইনস্টলের জন্য প্রস্তুত!",
                subtitleText = "SparkTube ${release.tag} সফলভাবে ডাউনলোড হয়েছে।",
                badgeText = "READY ✓"
            )
        )
        readyRoot.addView(
            TextView(activity).apply {
                text = "নিচের \"এখনই ইনস্টল করুন\" বাটনে ট্যাপ করলে নতুন ভার্সনটি ইনস্টল হবে। আপনার সব সেভ করা ডেটা ও প্লেলিস্ট সুরক্ষিত থাকবে।"
                textSize = 13f
                lineSpacingMultiplier = 1.25f
                setTextColor(0xE6FFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_glass_reddish_tile)
                setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(activity, 14) }
            }
        )
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 16) }
        }
        btnRow.addView(
            actionButton(activity, "পরে", primary = false) {
                readySheet.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = dp(activity, 6) }
            }
        )
        btnRow.addView(
            actionButton(activity, "এখনই ইনস্টল করুন", primary = true) {
                readySheet.dismiss()
                UpdateChecker.installApk(activity, file)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
                    .also { it.marginStart = dp(activity, 6) }
            }
        )
        readyRoot.addView(btnRow)
        readySheet.setContentView(readyRoot)
        readySheet.show()
    }
}
