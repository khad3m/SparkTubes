package com.sparktube.app.ui.music

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.databinding.ActivityMusicListBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.common.DownloadAdapter
import com.sparktube.app.ui.common.MusicRowAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import java.io.File

/**
 * The music page's own lists: Favorites (songs favorited from the player)
 * and Downloads (audio files with live download progress bars and Play / Pause / Resume / Retry).
 * Playing any finished download queues strictly the completed downloads list
 * and starts the exact clicked track in NowPlayingActivity.
 */
class MusicListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicListBinding
    private lateinit var favAdapter: MusicRowAdapter
    private lateinit var downloadAdapter: DownloadAdapter

    private var favourites = true
    private var entries: List<QueueEntry> = emptyList()
    private val pollHandler = Handler(Looper.getMainLooper())

    private val downloadPollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || favourites) return
            reload()
            val hasActive = LocalStore.musicDownloads(this@MusicListActivity).any {
                it.status == DownloadCenter.STATUS_PENDING || it.status == DownloadCenter.STATUS_RUNNING
            }
            if (hasActive) {
                pollHandler.postDelayed(this, 350L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMusicListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        favourites = intent.getBooleanExtra(EXTRA_FAVOURITES, true)
        binding.title.text =
            getString(if (favourites) R.string.music_favorites else R.string.music_downloads)

        favAdapter = MusicRowAdapter(
            onClick = { model ->
                val index = entries.indexOfFirst { it.url == model.url }
                if (index >= 0) {
                    PlaybackCenter.playMusicQueue(entries, index, allowRadio = false)
                    NowPlayingActivity.start(this)
                }
            },
            onLongClick = { model ->
                LocalStore.toggleFavorite(this, VideoEntry(
                    url = model.url,
                    title = model.title,
                    uploader = model.uploader,
                    thumbnailUrl = model.thumbnailUrl,
                    durationSec = model.durationSec,
                    isMusic = true
                ))
                Toast.makeText(this, R.string.removed_from_favorites, Toast.LENGTH_SHORT).show()
                reload()
            }
        )

        downloadAdapter = DownloadAdapter(
            onClick = { record ->
                val path = record.filePaths.firstOrNull { p -> File(p).let { it.exists() && it.length() > 0L } }
                if (path == null) {
                    Toast.makeText(this, "Downloaded file is missing", Toast.LENGTH_SHORT).show()
                    return@DownloadAdapter
                }
                val targetUrl = "file://$path"
                val index = entries.indexOfFirst { it.url == targetUrl }
                if (index >= 0) {
                    PlaybackCenter.playMusicQueue(entries, index, allowRadio = false)
                    NowPlayingActivity.start(this)
                } else {
                    PlaybackCenter.playDownload(record)
                    NowPlayingActivity.start(this)
                }
            },
            onLongClick = { record ->
                confirmDelete(record)
            },
            onStateChanged = {
                reload()
                pollHandler.removeCallbacks(downloadPollRunnable)
                pollHandler.postDelayed(downloadPollRunnable, 250L)
            }
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.itemAnimator = null
        binding.list.adapter = if (favourites) favAdapter else downloadAdapter

        binding.backButton.setOnClickListener { finish() }
        binding.playAllButton.setOnClickListener {
            if (entries.isNotEmpty()) {
                PlaybackCenter.playMusicQueue(entries, 0, allowRadio = false)
                NowPlayingActivity.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
        reload()
        if (!favourites) {
            pollHandler.removeCallbacks(downloadPollRunnable)
            pollHandler.postDelayed(downloadPollRunnable, 350L)
        }
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(downloadPollRunnable)
    }

    private fun reload() {
        if (favourites) {
            entries = LocalStore.musicFavorites(this).map { it.toQueueEntry() }
            favAdapter.submitList(entries.map { it.toRowModel() })
            binding.emptyView.setText(R.string.empty_music_favorites)
            binding.emptyView.isVisible = entries.isEmpty()
            binding.summaryRow.isVisible = entries.isNotEmpty()
            binding.subtitle.text = getString(R.string.music_songs_fmt, entries.size)
        } else {
            DownloadCenter.refreshStatuses(this)
            val allMusicRecords = LocalStore.musicDownloads(this)
            entries = allMusicRecords
                .filter { it.status == DownloadCenter.STATUS_DONE }
                .mapNotNull { record ->
                    val path = record.filePaths.firstOrNull { p ->
                        File(p).let { f -> f.exists() && f.length() > 0L }
                    } ?: return@mapNotNull null
                    record.toQueueEntry(path)
                }
            downloadAdapter.submitRecords(allMusicRecords)
            binding.emptyView.setText(R.string.empty_music_downloads)
            binding.emptyView.isVisible = allMusicRecords.isEmpty()
            binding.summaryRow.isVisible = allMusicRecords.isNotEmpty()
            binding.playAllButton.isVisible = entries.isNotEmpty()
            val folderShort = AppPrefs.downloadFolderSummary().substringAfterLast("/storage/emulated/0/")
            binding.subtitle.text = "${getString(R.string.music_songs_fmt, entries.size)} • 📁 $folderShort"
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_confirm)
            .setMessage(record.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadCenter.delete(this, record)
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_FAVOURITES = "extra_favourites"

        fun start(context: Context, favourites: Boolean) {
            context.startActivity(
                Intent(context, MusicListActivity::class.java)
                    .putExtra(EXTRA_FAVOURITES, favourites)
            )
        }
    }
}

private fun VideoEntry.toQueueEntry(): QueueEntry = QueueEntry(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    isMusic = true
)

private fun QueueEntry.toRowModel(): VideoUiModel = VideoUiModel(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    viewCount = -1L,
    uploadDate = ""
)

private fun DownloadRecord.toQueueEntry(path: String): QueueEntry = QueueEntry(
    url = "file://$path",
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    isMusic = true
)
