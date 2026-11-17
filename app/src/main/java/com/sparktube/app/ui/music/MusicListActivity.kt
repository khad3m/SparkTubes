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
import com.sparktube.app.playback.toQueueEntry
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.common.DownloadAdapter
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.MusicRowAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    /** v1.9.6: preset mode — curated backend search; the raw query is never shown to the user. */
    private var presetQuery: String? = null
    private var presetLoaded = false
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
        presetQuery = intent.getStringExtra(EXTRA_PRESET_QUERY)
        if (presetQuery != null) {
            // Preset mode: title is the friendly preset name only.
            binding.title.text = intent.getStringExtra(EXTRA_PRESET_TITLE) ?: "Curated Songs"
        } else {
            binding.title.text =
                getString(if (favourites) R.string.music_favorites else R.string.music_downloads)
        }

        favAdapter = MusicRowAdapter(
            onClick = { model ->
                val index = entries.indexOfFirst { it.url == model.url }
                if (index >= 0) {
                    PlaybackCenter.playMusicQueue(entries, index, allowRadio = true)
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
                    PlaybackCenter.playMusicQueue(entries, index, allowRadio = true)
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
        binding.list.adapter = if (favourites || presetQuery != null) favAdapter else downloadAdapter

        binding.backButton.setOnClickListener { finish() }
        binding.playAllButton.setOnClickListener {
            if (entries.isNotEmpty()) {
                PlaybackCenter.playMusicQueue(entries, 0, allowRadio = true)
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
        if (presetQuery != null) {
            loadPreset()
        } else if (favourites) {
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

    /** v1.9.6: backend search for preset cards — results appear as a normal curated list. */
    private fun loadPreset() {
        val q = presetQuery ?: return
        val country = AppPrefs.countryOrDefault
        // v1.9.7: previous session's results render instantly from the cache
        if (!presetLoaded && entries.isEmpty()) {
            val warm = LocalStore.readFeedCache(this, true, country, q, 0L)
            if (warm.isNotEmpty()) {
                entries = warm.map { it.toQueueEntry() }
                favAdapter.submitList(entries.map { it.toRowModel() })
                binding.emptyView.isVisible = false
                binding.summaryRow.isVisible = true
                binding.playAllButton.isVisible = true
                binding.subtitle.text = getString(R.string.music_songs_fmt, entries.size)
            }
        }
        if (presetLoaded) {
            favAdapter.submitList(entries.map { it.toRowModel() })
            binding.subtitle.text = getString(R.string.music_songs_fmt, entries.size)
            return
        }
        if (entries.isEmpty()) binding.subtitle.text = "Loading…"
        lifecycleScope.launch {
            try {
                val page = com.sparktube.app.data.YtRepository.searchVideos(q, null)
                entries = page.items.map { it.toQueueEntry(isMusic = true) }
                LocalStore.saveFeedCache(this@MusicListActivity, true, country, q,
                    page.items.map { it.toUiModel().toEntry() })
            } catch (e: Exception) {
                entries = emptyList()
                Toast.makeText(this@MusicListActivity, "Couldn't load this mix — try again", Toast.LENGTH_SHORT).show()
            }
            presetLoaded = true
            favAdapter.submitList(entries.map { it.toRowModel() })
            binding.emptyView.isVisible = entries.isEmpty()
            binding.summaryRow.isVisible = entries.isNotEmpty()
            binding.playAllButton.isVisible = entries.isNotEmpty()
            binding.subtitle.text = getString(R.string.music_songs_fmt, entries.size)
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
        private const val EXTRA_PRESET_QUERY = "extra_preset_query"
        private const val EXTRA_PRESET_TITLE = "extra_preset_title"

        fun start(context: Context, favourites: Boolean) {
            context.startActivity(
                Intent(context, MusicListActivity::class.java)
                    .putExtra(EXTRA_FAVOURITES, favourites)
            )
        }

        /** v1.9.6: open a curated preset list — the search query stays inside the app backend. */
        fun startPreset(context: Context, title: String, query: String) {
            context.startActivity(
                Intent(context, MusicListActivity::class.java)
                    .putExtra(EXTRA_PRESET_TITLE, title)
                    .putExtra(EXTRA_PRESET_QUERY, query)
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
