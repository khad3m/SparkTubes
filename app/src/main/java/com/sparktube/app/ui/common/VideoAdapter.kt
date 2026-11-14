package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.databinding.ItemVideoBinding
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.Avatars
import com.sparktube.app.util.Thumbs

class VideoAdapter(
    private val onClick: (VideoUiModel) -> Unit,
    private val onLongClick: ((VideoUiModel) -> Boolean)? = null,
    private val onAudioClick: ((VideoUiModel) -> Unit)? = null
) : ListAdapter<VideoUiModel, VideoAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoUiModel>() {
            override fun areItemsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url && a.title == b.title && a.viewsLabel == b.viewsLabel
        }
    }

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        val metaParts = listOf(item.uploader, item.viewsLabel, item.uploadDate).filter { it.isNotBlank() }
        b.meta.text = metaParts.joinToString(" • ")

        if (item.durationLabel.isEmpty()) {
            b.duration.visibility = android.view.View.GONE
        } else {
            b.duration.visibility = android.view.View.VISIBLE
            b.duration.text = item.durationLabel
        }

        Thumbs.load(b.thumbnail, item.thumbnailUrl)
        Avatars.load(b.avatar, item.uploaderAvatarUrl, item.uploader)

        if (onAudioClick != null) {
            b.cardAudioQuickBtn.visibility = android.view.View.VISIBLE
            b.cardAudioQuickBtn.setOnClickListener {
                onAudioClick.invoke(item)
            }
        } else {
            b.cardAudioQuickBtn.visibility = android.view.View.GONE
        }

        b.root.animate().cancel()
        b.root.scaleX = 1f
        b.root.scaleY = 1f
        Motion.pressable(b.root, 0.98f)
        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick?.invoke(item) ?: false
        }
    }
}
