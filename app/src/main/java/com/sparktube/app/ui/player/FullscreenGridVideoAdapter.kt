package com.sparktube.app.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.databinding.ItemFullscreenGridVideoBinding
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.Thumbs

/**
 * Compact 2-column semi-transparent glass video card adapter used inside the
 * fullscreen right-corner video grid drawer in [PlayerActivity].
 */
class FullscreenGridVideoAdapter(
    private val onClick: (VideoUiModel) -> Unit
) : ListAdapter<VideoUiModel, FullscreenGridVideoAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoUiModel>() {
            override fun areItemsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url && a.title == b.title
        }
    }

    class VH(val binding: ItemFullscreenGridVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFullscreenGridVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        b.meta.text = listOf(item.uploader, item.viewsLabel).filter { it.isNotBlank() }.joinToString(" • ")

        if (item.durationLabel.isEmpty()) {
            b.duration.visibility = View.GONE
        } else {
            b.duration.visibility = View.VISIBLE
            b.duration.text = item.durationLabel
        }

        Thumbs.load(b.thumbnail, item.thumbnailUrl)
        Motion.pressable(b.root, 0.96f)
        b.root.setOnClickListener { onClick(item) }
    }
}
