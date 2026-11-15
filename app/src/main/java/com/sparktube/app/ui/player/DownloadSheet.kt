package com.sparktube.app.ui.player

import android.content.Context
import android.graphics.Typeface
import android.os.Environment
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.AudioTrackGroup
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.playback.effectiveHeight
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.File

/**
 * Download picker: Audio only / Video + Audio / Video only, each with a
 * quality list + interactive Download Folder selector (`Download/SparkTube`
 * or any Custom Folder on local storage).
 */
class DownloadSheet(
    context: Context,
    private val entry: QueueEntry,
    private val catalog: StreamCatalog,
    private val audioOnly: Boolean = false,
    private val onDownloadStarted: (() -> Unit)? = null
) : BottomSheetDialog(context) {

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** Whether this exact type + quality was already downloaded successfully. */
    private fun downloaded(type: String, quality: String): Boolean =
        DownloadCenter.recordsFor(context, entry.url).any {
            it.type == type && it.quality == quality && it.status == DownloadCenter.STATUS_DONE
        }

    /** Whether this exact type + quality is currently downloading or paused. */
    private fun activeProgress(type: String, quality: String): Int {
        val rec = DownloadCenter.recordsFor(context, entry.url).firstOrNull {
            it.type == type && it.quality == quality &&
                (it.status == DownloadCenter.STATUS_PENDING ||
                    it.status == DownloadCenter.STATUS_RUNNING ||
                    it.status == DownloadCenter.STATUS_PAUSED)
        } ?: return -1
        return DownloadCenter.recordProgress(rec)
    }

    private fun audioExtOf(stream: AudioStream): String {
        val mime = stream.format?.mimeType.orEmpty().lowercase()
        val suffix = stream.format?.suffix.orEmpty().lowercase()
        return if (mime.contains("webm") || suffix == "webm" || suffix == "opus") "webm" else "m4a"
    }

    /** Best-effort size in bytes of one stream (exact contentLength, else bitrate x duration). */
    private fun streamBytes(stream: org.schabi.newpipe.extractor.stream.Stream, durationSec: Long): Long {
        val item = runCatching { stream.itagItem }.getOrNull() ?: return 0L
        val len = runCatching { item.contentLength }.getOrDefault(-1L)
        if (len > 0L) return len
        val bps = runCatching { item.bitrate }.getOrDefault(0)
        return if (bps > 0 && durationSec > 0L) bps.toLong() * durationSec / 8L else 0L
    }

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L * 1024L ->
            String.format(java.util.Locale.US, " • %.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(java.util.Locale.US, " • %.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.US, " • %d KB", bytes / 1024L)
    }

    /** For the merged Video+Audio download prefer H.264/MP4 video: it can be remuxed into one file. */
    private fun bestVideoForHeight(height: Int): org.schabi.newpipe.extractor.stream.VideoStream? =
        catalog.allVideoOnly
            .filter { it.effectiveHeight() == height }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                    val mime = it.format?.mimeType.orEmpty().lowercase()
                    val codec = it.codec.orEmpty().lowercase()
                    mime.contains("mp4") && (codec.startsWith("avc") || codec.isBlank())
                }.thenByDescending { runCatching { it.itagItem?.bitrate ?: 0 }.getOrDefault(0) }
            )
            .firstOrNull()

    private fun audioFormatTag(stream: AudioStream): String =
        if (audioExtOf(stream) == "webm") "Opus" else "M4A"

    override fun show() {
        val scroll = android.widget.ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.action_download)
            textSize = 18f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)
        val songTitle = TextView(context).apply {
            text = entry.title
            textSize = 13f
            maxLines = 1
            setTextColor(context.getColor(R.color.on_surface_variant))
        }
        root.addView(songTitle)

        // Interactive Destination Folder Pill (Local Storage / Custom Folder)
        val folderPill = TextView(context).apply {
            text = "📁 Folder: ${AppPrefs.downloadFolderSummary()}  (Tap to change)"
            textSize = 12f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setTextColor(Themes.accentColor(context))
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_card_soft)
            layoutParams = marginParams(top = 10)
        }
        folderPill.setOnClickListener {
            showQuickFolderPicker {
                folderPill.text = "📁 Folder: ${AppPrefs.downloadFolderSummary()}  (Tap to change)"
            }
        }
        root.addView(folderPill)

        val durationSec = catalog.durationSec.takeIf { it > 0L } ?: entry.durationSec
        val musicOnly = audioOnly || !catalog.hasVideo

        // ----- Audio only -----
        val audioTrack = catalog.audioTracks.firstOrNull()
        if (audioTrack != null) {
            root.addView(sectionTitle(context.getString(R.string.download_audio_section)))
            audioTrack.streams
                .filter { it.isUrl }
                .sortedWith(
                    compareByDescending<AudioStream> { audioExtOf(it) == "m4a" }
                        .thenByDescending { it.averageBitrate }
                )
                .distinctBy { "${it.averageBitrate}_${audioExtOf(it)}" }
                .forEach { stream ->
                    val ext = audioExtOf(stream)
                    val fmtTag = audioFormatTag(stream)
                    val qualityKey = if (stream.averageBitrate > 0) {
                        "${stream.averageBitrate} kbps ($fmtTag)"
                    } else {
                        "Audio ($fmtTag)"
                    }
                    val alreadyDone = downloaded(DownloadCenter.TYPE_AUDIO, qualityKey)
                    val inFlightPct = activeProgress(DownloadCenter.TYPE_AUDIO, qualityKey)
                    val rowLabel = when {
                        alreadyDone -> "$qualityKey • ${context.getString(R.string.download_option_done)}"
                        inFlightPct >= 0 -> "$qualityKey • ${context.getString(R.string.downloading_fmt, inFlightPct)}"
                        else -> qualityKey + sizeText(streamBytes(stream, durationSec)) + suffix(audioTrack)
                    }
                    root.addView(
                        optionRow(rowLabel, alreadyDone || inFlightPct >= 0) {
                            when {
                                alreadyDone -> Toast.makeText(
                                    context, R.string.already_downloaded, Toast.LENGTH_SHORT
                                ).show()
                                inFlightPct >= 0 -> {
                                    DownloadCenter.togglePauseResumeOrRetry(context, entry.url)
                                    onDownloadStarted?.invoke()
                                }
                                else -> {
                                    DownloadCenter.startSingle(
                                        context = context,
                                        videoUrl = entry.url,
                                        title = entry.title,
                                        uploader = entry.uploader,
                                        thumbUrl = entry.thumbnailUrl,
                                        type = DownloadCenter.TYPE_AUDIO,
                                        quality = qualityKey,
                                        streamUrl = stream.content,
                                        fileExt = ext,
                                        durationSec = durationSec
                                    )
                                    toastStarted()
                                    onDownloadStarted?.invoke()
                                }
                            }
                            dismiss()
                        }
                    )
                }
        }

        if (!musicOnly) {
            // ----- Video + Audio (Recommended full video with sound) -----
            if (catalog.videoOnly.isNotEmpty() && audioTrack?.best != null) {
                root.addView(sectionTitle(context.getString(R.string.download_av_section)))
                val note = TextView(context).apply {
                    text = context.getString(R.string.download_pair_note)
                    textSize = 12f
                    maxLines = 2
                    setTextColor(context.getColor(R.color.on_surface_variant))
                    layoutParams = marginParams()
                }
                root.addView(note)
                val bestM4aAudio = audioTrack.streams
                    .filter { it.isUrl && audioExtOf(it) == "m4a" }
                    .maxByOrNull { it.averageBitrate }
                    ?: audioTrack.best!!
                catalog.videoOnly.forEach { listed ->
                    val v = bestVideoForHeight(listed.effectiveHeight()) ?: listed
                    val quality = "${v.effectiveHeight()}p"
                    val avSize = streamBytes(v, durationSec) + streamBytes(bestM4aAudio, durationSec)
                    val alreadyDone = downloaded(DownloadCenter.TYPE_AV, quality)
                    val inFlightPct = activeProgress(DownloadCenter.TYPE_AV, quality)
                    val rowLabel = when {
                        alreadyDone -> "$quality (Video + Audio) • ${context.getString(R.string.download_option_done)}"
                        inFlightPct >= 0 -> "$quality (Video + Audio) • ${context.getString(R.string.downloading_fmt, inFlightPct)}"
                        else -> "$quality (Video + Audio)" + sizeText(avSize)
                    }
                    root.addView(
                        optionRow(rowLabel, alreadyDone || inFlightPct >= 0) {
                            when {
                                alreadyDone -> Toast.makeText(context, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                                inFlightPct >= 0 -> {
                                    DownloadCenter.togglePauseResumeOrRetry(context, entry.url)
                                    onDownloadStarted?.invoke()
                                }
                                else -> {
                                    DownloadCenter.startPair(
                                        context = context,
                                        videoUrl = entry.url,
                                        title = entry.title,
                                        uploader = entry.uploader,
                                        thumbUrl = entry.thumbnailUrl,
                                        quality = quality,
                                        videoStreamUrl = v.content,
                                        audioStreamUrl = bestM4aAudio.content,
                                        durationSec = durationSec
                                    )
                                    toastStarted()
                                    onDownloadStarted?.invoke()
                                }
                            }
                            dismiss()
                        }
                    )
                }
            }

            // ----- Video only / Muxed -----
            root.addView(sectionTitle(context.getString(R.string.download_video_section)))
            val videoChoices = if (catalog.muxed.isNotEmpty()) {
                catalog.muxed + catalog.videoOnly
            } else {
                catalog.videoOnly
            }
            videoChoices.forEach { v ->
                val label = if (v.isVideoOnly) {
                    "${v.effectiveHeight()}p (video only, no sound)"
                } else {
                    "${v.effectiveHeight()}p (muxed MP4, video + sound)"
                }
                val quality = if (v.isVideoOnly) "${v.effectiveHeight()}p-vo" else "${v.effectiveHeight()}p"
                val alreadyDone = downloaded(DownloadCenter.TYPE_VIDEO, quality)
                val inFlightPct = activeProgress(DownloadCenter.TYPE_VIDEO, quality)
                val rowLabel = when {
                    alreadyDone -> "$label • ${context.getString(R.string.download_option_done)}"
                    inFlightPct >= 0 -> "$label • ${context.getString(R.string.downloading_fmt, inFlightPct)}"
                    else -> label + sizeText(streamBytes(v, durationSec))
                }
                root.addView(
                    optionRow(rowLabel, alreadyDone || inFlightPct >= 0) {
                        when {
                            alreadyDone -> Toast.makeText(context, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                            inFlightPct >= 0 -> {
                                DownloadCenter.togglePauseResumeOrRetry(context, entry.url)
                                onDownloadStarted?.invoke()
                            }
                            else -> {
                                DownloadCenter.startSingle(
                                    context = context,
                                    videoUrl = entry.url,
                                    title = entry.title,
                                    uploader = entry.uploader,
                                    thumbUrl = entry.thumbnailUrl,
                                    type = DownloadCenter.TYPE_VIDEO,
                                    quality = quality,
                                    streamUrl = v.content,
                                    fileExt = "mp4",
                                    durationSec = durationSec
                                )
                                toastStarted()
                                onDownloadStarted?.invoke()
                            }
                        }
                        dismiss()
                    }
                )
            }
        }

        scroll.addView(root)
        setContentView(scroll)
        super.show()
    }

    private fun showQuickFolderPicker(onUpdated: () -> Unit) {
        val options = arrayOf(
            context.getString(R.string.download_folder_public),
            context.getString(R.string.download_folder_media),
            context.getString(R.string.download_folder_custom),
            context.getString(R.string.download_folder_private)
        )
        val currentIdx = when (AppPrefs.downloadFolderMode) {
            AppPrefs.FOLDER_MODE_PUBLIC_MEDIA -> 1
            AppPrefs.FOLDER_MODE_CUSTOM -> 2
            AppPrefs.FOLDER_MODE_APP_PRIVATE -> 3
            else -> 0
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_download_folder)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_PUBLIC_DOWNLOADS
                        onUpdated()
                    }
                    1 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_PUBLIC_MEDIA
                        onUpdated()
                    }
                    2 -> {
                        showCustomPathInputDialog(onUpdated)
                    }
                    3 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_APP_PRIVATE
                        onUpdated()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomPathInputDialog(onUpdated: () -> Unit) {
        val defaultBase = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube").absolutePath
        }.getOrDefault("/storage/emulated/0/Download/SparkTube")
        val input = EditText(context).apply {
            setText(AppPrefs.customDownloadPath.ifBlank { defaultBase })
            setSelection(text.length)
            setSingleLine()
        }
        val wrap = FrameLayout(context).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.download_folder_custom)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (entered.isNotBlank()) {
                    val dir = File(entered)
                    runCatching { dir.mkdirs() }
                    AppPrefs.customDownloadPath = dir.absolutePath
                    AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_CUSTOM
                    onUpdated()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionTitle(label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
            layoutParams = marginParams(top = 18)
        }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(top) }

    private fun optionRow(label: String, done: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(
                if (done) Themes.onSurfaceVariantColor(context)
                else Themes.onSurfaceColor(context)
            )
            setBackgroundResource(R.drawable.bg_card_soft)
            layoutParams = marginParams(top = 8)
            setOnClickListener { onClick() }
        }

    private fun suffix(track: AudioTrackGroup): String {
        return if (track.label.isNotBlank() && track.label != "Default") {
            " • ${track.label}"
        } else {
            ""
        }
    }

    private fun toastStarted() {
        Toast.makeText(
            context,
            "${context.getString(R.string.download_started)} → ${AppPrefs.downloadFolderSummary().substringAfterLast("/storage/emulated/0/")}",
            Toast.LENGTH_SHORT
        ).show()
    }
}
