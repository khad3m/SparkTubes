package com.sparktube.app.ui.common

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sparktube.app.R
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * In-app update download experience (v1.7.0): picking "Download" on the
 * update dialog streams the APK inside this dialog — a determinate progress
 * bar with live percentage / size — and then offers one-tap install through
 * the system installer. No browser, no separate download screen.
 */
object UpdateDownloadUi {

    fun start(activity: Activity, release: UpdateChecker.Release, scope: CoroutineScope) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val status = TextView(activity).apply {
            text = activity.getString(R.string.update_downloading, 0)
            textSize = 14f
            setTextColor(activity.getColor(R.color.on_surface))
        }
        val size = TextView(activity).apply {
            text = ""
            textSize = 12f
            setTextColor(activity.getColor(R.color.on_surface_variant))
            setPadding(0, dp(3), 0, 0)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
        }
        root.addView(status)
        root.addView(size)
        root.addView(bar)

        var userCancelled = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("${activity.getString(R.string.update_available_title)} (${release.tag})")
            .setView(root)
            .setNegativeButton(android.R.string.cancel) { _, _ -> userCancelled = true }
            .create()
        dialog.show()

        val cancelled = { userCancelled || activity.isFinishing || activity.isDestroyed }
        scope.launch {
            val file = UpdateChecker.downloadApk(activity, release) { bytes, total ->
                if (cancelled()) return@downloadApk
                activity.runOnUiThread {
                    if (cancelled()) return@runOnUiThread
                    if (total > 0L) {
                        bar.isIndeterminate = false
                        bar.max = 100
                        bar.progress = ((bytes * 100L) / total).toInt().coerceIn(0, 100)
                        status.text = activity.getString(
                            R.string.update_downloading, bar.progress
                        )
                        size.text = "${Formatters.formatBytes(bytes)} / ${Formatters.formatBytes(total)}"
                    } else {
                        size.text = Formatters.formatBytes(bytes)
                    }
                }
            }
            if (userCancelled) return@launch
            if (file != null) {
                dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                AlertDialog.Builder(activity)
                    .setTitle(R.string.update_ready_title)
                    .setMessage(activity.getString(R.string.update_ready_message, release.tag))
                    .setPositiveButton(R.string.update_install_now) { _, _ ->
                        UpdateChecker.installApk(activity, file)
                    }
                    .setNegativeButton(R.string.later, null)
                    .show()
            } else {
                dialog.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
