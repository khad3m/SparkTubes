package com.sparktube.app.ui.common

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.ui.player.DownloadSheet
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.ui.music.ActionSheet
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.9.6: shared overflow menu for every video card — Play, Favorite, Share,
 * Download (opens the quality picker + Downloads flow) and an optional Remove.
 */
object CardMenu {

    private val main = Handler(Looper.getMainLooper())

    fun show(
        ctx: Context,
        model: VideoUiModel,
        onRemove: (() -> Unit)? = null,
        removeLabel: String = "Remove"
    ) {
        val entry = QueueEntry(
            url = model.url,
            title = model.title,
            uploader = model.uploader,
            thumbnailUrl = model.thumbnailUrl,
            durationSec = model.durationSec
        )
        val isFav = runCatching { LocalStore.isFavorite(ctx, model.url) }.getOrDefault(false)
        val items = mutableListOf<ActionSheet.Item>()

        items += ActionSheet.Item("Play Now") {
            PlaybackCenter.playVideo(entry)
            PlayerActivity.start(ctx, model)
        }
        items += ActionSheet.Item(if (isFav) "Remove from Favorites" else "Add to Favorites", accent = true) {
            runCatching {
                LocalStore.toggleFavorite(ctx, VideoEntry(
                    url = model.url,
                    title = model.title,
                    uploader = model.uploader,
                    thumbnailUrl = model.thumbnailUrl,
                    durationSec = model.durationSec
                ))
            }.onFailure {
                Toast.makeText(ctx, "Could not update favorites", Toast.LENGTH_SHORT).show()
            }
        }
        items += ActionSheet.Item("Share") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${model.title} — ${model.url}")
            }
            runCatching { ctx.startActivity(Intent.createChooser(send, "Share video")) }
        }
        items += ActionSheet.Item("Download (Audio / Video)", accent = true) {
            startDownload(ctx, model, entry)
        }
        if (onRemove != null) {
            items += ActionSheet.Item(removeLabel) { onRemove() }
        }
        ActionSheet.show(ctx, model.title, model.uploader, items)
    }

    /** Resolves stream qualities in the background, then opens the DownloadSheet quality picker. */
    private fun startDownload(ctx: Context, model: VideoUiModel, entry: QueueEntry) {
        Toast.makeText(ctx, "Preparing download…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val catalog = withContext(Dispatchers.IO) {
                runCatching { StreamCatalog(YtRepository.streamInfo(model.url), model.url) }.getOrNull()
            }
            if (catalog == null) {
                Toast.makeText(ctx, "Couldn't resolve streams — try again", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching {
                DownloadSheet(ctx, entry, catalog).show()
            }.onFailure {
                Toast.makeText(ctx, "Couldn't open download options", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
