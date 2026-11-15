package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.databinding.ItemRemotePlaylistBinding
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Thumbs

/**
 * YouTube playlist rows on the Search → Playlists tab (v1.7.0).
 * Tap plays the whole playlist as a video or audio queue; long-press is unused.
 */
class RemotePlaylistAdapter(
    private val onClick: (RemotePlaylistModel) -> Unit
) : ListAdapter<RemotePlaylistModel, RemotePlaylistAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RemotePlaylistModel>() {
            override fun areItemsTheSame(a: RemotePlaylistModel, b: RemotePlaylistModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: RemotePlaylistModel, b: RemotePlaylistModel): Boolean =
                a.url == b.url && a.title == b.title && a.videoCount == b.videoCount
        }
    }

    class VH(val binding: ItemRemotePlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRemotePlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.title.text = item.title
        val count = if (item.videoCount > 0) {
            "${Formatters.formatViewCount(item.videoCount)} videos"
        } else {
            "Playlist"
        }
        val parts = listOf(item.uploader, count).filter { it.isNotBlank() }
        b.meta.text = parts.joinToString(" • ")
        b.countBadge.text = if (item.videoCount > 0) "${item.videoCount}" else ""
        b.countBadge.visibility =
            if (item.videoCount > 0) android.view.View.VISIBLE else android.view.View.GONE
        Thumbs.load(b.cover, item.thumbnailUrl)
        Motion.pressable(b.root, 0.97f)
        b.root.setOnClickListener { onClick(item) }
    }
}

/** Plain data model for a remote (YouTube) playlist search result. */
data class RemotePlaylistModel(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val videoCount: Long
)
