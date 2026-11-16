package com.sparktube.app.ui.common

import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.sparktube.app.R
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.util.Thumbs

/**
 * Reusable floating mini player bar (v1.7.0) for secondary screens (Search…).
 * Mirrors the MainActivity mini player minus the embedded video surface:
 * thumbnail, title/subtitle, play/pause, close; tap opens the full player.
 * Lifecycle: [start] in onResume, [stop] in onPause.
 *
 * v1.7.1: every view lookup and every binding pass is null-safe and failure
 * isolated — the bar can never crash the host screen (Search), whatever the
 * device does. If wiring fails the bar simply stays hidden.
 */
class MiniPlayerBar(
    root: View,
    private val openPlayer: () -> Unit = {}
) : PlaybackCenter.Listener {

    private val tag = "MiniPlayerBar"

    // Nullable everywhere: a missing view means the feature hides itself
    // instead of throwing.
    private val bar: LinearLayout? = root.findViewById(R.id.miniBar)
    private val thumb: ShapeableImageView? = root.findViewById(R.id.miniBarThumb)
    private val title: TextView? = root.findViewById(R.id.miniBarTitle)
    private val subtitle: TextView? = root.findViewById(R.id.miniBarSubtitle)
    private val playPause: ImageButton? = root.findViewById(R.id.miniBarPlayPause)
    private val close: ImageButton? = root.findViewById(R.id.miniBarClose)
    private var lastThumbKey: String? = null
    private val wired: Boolean

    init {
        wired = runCatching {
            val barView = bar ?: return@runCatching false
            playPause?.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                runCatching { PlaybackCenter.togglePlayPause() }
            }
            close?.setOnClickListener { runCatching { PlaybackCenter.stopPlayback() } }
            barView.setOnClickListener {
                val entry = PlaybackCenter.currentEntry ?: return@setOnClickListener
                runCatching {
                    if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO || PlaybackCenter.audioOnlyMode) {
                        NowPlayingActivity.start(barView.context)
                    } else {
                        openPlayer()
                    }
                }.onFailure { Log.w(tag, "open player failed: ${it.message}") }
            }
            true
        }.getOrDefault(false)
        if (!wired) Log.w(tag, "mini bar views missing — feature disabled for this screen")
    }

    fun start() {
        if (!wired) return
        runCatching {
            PlaybackCenter.addListener(this)
            updateAll()
        }.onFailure { Log.w(tag, "start failed: ${it.message}") }
    }

    fun stop() {
        if (!wired) return
        runCatching { PlaybackCenter.removeListener(this) }
    }

    private fun updateAll() {
        updateItem(PlaybackCenter.currentEntry)
        updatePlayPause()
    }

    private fun updateItem(entry: QueueEntry?) {
        val barView = bar ?: return
        val show = entry != null && !PlaybackCenter.inPip
        barView.visibility = if (show) View.VISIBLE else View.GONE
        if (entry == null) {
            lastThumbKey = null
            return
        }
        runCatching {
            title?.text = entry.title
            title?.isSelected = true
            subtitle?.text = entry.uploader
            val key = "${entry.url}|${entry.thumbnailUrl}"
            if (key != lastThumbKey) {
                lastThumbKey = key
                thumb?.let { Thumbs.load(it, entry.thumbnailUrl) }
            }
        }.onFailure { Log.w(tag, "bind failed: ${it.message}") }
    }

    private fun updatePlayPause() {
        playPause?.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
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
