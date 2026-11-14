package com.sparktube.app.ui.library

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.FragmentLibraryBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.channel.ChannelActivity
import com.sparktube.app.ui.common.ChannelRowAdapter
import com.sparktube.app.ui.common.DownloadAdapter
import com.sparktube.app.ui.common.PlaylistAdapter
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.ui.playlist.PlaylistActivity
import java.io.File

/**
 * Library: History, Favorites, Subscriptions, Downloads and Playlists tabs.
 * Everything is stored locally on the device.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var channelAdapter: ChannelRowAdapter
    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var tab = TAB_HISTORY
    private val pollHandler = Handler(Looper.getMainLooper())

    private val downloadPollRunnable = object : Runnable {
        override fun run() {
            val ctx = context
            if (_binding == null || ctx == null || isHidden || !isResumed || tab != TAB_DOWNLOADS) return
            bindDownloads()
            val hasActive = LocalStore.downloads(ctx).any {
                it.status == DownloadCenter.STATUS_PENDING || it.status == DownloadCenter.STATUS_RUNNING
            }
            if (hasActive) {
                pollHandler.postDelayed(this, 350L)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        videoAdapter = VideoAdapter(
            onClick = { model -> PlayerActivity.start(requireContext(), model) },
            onLongClick = { model ->
                if (tab == TAB_HISTORY) {
                    LocalStore.removeFromHistory(requireContext(), model.url)
                    Toast.makeText(requireContext(), R.string.removed_from_history, Toast.LENGTH_SHORT).show()
                } else {
                    LocalStore.toggleFavorite(requireContext(), model.toEntry())
                    Toast.makeText(requireContext(), R.string.removed_from_favorites, Toast.LENGTH_SHORT).show()
                }
                refresh()
                true
            }
        )
        channelAdapter = ChannelRowAdapter(
            onClick = { channel ->
                ChannelActivity.start(requireContext(), channel.url, channel.name)
            },
            onUnsubscribe = { channel ->
                LocalStore.toggleSubscription(requireContext(), channel)
                Toast.makeText(requireContext(), R.string.unsubscribed_toast, Toast.LENGTH_SHORT).show()
                refresh()
            }
        )
        downloadAdapter = DownloadAdapter(
            onClick = { record -> onDownloadItemClicked(record) },
            onLongClick = { record -> confirmDelete(record) },
            onStateChanged = {
                bindDownloads()
                pollHandler.removeCallbacks(downloadPollRunnable)
                pollHandler.postDelayed(downloadPollRunnable, 250L)
            }
        )
        playlistAdapter = PlaylistAdapter(
            onClick = { playlist ->
                PlaylistActivity.start(requireContext(), playlist.id)
            },
            onLongClick = { playlist -> confirmDeletePlaylist(playlist) }
        )

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.itemAnimator = null
        binding.list.adapter = videoAdapter

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                this@LibraryFragment.tab = tab.position
                refresh()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.tabs.selectTab(binding.tabs.getTabAt(tab))
    }

    private fun onDownloadItemClicked(record: DownloadRecord) {
        val ctx = context ?: return
        if (record.type == DownloadCenter.TYPE_AUDIO) {
            val doneAudioRecords = LocalStore.musicDownloads(ctx).filter { rec ->
                rec.status == DownloadCenter.STATUS_DONE &&
                    rec.filePaths.any { p -> File(p).let { it.exists() && it.length() > 0L } }
            }
            val entries = doneAudioRecords.mapNotNull { rec ->
                val path = rec.filePaths.firstOrNull { p -> File(p).let { it.exists() && it.length() > 0L } }
                    ?: return@mapNotNull null
                QueueEntry(
                    url = "file://$path",
                    title = rec.title,
                    uploader = rec.uploader,
                    thumbnailUrl = rec.thumbnailUrl,
                    durationSec = rec.durationSec,
                    isMusic = true
                )
            }
            val clickedPath = record.filePaths.firstOrNull() ?: return
            val targetUrl = "file://$clickedPath"
            val idx = entries.indexOfFirst { it.url == targetUrl }.coerceAtLeast(0)
            if (entries.isNotEmpty()) {
                PlaybackCenter.playMusicQueue(entries, idx, allowRadio = false)
                NowPlayingActivity.start(ctx)
            }
        } else {
            PlaybackCenter.playDownload(record)
            PlayerActivity.startResume(ctx)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(downloadPollRunnable)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refresh()
        } else {
            pollHandler.removeCallbacks(downloadPollRunnable)
        }
    }

    private fun refresh() {
        val context = context ?: return
        pollHandler.removeCallbacks(downloadPollRunnable)
        when (tab) {
            TAB_HISTORY -> bindVideos(LocalStore.history(context), R.string.empty_history)
            TAB_FAVORITES -> bindVideos(LocalStore.videoFavorites(context), R.string.empty_favorites)
            TAB_SUBSCRIPTIONS -> bindChannels()
            TAB_PLAYLISTS -> bindPlaylists()
            else -> {
                bindDownloads()
                pollHandler.postDelayed(downloadPollRunnable, 350L)
            }
        }
    }

    private fun bindVideos(entries: List<com.sparktube.app.data.VideoEntry>, emptyText: Int) {
        binding.list.adapter = videoAdapter
        videoAdapter.submitList(entries.map { it.toUiModel() })
        binding.emptyView.setText(emptyText)
        binding.emptyView.isVisible = entries.isEmpty()
    }

    private fun bindChannels() {
        val context = context ?: return
        val channels = LocalStore.subscriptions(context)
        binding.list.adapter = channelAdapter
        channelAdapter.submitList(channels)
        binding.emptyView.setText(R.string.empty_subscriptions)
        binding.emptyView.isVisible = channels.isEmpty()
    }

    private fun bindDownloads() {
        val context = context ?: return
        DownloadCenter.refreshStatuses(context)
        val downloads = LocalStore.downloads(context)
        if (binding.list.adapter !== downloadAdapter) {
            binding.list.adapter = downloadAdapter
        }
        downloadAdapter.submitRecords(downloads)
        binding.emptyView.setText(R.string.empty_downloads)
        binding.emptyView.isVisible = downloads.isEmpty()
    }

    private fun bindPlaylists() {
        val context = context ?: return
        val playlists = LocalStore.playlists(context)
        binding.list.adapter = playlistAdapter
        playlistAdapter.submitList(playlists)
        binding.emptyView.setText(R.string.empty_playlists)
        binding.emptyView.isVisible = playlists.isEmpty()
    }

    private fun confirmDeletePlaylist(playlist: com.sparktube.app.data.Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_playlist_confirm)
            .setMessage(playlist.name)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LocalStore.deletePlaylist(requireContext(), playlist.id)
                Toast.makeText(requireContext(), R.string.playlist_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(record: com.sparktube.app.data.DownloadRecord) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_delete_confirm)
            .setMessage(record.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadCenter.delete(requireContext(), record)
                Toast.makeText(requireContext(), R.string.download_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        pollHandler.removeCallbacks(downloadPollRunnable)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAB_HISTORY = 0
        private const val TAB_FAVORITES = 1
        private const val TAB_SUBSCRIPTIONS = 2
        private const val TAB_DOWNLOADS = 3
        private const val TAB_PLAYLISTS = 4
    }
}
