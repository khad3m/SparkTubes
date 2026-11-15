package com.sparktube.app.ui.common

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.databinding.ItemDownloadBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs

/**
 * Download entries on Library > Downloads and Music > Downloads, with:
 * - Real-time horizontal progress bar (`0%..100%`) for active & paused downloads
 * - Interactive Play / Pause / Resume / Retry button (`actionButton`)
 * - Byte progress (`MB / MB`) and quality/format badges
 */
data class DownloadUiItem(
    val record: DownloadRecord,
    val progressPct: Int,
    val bytesText: String
)

class DownloadAdapter(
    private val onClick: (DownloadRecord) -> Unit,
    private val onLongClick: (DownloadRecord) -> Unit,
    private val onStateChanged: (() -> Unit)? = null
) : ListAdapter<DownloadUiItem, DownloadAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadUiItem>() {
            override fun areItemsTheSame(a: DownloadUiItem, b: DownloadUiItem): Boolean =
                a.record.videoId == b.record.videoId &&
                    a.record.type == b.record.type &&
                    a.record.quality == b.record.quality

            override fun areContentsTheSame(a: DownloadUiItem, b: DownloadUiItem): Boolean =
                a == b
        }
    }

    fun submitRecords(records: List<DownloadRecord>) {
        submitList(
            records.map { rec ->
                DownloadUiItem(
                    record = rec,
                    progressPct = DownloadCenter.recordProgress(rec),
                    bytesText = DownloadCenter.recordBytesText(rec)
                )
            }
        )
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false).also {
            com.sparktube.app.ui.music.Motion.pressable(it.root, 0.98f)
        })

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ui = getItem(position)
        val item = ui.record
        val b = holder.binding
        val ctx = b.root.context

        b.title.text = item.title
        b.uploader.text = item.uploader
        b.uploader.isVisible = item.uploader.isNotBlank()

        val typeLabel = when (item.type) {
            DownloadCenter.TYPE_AUDIO -> ctx.getString(R.string.download_type_audio)
            DownloadCenter.TYPE_AV -> ctx.getString(R.string.download_type_av)
            else -> ctx.getString(R.string.download_type_video)
        }

        val isRunning = item.status == DownloadCenter.STATUS_RUNNING || item.status == DownloadCenter.STATUS_PENDING
        val isPaused = item.status == DownloadCenter.STATUS_PAUSED
        val isFailed = item.status == DownloadCenter.STATUS_FAILED
        val isDone = item.status == DownloadCenter.STATUS_DONE
        val pct = ui.progressPct.coerceIn(0, 100)

        val statusLabel = when (item.status) {
            DownloadCenter.STATUS_DONE -> ctx.getString(R.string.download_status_done)
            DownloadCenter.STATUS_RUNNING -> "${ctx.getString(R.string.download_status_running)} $pct%"
            DownloadCenter.STATUS_PAUSED -> "${ctx.getString(R.string.download_status_paused)} $pct%"
            DownloadCenter.STATUS_FAILED -> ctx.getString(R.string.download_status_failed)
            else -> "${ctx.getString(R.string.download_status_pending)} $pct%"
        }

        val metaParts = mutableListOf(typeLabel, item.quality, statusLabel)
        if (ui.bytesText.isNotBlank()) {
            metaParts.add(ui.bytesText)
        }
        b.meta.text = metaParts.joinToString(" • ")

        b.downloadProgressBar.isVisible = isRunning || isPaused
        if (isRunning || isPaused) {
            b.downloadProgressBar.isIndeterminate = isRunning && pct == 0 && ui.bytesText.isBlank()
            b.downloadProgressBar.progress = pct
            b.downloadProgressBar.alpha = if (isPaused) 0.55f else 1.0f
        }

        val durLabel = Formatters.formatDuration(item.durationSec)
        when {
            isRunning || isPaused -> {
                b.durationBadge.isVisible = true
                b.durationBadge.text = "$pct%"
            }
            durLabel.isNotBlank() -> {
                b.durationBadge.isVisible = true
                b.durationBadge.text = durLabel
            }
            isDone -> {
                b.durationBadge.isVisible = true
                b.durationBadge.text = "✓"
            }
            else -> {
                b.durationBadge.isVisible = false
            }
        }

        // Configure right-side interactive Play / Pause / Resume / Retry button
        when {
            isRunning -> {
                b.actionButton.setImageResource(R.drawable.ic_pause)
                b.actionButton.setColorFilter(Themes.accentColor(ctx))
                b.actionButton.contentDescription = ctx.getString(R.string.cd_pause)
            }
            isPaused -> {
                b.actionButton.setImageResource(R.drawable.ic_play_arrow)
                b.actionButton.setColorFilter(ctx.getColor(R.color.music_green))
                b.actionButton.contentDescription = ctx.getString(R.string.cd_play)
            }
            isFailed -> {
                b.actionButton.setImageResource(R.drawable.ic_player_replay)
                b.actionButton.setColorFilter(Themes.accentColor(ctx))
                b.actionButton.contentDescription = ctx.getString(R.string.retry)
            }
            else -> {
                b.actionButton.setImageResource(R.drawable.ic_play_arrow)
                b.actionButton.setColorFilter(ctx.getColor(R.color.music_green))
                b.actionButton.contentDescription = ctx.getString(R.string.cd_play)
            }
        }

        val handlePrimaryAction = {
            b.actionButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (item.status) {
                DownloadCenter.STATUS_DONE -> onClick(item)
                DownloadCenter.STATUS_RUNNING, DownloadCenter.STATUS_PENDING -> {
                    DownloadCenter.pause(ctx, item)
                    Toast.makeText(ctx, ctx.getString(R.string.paused_fmt, pct), Toast.LENGTH_SHORT).show()
                    onStateChanged?.invoke()
                }
                DownloadCenter.STATUS_PAUSED -> {
                    DownloadCenter.resume(ctx, item)
                    Toast.makeText(ctx, R.string.download_resumed, Toast.LENGTH_SHORT).show()
                    onStateChanged?.invoke()
                }
                DownloadCenter.STATUS_FAILED -> {
                    DownloadCenter.retry(ctx, item)
                    Toast.makeText(ctx, R.string.download_retrying, Toast.LENGTH_SHORT).show()
                    onStateChanged?.invoke()
                }
            }
        }

        b.actionButton.setOnClickListener { handlePrimaryAction() }
        b.root.setOnClickListener { handlePrimaryAction() }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }

        Thumbs.load(b.thumb, item.thumbnailUrl)
    }
}
