package com.sparktube.app.ui.common

import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.sparktube.app.R
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Thumbs

/**
 * Reusable floating mini player bar (v1.7.0) for secondary screens (Search…).
 * Mirrors the MainActivity mini player minus the embedded video surface:
 * thumbnail, title/subtitle, play/pause, close; tap opens the full player.
 * Lifecycle: [start] in onResume, [stop] in onPause.
 */
class MiniPlayerBar(
    root: View,
    private val openPlayer: () -> Unit = {}
) : PlaybackCenter.Listener {

    private val bar = root.findViewById<LinearLayout>(R.id.miniBar)
    private val thumb = root.findViewById<ShapeableImageView>(R.id.miniBarThumb)
    private val title = root.findViewById<TextView>(R.id.miniBarTitle)
    private val subtitle = root.findViewById<TextView>(R.id.miniBarSubtitle)
    private val playPause = root.findViewById<ImageButton>(R.id.miniBarPlayPause)
    private val close = root.findViewById<ImageButton>(R.id.miniBarClose)
    private var lastThumbKey: String? = null

    init {
        playPause.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            PlaybackCenter.togglePlayPause()
        }
        close.setOnClickListener { PlaybackCenter.stopPlayback() }
        bar.setOnClickListener {
            PlaybackCenter.currentEntry ?: return@setOnClickListener
            if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO || PlaybackCenter.audioOnlyMode) {
                NowPlayingActivity.start(root.context)
            } else {
                openPlayer()
            }
        }
    }

    fun start() {
        PlaybackCenter.addListener(this)
        updateAll()
    }

    fun stop() {
        PlaybackCenter.removeListener(this)
    }

    private fun updateAll() {
        updateItem(PlaybackCenter.currentEntry)
        updatePlayPause()
    }

    private fun updateItem(entry: QueueEntry?) {
        val show = entry != null && !PlaybackCenter.inPip
        bar.isVisibleCompat(show)
        if (entry == null) {
            lastThumbKey = null
            return
        }
        title.text = entry.title
        title.isSelected = true
        subtitle.text = entry.uploader
        val key = "${entry.url}|${entry.thumbnailUrl}"
        if (key != lastThumbKey) {
            lastThumbKey = key
            Thumbs.load(thumb, entry.thumbnailUrl)
        }
    }

    private fun updatePlayPause() {
        playPause.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun View.isVisibleCompat(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ----- PlaybackCenter.Listener -----

    override fun onItemChanged(entry: QueueEntry?) {
        updateItem(entry)
        updatePlayPause()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) = updatePlayPause()

    override fun onAudioOnlyChanged(audioOnly: Boolean) = updateAll()

    override fun onQueueChanged() = updatePlayPause()
}
