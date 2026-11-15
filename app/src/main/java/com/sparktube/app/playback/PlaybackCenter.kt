package com.sparktube.app.playback

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LiveFilter
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.net.YouTubePlayerUnlock
import com.sparktube.app.net.ParallelRangeDataSource
import com.sparktube.app.ui.widget.SparkTubeWidgetProvider
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.DeviceTuning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class LiveContentException :
    IOException("This is a live stream, which SparkTube doesn't support playing.")

fun VideoStream.effectiveHeight(): Int =
    if (height > 0) height else StreamCatalog.resolutionHeight(resolution)

data class QueueEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val isMusic: Boolean = false
)

data class AudioTrackGroup(
    val id: String,
    val label: String,
    val isOriginal: Boolean,
    val streams: List<AudioStream>
) {
    val best: AudioStream? get() = streams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
}

class StreamCatalog(info: StreamInfo, requestedUrl: String = "") {

    val url: String = info.url?.takeIf { it.isNotBlank() } ?: requestedUrl
    val videoId: String = DownloadCenter.videoIdOf(url.ifBlank { requestedUrl })
    val name: String = info.name ?: ""
    val uploaderUrl: String = info.uploaderUrl ?: ""
    val uploaderName: String = info.uploaderName ?: ""
    val uploaderAvatarUrl: String = info.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
    val viewCount: Long = info.viewCount
    val durationSec: Long = info.duration

    val description: String = info.description?.content.orEmpty()
    val descriptionIsHtml: Boolean =
        info.description?.type == org.schabi.newpipe.extractor.stream.Description.HTML

    val uploadDateText: String =
        com.sparktube.app.util.Formatters.formatRelativeTime(info.textualUploadDate)
    val likeCount: Long = info.likeCount

    val videoOnly: List<VideoStream> = info.videoOnlyStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    val allVideoOnly: List<VideoStream> = info.videoOnlyStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }

    val muxed: List<VideoStream> = info.videoStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    val audioTracks: List<AudioTrackGroup> = info.audioStreams
        .filter { it.isUrl }
        .groupBy { it.audioTrackId ?: it.audioTrackName ?: "default" }
        .map { (id, streams) ->
            val first = streams.first()
            AudioTrackGroup(
                id = id,
                label = first.audioTrackName?.takeIf { it.isNotBlank() }
                    ?: if (id == "default") "Default" else id,
                isOriginal = first.audioTrackType == AudioTrackType.ORIGINAL,
                streams = streams.sortedByDescending { it.averageBitrate }
            )
        }
        .sortedWith(compareByDescending<AudioTrackGroup> { it.isOriginal }.thenBy { it.label })

    val subtitles: List<SubtitlesStream> = info.subtitles
        .filter { it.isUrl && !it.content.isNullOrBlank() }

    /** Up-next / related list. Falls back to a search-based list when YouTube returns none. */
    @Volatile
    var related: List<StreamInfoItem> = LiveFilter.sanitize(info.relatedItems)

    val hasVideo: Boolean get() = videoOnly.isNotEmpty() || muxed.isNotEmpty()

    companion object {
        fun resolutionHeight(resolution: String): Int =
            Regex("(\\d{3,4})").find(resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun qualityLabel(v: VideoStream): String {
        val height = v.effectiveHeight()
        val fps = if (v.fps > 30 && v.fps < 100) "${v.fps}fps" else ""
        val tag = if (v.isVideoOnly) " (video only)" else ""
        return listOf(height.toString() + "p", fps).filter { it.isNotBlank() }.joinToString(" ") + tag
    }
}

/**
 * App-wide playback engine with:
 * - Seamless Dual Audio <-> Video Mode switching (online & offline `file://` / `TYPE_AV` aware)
 * - Strict `StreamCatalog` ownership verification (`catalogForCurrentEntry`) so downloads and stream caches
 *   never mix metadata of one song with the audio stream of another song
 * - Full-queue preservation for offline Downloads and Favorites lists
 * - Strict Music-Only radio queue building (`LiveFilter.isMusicTrack`)
 * - Smooth Audio Crossfade (`AppPrefs.crossfadeSeconds`) between songs
 * - Sleep Timer (`startSleepTimer`, `sleepTimerRemainingMs`)
 * - Hardware Audio Equalizer & BassBoost presets (`applyAudioPreset`) with safe session re-binding
 */
@OptIn(UnstableApi::class)
object PlaybackCenter {

    /** Equalizer presets; index is what [AppPrefs.eqPresetIndex] stores. */
    val EQ_PRESET_LABELS = listOf(
        "Off (Disabled)",
        "Noise Cancel",
        "Only Vocal",
        "Flat (Default)",
        "Bass Boost",
        "Vocal Studio",
        "Club Electronic",
        "Treble Clarity"
    )

    enum class Mode { NONE, VIDEO, AUDIO }

    interface Listener {
        fun onItemChanged(entry: QueueEntry?) {}
        fun onResolvingChanged(isResolving: Boolean) {}
        fun onCatalogReady() {}
        fun onPlaybackStateChanged(isPlaying: Boolean) {}
        fun onQueueChanged() {}
        fun onError(message: String) {}
        fun onLiveContentBlocked() {}
        fun onFavoriteChanged(url: String, isFavorite: Boolean) {}
        fun onAudioOnlyChanged(audioOnly: Boolean) {}
        fun onNotice(message: String) {}
    }

    lateinit var appContext: Context
        private set

    val listeners = mutableListOf<Listener>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private const val RADIO_FIRST_BATCH = 30
    private const val RADIO_BATCH = 20
    private const val RADIO_REFILL_BELOW = 10
    private const val RADIO_MIN_UPCOMING = 15
    private var playerRef: ExoPlayer? = null
    private var resolveJob: Job? = null

    private var watchdogJob: Job? = null
    private var watchdogDownshifts = 0
    private var downshiftHeight: Int? = null

    private var dashActive = false
    private var dashBlocked = false
    private var sourceAppliedAt = 0L

    private val STARTUP_LIMIT_MS = 10_000L
    private val STALL_LIMIT_MS = 4_000L

    private val pendingMusic = ConcurrentHashMap<String, QueueEntry>()
    private val musicItemId = AtomicLong(0L)
    private val musicUriCache = ConcurrentHashMap<String, Uri>()
    private var prefetchJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null

    // ----- Sleep Timer & Audio Equalizer / Bass Boost -----
    private var sleepTimerJob: Job? = null
    @Volatile var sleepTimerEndRealtimeMs: Long = 0L
        private set

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var boundAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var currentEqPresetIndex: Int
        get() = runCatching { AppPrefs.eqPresetIndex }.getOrDefault(0)
        set(value) {
            runCatching { AppPrefs.eqPresetIndex = value }
        }
    val currentEqPresetLabel: String
        get() = EQ_PRESET_LABELS.getOrElse(currentEqPresetIndex) { EQ_PRESET_LABELS[0] }

    // ----- Smooth Audio Crossfade Engine -----
    private var crossfadeMonitorJob: Job? = null
    private var crossfadeRampJob: Job? = null
    private var lastCrossfadeMediaIndex: Int = -1

    val sleepTimerRemainingMs: Long
        get() {
            if (sleepTimerEndRealtimeMs <= 0L) return 0L
            return (sleepTimerEndRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerEndRealtimeMs = 0L
            return
        }
        val durationMs = minutes * 60_000L
        sleepTimerEndRealtimeMs = SystemClock.elapsedRealtime() + durationMs
        sleepTimerJob = scope.launch {
            delay(durationMs)
            sleepTimerEndRealtimeMs = 0L
            playerRef?.pause()
            notify { it.onNotice("Sleep Timer: Playback paused") }
        }
    }

    fun applyAudioPreset(presetIndex: Int, @Suppress("UNUSED_PARAMETER") label: String = "") {
        currentEqPresetIndex = presetIndex.coerceIn(0, EQ_PRESET_LABELS.size - 1)
        val sessionId = playerRef?.audioSessionId ?: return
        bindAudioEffectsForSession(sessionId)
    }

    /** Gain (dB) for a band centred at [freqHz] for the given preset. */
    private fun presetGainDb(preset: Int, freqHz: Int): Float = when (preset) {
        1 -> when { // Noise Cancel: cut low-end rumble & high-frequency hiss
            freqHz <= 120 -> -8f
            freqHz <= 300 -> -3f
            freqHz <= 1200 -> 1f
            freqHz <= 4000 -> 2f
            else -> -7f
        }
        2 -> when { // Only Vocal: isolate vocal frequencies, suppress bass & cymbals
            freqHz <= 200 -> -12f
            freqHz <= 450 -> -6f
            freqHz <= 3500 -> 6f
            freqHz <= 6000 -> 1f
            else -> -9f
        }
        4 -> when { // Bass Boost
            freqHz <= 120 -> 6f
            freqHz <= 300 -> 4f
            freqHz <= 800 -> 1f
            else -> 0f
        }
        5 -> when { // Vocal Studio
            freqHz <= 120 -> -2f
            freqHz <= 400 -> 0f
            freqHz <= 1500 -> 3f
            freqHz <= 5000 -> 4f
            else -> 1f
        }
        6 -> when { // Club Electronic
            freqHz <= 120 -> 5f
            freqHz <= 400 -> 2f
            freqHz <= 2000 -> -2f
            freqHz <= 6000 -> 2f
            else -> 5f
        }
        7 -> when { // Treble Clarity
            freqHz <= 400 -> -1f
            freqHz <= 2000 -> 0f
            freqHz <= 6000 -> 3f
            else -> 5f
        }
        else -> 0f // Off / Flat
    }

    private fun bassStrengthFor(preset: Int): Short = when (preset) {
        4 -> 700
        6 -> 450
        else -> 0
    }

    private fun bindAudioEffectsForSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        val preset = currentEqPresetIndex

        if (preset == 0) {
            runCatching { equalizer?.enabled = false }
            runCatching { bassBoost?.enabled = false }
            return
        }

        if (boundAudioSessionId != sessionId) {
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            equalizer = null
            bassBoost = null
            boundAudioSessionId = sessionId
        }

        // Equalizer and BassBoost are applied independently: one failing (not every
        // device supports both) must not stop the other from working.
        runCatching {
            if (equalizer == null) equalizer = Equalizer(0, sessionId)
            val eq = equalizer ?: return@runCatching
            val range = eq.bandLevelRange // millibels [min, max]
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            for (band in 0 until eq.numberOfBands) {
                val freqHz = eq.getCenterFreq(band.toShort()) / 1000
                val mb = (presetGainDb(preset, freqHz) * 100f).toInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
            eq.enabled = true
        }

        runCatching {
            if (bassBoost == null) bassBoost = BassBoost(0, sessionId)
            val bb = bassBoost ?: return@runCatching
            if (bb.strengthSupported) {
                bb.setStrength(bassStrengthFor(preset))
                bb.enabled = bassStrengthFor(preset) > 0
            }
        }
    }

    private fun resetVolumeImmediate() {
        crossfadeRampJob?.cancel()
        playerRef?.volume = 1.0f
    }

    private fun startCrossfadeFadeIn(durationMs: Long) {
        crossfadeRampJob?.cancel()
        val p = playerRef ?: return
        p.volume = 0.18f
        crossfadeRampJob = scope.launch {
            val steps = 14
            val stepMs = (durationMs / steps).coerceIn(40L, 180L)
            for (i in 1..steps) {
                delay(stepMs)
                if (mode != Mode.AUDIO) {
                    p.volume = 1.0f
                    return@launch
                }
                p.volume = (0.18f + (0.82f * i / steps)).coerceIn(0.18f, 1.0f)
            }
            p.volume = 1.0f
        }
    }

    private fun startCrossfadeMonitor() {
        crossfadeMonitorJob?.cancel()
        lastCrossfadeMediaIndex = -1
        crossfadeMonitorJob = scope.launch {
            while (isActive && mode == Mode.AUDIO) {
                delay(240L)
                val p = playerRef ?: continue
                val fadeSec = AppPrefs.crossfadeSeconds
                if (fadeSec <= 0 || !AppPrefs.musicAutoplayNext) {
                    if (crossfadeRampJob?.isActive != true && p.volume < 0.99f) {
                        p.volume = 1.0f
                    }
                    continue
                }
                if (!p.isPlaying || crossfadeRampJob?.isActive == true) continue
                val dur = p.duration
                val pos = p.currentPosition
                val fadeMs = fadeSec * 1000L
                if (dur > fadeMs * 2 && pos >= dur - fadeMs && p.hasNextMediaItem()) {
                    val remMs = (dur - pos).coerceAtLeast(0L)
                    val ratio = (remMs.toFloat() / fadeMs.toFloat()).coerceIn(0.15f, 1.0f)
                    p.volume = ratio
                    if (remMs <= 300L && lastCrossfadeMediaIndex != p.currentMediaItemIndex) {
                        lastCrossfadeMediaIndex = p.currentMediaItemIndex
                        p.seekToNextMediaItem()
                        startCrossfadeFadeIn(minOf(fadeMs, 2600L))
                    }
                } else if (p.volume < 0.99f) {
                    p.volume = 1.0f
                }
            }
        }
    }

    @Volatile var mode: Mode = Mode.NONE
        private set
    @Volatile var inPip: Boolean = false
    @Volatile var audioOnlyMode: Boolean = false
        private set

    val queue = mutableListOf<QueueEntry>()
    var queueIndex: Int = -1
        private set
    var radioMode: Boolean = false
        private set

    @Volatile var playlistMode: Boolean = false
        private set

    @Volatile var isResolving: Boolean = false
        private set
    private var usedFallback = false

    /** One signed-in unlock retry per play attempt (v1.7.2 age-gate wiring). */
    private var usedSignedInRetry = false

    var catalog: StreamCatalog? = null
        private set

    /**
     * Returns [catalog] ONLY when its `videoId` matches [currentEntry]'s `videoId`.
     * Prevents stale catalogs from a previous song/video from ever being used for
     * downloading or stream building when switching tracks.
     */
    fun catalogForCurrentEntry(): StreamCatalog? {
        val entry = currentEntry ?: return null
        val c = catalog ?: return null
        val entryId = DownloadCenter.videoIdOf(entry.url)
        return if (c.videoId.isNotBlank() && c.videoId == entryId) c else null
    }

    var selectedAudioTrackId: String? = null
        private set
    private var selectedHeight: Int? = null
    private var autoPicked = false

    val selectedQualityHeight: Int? get() = selectedHeight

    fun effectiveSelectedHeight(c: StreamCatalog): Int? {
        downshiftHeight?.let { return it }
        selectedHeight?.let { return it }
        if (autoPicked) return null
        val default = AppPrefs.defaultVideoHeightNow(appContext)
        if (default <= 0) return null
        val candidates = c.videoOnly.ifEmpty { c.muxed }
        return candidates.filter { it.effectiveHeight() <= default }
            .maxByOrNull { it.effectiveHeight() }?.effectiveHeight()
            ?: candidates.minByOrNull { it.effectiveHeight() }?.effectiveHeight()
    }

    val selectedAudioTrackLabel: String?
        get() = selectedAudioTrackId?.let { id ->
            catalogForCurrentEntry()?.audioTracks?.firstOrNull { it.id == id }?.label
        }

    val currentEntry: QueueEntry? get() = queue.getOrNull(queueIndex)
    val hasMedia: Boolean get() = currentEntry != null

    var playbackSpeed: Float = 1.0f
        private set

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    val player: ExoPlayer
        get() {
            check(this::appContext.isInitialized) { "PlaybackCenter.init() not called" }
            return playerRef ?: ExoPlayer.Builder(appContext, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ if (lowRam) 20_000 else 45_000,
                            /* maxBufferMs = */ if (lowRam) 60_000 else 120_000,
                            /* bufferForPlaybackMs = */ 1_000,
                            /* bufferForPlaybackAfterRebufferMs = */ 5_000
                        )
                        .setBackBuffer(/* backBufferDurationMs = */ if (lowRam) 10_000 else 30_000, /* retainBackBufferFromKeyframe = */ true)
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
                .also { built ->
                    playerRef = built
                    built.addListener(playerListener)
                }
        }

    /**
     * True on low-RAM devices (ActivityManager.isLowRamDevice / tiny heap):
     * halves the playback buffer, back-buffer and in-memory stream caches.
     */
    val lowRam: Boolean
        get() = this::appContext.isInitialized && DeviceTuning.lowRam(appContext)

    /**
     * Renderers factory with the "Hardware acceleration" setting applied:
     * ON (default) → normal hardware MediaCodec selection;
     * OFF → software decoders (c2.android.* / OMX.google.*) are preferred so
     * playback still works on devices with broken hardware codecs.
     */
    private val renderersFactory: DefaultRenderersFactory
        get() {
            val factory = DefaultRenderersFactory(appContext)
            if (!AppPrefs.hardwareAcceleration) {
                factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val all = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    val software = all.filter {
                        it.name.startsWith("c2.android") ||
                            it.name.startsWith("OMX.google") ||
                            it.name.contains(".sw.")
                    }
                    software + (all - software.toSet())
                }
            }
            return factory
        }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notify { it.onPlaybackStateChanged(isPlaying) }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            bindAudioEffectsForSession(audioSessionId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) applyRepeatShuffle()
            if (playbackState == Player.STATE_ENDED && mode == Mode.VIDEO &&
                (playlistMode || AppPrefs.videoAutoplayNext)
            ) {
                advance()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mode != Mode.AUDIO) return
            val p = playerRef ?: return
            if (p.mediaItemCount == 0 || mediaItem == null) return
            val index = p.currentMediaItemIndex
            if (index < 0 || index >= queue.size) return
            queueIndex = index
            catalog = null
            if (AppPrefs.crossfadeSeconds > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                startCrossfadeFadeIn(minOf(AppPrefs.crossfadeSeconds * 1000L, 2600L))
            } else if (crossfadeRampJob?.isActive != true) {
                p.volume = 1.0f
            }
            currentEntry?.let { entry ->
                notify { it.onItemChanged(entry) }
                notify { it.onCatalogReady() }
                if (!entry.url.startsWith("file://")) {
                    resolveCatalog(entry)
                } else {
                    resolveJob?.cancel()
                    setResolving(false)
                }
            }
            prefetchAhead()
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlayerFailed(error)
        }

        override fun onRenderedFirstFrame() {
            if (mode != Mode.VIDEO || sourceAppliedAt == 0L) return
            val ms = SystemClock.elapsedRealtime() - sourceAppliedAt
            android.util.Log.i("SparkTubeStartup", "first frame after ${ms}ms (adaptive=$dashActive)")
            sourceAppliedAt = 0L
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notify(block: (Listener) -> Unit) {
        val snapshot = listeners.toList()
        snapshot.forEach(block)
        if (this::appContext.isInitialized) {
            SparkTubeWidgetProvider.refreshAll(appContext)
        }
    }

    private fun ensureService() {
        if (controllerFuture != null) return
        try {
            val token =
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        future.get()
                    } catch (_: Exception) {
                        controllerFuture = null
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        } catch (_: Exception) {
        }
    }

    private fun stopPlayerNow() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        watchdogJob?.cancel()
        crossfadeMonitorJob?.cancel()
        resetVolumeImmediate()
        val prevMode = mode
        mode = Mode.NONE
        playerRef?.run {
            stop()
            clearMediaItems()
        }
        mode = prevMode
    }

    fun playVideo(entry: QueueEntry, extraQueue: List<QueueEntry> = emptyList()) {
        initFromApp()
        stopPlayerNow()
        catalog = null
        mode = Mode.VIDEO
        radioMode = false
        playlistMode = false
        audioOnlyMode = false
        setQueue(listOf(entry) + extraQueue, 0)
        resolveCurrent()
    }

    fun playPlaylist(entries: List<QueueEntry>, startIndex: Int = 0) {
        initFromApp()
        if (entries.isEmpty()) return
        stopPlayerNow()
        catalog = null
        mode = Mode.VIDEO
        radioMode = false
        playlistMode = true
        audioOnlyMode = false
        setQueue(entries, startIndex.coerceIn(0, entries.size - 1))
        resolveCurrent()
    }

    fun playMusic(entry: QueueEntry, radio: Boolean) {
        playMusicQueue(listOf(entry), 0, allowRadio = radio)
    }

    fun playMusicQueue(entries: List<QueueEntry>, startIndex: Int = 0, allowRadio: Boolean = false) {
        initFromApp()
        if (entries.isEmpty()) return
        val start = startIndex.coerceIn(0, entries.size - 1)
        val playlist = if (allowRadio && start > 0) entries.drop(start) else entries
        val initialIdx = if (allowRadio) 0 else start

        stopPlayerNow()
        catalog = null
        mode = Mode.AUDIO
        radioMode = allowRadio
        playlistMode = false
        audioOnlyMode = false
        pendingMusic.clear()
        setQueue(playlist, initialIdx)
        startCrossfadeMonitor()

        val first = currentEntry ?: return
        if (first.url.startsWith("file://")) {
            setResolving(false)
            startMusicPlaylistWithRelated()
            ensureService()
            notify { it.onItemChanged(currentEntry) }
            notify { it.onCatalogReady() }
            return
        }

        setResolving(true)
        resolveJob = scope.launch {
            try {
                val info = YtRepository.streamInfo(first.url)
                if (LiveFilter.isLive(info)) {
                    throw LiveContentException()
                }
                if (currentEntry?.url != first.url) return@launch
                val resolvedCatalog = StreamCatalog(info, first.url)
                catalog = resolvedCatalog
                LocalStore.addToMusicHistory(appContext, first.toVideoEntry())
                RecommendEngine.logPlay(appContext, first.url, first.title, first.uploader)
                primeMusicUri(first, resolvedCatalog)
                startMusicPlaylistWithRelated()
                ensureService()
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                prefetchAhead()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is LiveContentException) {
                    notify { it.onLiveContentBlocked() }
                } else {
                    notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
                }
            } finally {
                setResolving(false)
            }
        }
    }

    /** Reorders an item in the active queue (used by QueueSheet drag-and-drop). */
    fun moveQueueItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val item = queue.removeAt(from)
        queue.add(to, item)
        queueIndex = when (queueIndex) {
            from -> to
            in (from + 1)..to -> queueIndex - 1
            in to until from -> queueIndex + 1
            else -> queueIndex
        }
        if (mode == Mode.AUDIO) {
            playerRef?.let { p ->
                if (from < p.mediaItemCount && to < p.mediaItemCount) {
                    p.moveMediaItem(from, to)
                }
            }
        }
        notify { it.onQueueChanged() }
    }

    /** Removes a non-current item from the active queue (used by QueueSheet swipe-to-remove). */
    fun removeQueueItem(index: Int) {
        if (index !in queue.indices || index == queueIndex) return
        queue.removeAt(index)
        if (index < queueIndex) {
            queueIndex--
        }
        if (mode == Mode.AUDIO) {
            playerRef?.let { p ->
                if (index < p.mediaItemCount) {
                    p.removeMediaItem(index)
                }
            }
        }
        notify { it.onQueueChanged() }
    }

    fun playQueueIndex(index: Int) {
        if (index < 0 || index >= queue.size) return
        resetVolumeImmediate()
        when (mode) {
            Mode.AUDIO -> playerRef?.let { p ->
                if (index < p.mediaItemCount) {
                    queueIndex = index
                    catalog = null
                    p.seekTo(index, 0L)
                    p.playWhenReady = true
                    currentEntry?.let { entry ->
                        notify { it.onItemChanged(entry) }
                        notify { it.onCatalogReady() }
                        if (!entry.url.startsWith("file://")) {
                            resolveCatalog(entry)
                        } else {
                            resolveJob?.cancel()
                            setResolving(false)
                        }
                    }
                }
            }
            Mode.VIDEO -> {
                queueIndex = index
                resolveCurrent()
            }
            Mode.NONE -> Unit
        }
    }

    fun togglePlayPause() {
        val p = playerRef ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        resetVolumeImmediate()
        playerRef?.seekTo(positionMs.coerceIn(0, durationMs))
    }

    val positionMs: Long get() = playerRef?.currentPosition?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() = playerRef?.duration?.takeIf { it > 0 } ?: 0L
    val isPlaying: Boolean get() = playerRef?.isPlaying ?: false
    val isBuffering: Boolean get() = playerRef?.playbackState == Player.STATE_BUFFERING

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        playerRef?.setPlaybackSpeed(speed)
    }

    fun applyMusicAutoplay() {
        playerRef?.setPauseAtEndOfMediaItems(!AppPrefs.musicAutoplayNext)
    }

    /** Pushes the saved repeat / shuffle choice onto the player. */
    fun applyRepeatShuffle() {
        val audio = mode == Mode.AUDIO
        playerRef?.run {
            val wantRepeat = if (audio) {
                when (AppPrefs.musicRepeatMode) {
                    1 -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    2 -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            } else androidx.media3.common.Player.REPEAT_MODE_OFF // video uses its own queue logic
            val wantShuffle = audio && AppPrefs.musicShuffle
            if (repeatMode != wantRepeat) repeatMode = wantRepeat
            if (shuffleModeEnabled != wantShuffle) shuffleModeEnabled = wantShuffle
        }
    }

    /** off -> all -> one -> off. @return the new mode (0/1/2). */
    fun cycleRepeatMode(): Int {
        val next = (AppPrefs.musicRepeatMode + 1) % 3
        AppPrefs.musicRepeatMode = next
        applyRepeatShuffle()
        return next
    }

    fun toggleShuffle(): Boolean {
        val on = !AppPrefs.musicShuffle
        AppPrefs.musicShuffle = on
        applyRepeatShuffle()
        return on
    }

    fun next() {
        resetVolumeImmediate()
        when (mode) {
            Mode.AUDIO -> playerRef?.run {
                if (hasNextMediaItem()) seekToNextMediaItem()
            }
            Mode.VIDEO -> if (queueIndex < queue.size - 1) {
                queueIndex++
                resolveCurrent()
            }
            Mode.NONE -> Unit
        }
    }

    fun previous() {
        resetVolumeImmediate()
        when (mode) {
            Mode.AUDIO -> playerRef?.run {
                if (hasPreviousMediaItem()) seekToPreviousMediaItem()
                else seekTo(0)
            }
            Mode.VIDEO -> if (queueIndex > 0) {
                queueIndex--
                resolveCurrent()
            } else {
                seekTo(0)
            }
            Mode.NONE -> Unit
        }
    }

    fun stopPlayback() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        sleepTimerJob?.cancel()
        crossfadeMonitorJob?.cancel()
        resetVolumeImmediate()
        sleepTimerEndRealtimeMs = 0L
        pendingMusic.clear()
        val prevMode = mode
        mode = Mode.NONE
        playerRef?.run {
            stop()
            clearMediaItems()
        }
        playlistMode = false
        queue.clear()
        queueIndex = -1
        catalog = null
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(null) }
        notify { it.onPlaybackStateChanged(false) }
    }

    fun setVideoQuality(height: Int?) {
        if (height == -1) {
            audioOnlyMode = true
            selectedHeight = -1
            autoPicked = false
        } else {
            audioOnlyMode = false
            selectedHeight = height
            autoPicked = height == null
        }
        usedFallback = false
        watchdogJob?.cancel()
        downshiftHeight = null
        rebuildSource(keepPosition = true)
    }

    fun setAudioTrack(trackId: String) {
        if (catalogForCurrentEntry()?.audioTracks?.none { it.id == trackId } == true) return
        selectedAudioTrackId = trackId
        usedFallback = false
        rebuildSource(keepPosition = true)
        notify { it.onCatalogReady() }
    }

    fun switchToAudioOnly() {
        audioOnlyMode = true
        rebuildSource(keepPosition = true)
        notify { it.onAudioOnlyChanged(true) }
    }

    fun switchBackToVideo() {
        if (mode != Mode.VIDEO) return
        audioOnlyMode = false
        resetVolumeImmediate()
        val entry = currentEntry ?: return
        if (entry.url.startsWith("file://")) {
            val local = DownloadCenter.recordsFor(appContext, entry.url)
                .firstOrNull { it.status == DownloadCenter.STATUS_DONE }
            if (local != null) {
                val pos = positionMs
                startLocalFiles(local, pos)
            }
        } else {
            rebuildSource(keepPosition = true)
        }
        notify { it.onAudioOnlyChanged(false) }
    }

    fun switchToAudioPlayerMode() {
        initFromApp()
        val entry = currentEntry ?: return
        val savedPos = positionMs
        val wasPlaying = playerRef?.isPlaying ?: true
        val existingCatalog = catalogForCurrentEntry()

        watchdogJob?.cancel()
        resetVolumeImmediate()
        mode = Mode.AUDIO
        audioOnlyMode = true
        startCrossfadeMonitor()

        // Offline local file (or AV pair): switch to audio player at exact timestamp
        if (entry.url.startsWith("file://")) {
            val local = DownloadCenter.recordsFor(appContext, entry.url)
                .firstOrNull { it.status == DownloadCenter.STATUS_DONE }
            val audioPath = when {
                local != null && local.type == DownloadCenter.TYPE_AV && local.filePaths.size >= 2 ->
                    local.filePaths.last()
                local != null && local.filePaths.isNotEmpty() ->
                    local.filePaths.first()
                else -> entry.url.removePrefix("file://")
            }
            val file = File(audioPath)
            if (file.exists()) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.uploader)
                    .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
                val item = MediaItem.Builder()
                    .setMediaId(entry.url)
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(metadata)
                    .build()
                player.run {
                    volume = 1.0f
                    setMediaItems(listOf(item), 0, savedPos)
                    prepare()
                    playWhenReady = wasPlaying
                    setPlaybackSpeed(playbackSpeed)
                }
                ensureService()
                notify { it.onAudioOnlyChanged(true) }
                notify { it.onItemChanged(entry) }
                return
            }
        }

        if (existingCatalog != null) {
            primeMusicUri(entry, existingCatalog)
            val source = buildSource(entry, existingCatalog)
            if (source != null) {
                player.run {
                    setMediaSource(source, savedPos)
                    prepare()
                    playWhenReady = wasPlaying
                    setPlaybackSpeed(playbackSpeed)
                }
                ensureService()
                notify { it.onAudioOnlyChanged(true) }
                notify { it.onItemChanged(entry) }
                return
            }
        }
        playMusic(entry.copy(isMusic = true), radio = true)
    }

    fun switchToVideoPlayerMode() {
        initFromApp()
        val entry = currentEntry ?: return
        val savedPos = positionMs
        val wasPlaying = playerRef?.isPlaying ?: true
        val existingCatalog = catalogForCurrentEntry()

        crossfadeMonitorJob?.cancel()
        resetVolumeImmediate()
        mode = Mode.VIDEO
        audioOnlyMode = false
        radioMode = false
        if (selectedHeight == -1) selectedHeight = null

        // Check if we have a local video/AV download for this item
        val localVid = localVideoRecord(entry.url)
        if (localVid != null && startLocalFiles(localVid, savedPos)) {
            player.playWhenReady = wasPlaying
            ensureService()
            notify { it.onAudioOnlyChanged(false) }
            notify { it.onItemChanged(entry) }
            notify { it.onCatalogReady() }
            return
        }

        if (existingCatalog != null && existingCatalog.hasVideo) {
            val source = buildSource(entry, existingCatalog)
            if (source != null) {
                sourceAppliedAt = SystemClock.elapsedRealtime()
                player.run {
                    setMediaSource(source, savedPos)
                    prepare()
                    playWhenReady = wasPlaying
                    setPlaybackSpeed(playbackSpeed)
                }
                startStartupWatchdog()
                ensureService()
                notify { it.onAudioOnlyChanged(false) }
                notify { it.onItemChanged(entry) }
                notify { it.onCatalogReady() }
                return
            }
        }

        // If switching from an offline audio file to Video mode and no local video file exists,
        // reconstruct the online YouTube URL from DownloadRecord.sourceUrl or videoId
        val targetUrl = if (entry.url.startsWith("file://")) {
            val rec = DownloadCenter.recordsFor(appContext, entry.url).firstOrNull()
            rec?.sourceUrl?.takeIf { it.startsWith("http") }
                ?: DownloadCenter.videoIdOf(entry.url).takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
                ?: entry.url
        } else {
            entry.url
        }
        playVideo(entry.copy(url = targetUrl, isMusic = false))
    }

    fun toggleFavoriteCurrent(): Boolean {
        val entry = currentEntry ?: return false
        val nowFavorite = LocalStore.toggleFavorite(appContext, entry.toVideoEntry())
        notify { it.onFavoriteChanged(entry.url, nowFavorite) }
        return nowFavorite
    }

    fun isCurrentFavorite(): Boolean {
        val entry = currentEntry ?: return false
        return LocalStore.isFavorite(appContext, entry.url)
    }

    fun playDownload(record: DownloadRecord) {
        initFromApp()
        if (record.filePaths.isEmpty()) return
        if (record.status != DownloadCenter.STATUS_DONE) {
            notify { it.onNotice("Download is still in progress") }
            return
        }
        val validFiles = record.filePaths.filter { p -> File(p).let { it.exists() && it.length() > 0L } }
        if (validFiles.isEmpty()) {
            notify { it.onError("Downloaded file is missing") }
            return
        }

        if (record.type == DownloadCenter.TYPE_AUDIO) {
            val audioEntry = QueueEntry(
                url = "file://${validFiles.first()}",
                title = record.title,
                uploader = record.uploader,
                thumbnailUrl = record.thumbnailUrl,
                durationSec = record.durationSec,
                isMusic = true
            )
            // Radio auto-builds the music playlist after the local song.
            playMusicQueue(listOf(audioEntry), 0, allowRadio = true)
            return
        }

        stopPlayerNow()
        catalog = null
        mode = Mode.VIDEO
        audioOnlyMode = false
        radioMode = false
        playlistMode = false
        pendingMusic.clear()
        queue.clear()
        queue.add(
            QueueEntry(
                url = "file://${validFiles.first()}",
                title = record.title,
                uploader = record.uploader,
                thumbnailUrl = record.thumbnailUrl,
                durationSec = record.durationSec,
                isMusic = false
            )
        )
        queueIndex = 0
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }

        if (!startLocalFiles(record, 0L)) {
            notify { it.onError("Downloaded file is missing") }
            return
        }
        setResolving(false)
        notify { it.onCatalogReady() }
        ensureService()
    }

    private fun startLocalFiles(record: DownloadRecord, startPositionMs: Long = 0L): Boolean {
        val entry = currentEntry
        val metadata = MediaMetadata.Builder()
            .setTitle(entry?.title ?: record.title)
            .setArtist(entry?.uploader ?: record.uploader)
            .setArtworkUri((entry?.thumbnailUrl ?: record.thumbnailUrl).takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        val sources = record.filePaths
            .filter { p -> File(p).let { it.exists() && it.length() > 0L } }
            .mapIndexed { idx, path ->
                val itemBuilder = MediaItem.Builder()
                    .setMediaId("file://$path")
                    .setUri(Uri.fromFile(File(path)))
                if (idx == 0) itemBuilder.setMediaMetadata(metadata)
                progressiveSource(itemBuilder.build())
            }
        if (sources.isEmpty()) return false
        val source = if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
        player.run {
            volume = 1.0f
            setMediaSource(source, startPositionMs.coerceAtLeast(0L))
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
        }
        return true
    }

    private fun localVideoRecord(url: String): DownloadRecord? =
        DownloadCenter.recordsFor(appContext, url).firstOrNull { record ->
            record.status == DownloadCenter.STATUS_DONE &&
                record.type != DownloadCenter.TYPE_AUDIO &&
                record.filePaths.isNotEmpty() &&
                record.filePaths.all { p -> File(p).let { it.exists() && it.length() > 0L } }
        }

    private fun localAudioRecord(url: String): DownloadRecord? =
        DownloadCenter.recordsFor(appContext, url).firstOrNull { record ->
            record.status == DownloadCenter.STATUS_DONE &&
                record.type == DownloadCenter.TYPE_AUDIO &&
                record.filePaths.isNotEmpty() &&
                record.filePaths.all { p -> File(p).let { it.exists() && it.length() > 0L } }
        }

    private fun setQueue(newQueue: List<QueueEntry>, startIndex: Int) {
        queue.clear()
        queue.addAll(newQueue)
        queueIndex = startIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }
    }

    private fun initFromApp() {
        if (this::appContext.isInitialized) return
        throw IllegalStateException("PlaybackCenter.init(context) must be called in Application.onCreate()")
    }

    private fun resolveCurrent() {
        val entry = currentEntry ?: return
        resolveJob?.cancel()
        watchdogJob?.cancel()
        downshiftHeight = null
        dashBlocked = false
        dashActive = false
        catalog = null
        notify { it.onItemChanged(currentEntry) }

        // Handle local file:// URLs or already-downloaded offline videos first
        val local = localVideoRecord(entry.url)
            ?: if (entry.url.startsWith("file://")) {
                DownloadCenter.recordsFor(appContext, entry.url)
                    .firstOrNull { it.status == DownloadCenter.STATUS_DONE }
            } else null

        if (entry.url.startsWith("file://")) {
            setResolving(false)
            if (local != null && startLocalFiles(local, 0L)) {
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                ensureService()
            } else {
                val directFile = File(entry.url.removePrefix("file://"))
                if (directFile.exists() && directFile.length() > 0L) {
                    val tempRecord = DownloadRecord(
                        videoId = DownloadCenter.videoIdOf(entry.url),
                        title = entry.title,
                        uploader = entry.uploader,
                        thumbnailUrl = entry.thumbnailUrl,
                        type = DownloadCenter.TYPE_VIDEO,
                        quality = "Offline",
                        status = DownloadCenter.STATUS_DONE,
                        filePaths = listOf(directFile.absolutePath),
                        downloadIds = emptyList(),
                        durationSec = entry.durationSec
                    )
                    startLocalFiles(tempRecord, 0L)
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                    ensureService()
                } else {
                    notify { it.onError("Downloaded file is missing") }
                }
            }
            return
        }

        usedSignedInRetry = false
        setResolving(true)
        resolveJob = scope.launch {
            try {
                if (local != null && startLocalFiles(local, 0L)) {
                    setResolving(false)
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                    ensureService()
                    // Best-effort catalog fetch in background if online
                    runCatching {
                        val info = YtRepository.streamInfo(entry.url)
                        if (currentEntry?.url == entry.url && !LiveFilter.isLive(info)) {
                            catalog = StreamCatalog(info, entry.url)
                            notify { it.onCatalogReady() }
                        }
                    }
                    return@launch
                }
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw LiveContentException()
                }
                if (currentEntry?.url != entry.url) return@launch
                catalog = StreamCatalog(info, entry.url)
                usedFallback = false
                LocalStore.addToHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                applySources(playbackSpeed)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                maybeFetchRelatedFallback()
                extendQueueFromRelated()
                ensureService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // v1.7.2 age-gate wiring: if the video is age-restricted /
                // login-gated and the user IS signed in with Google, force the
                // signed-in unlock engine (WEB/ANDROID + session cookies +
                // SAPISIDHASH) and retry the resolve exactly once.
                val msg = (e.message ?: "").lowercase()
                val ageGated = e is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ||
                    e is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ||
                    msg.contains("age") || msg.contains("sign in") ||
                    msg.contains("logged") || msg.contains("log in") ||
                    msg.contains("confirm your age") || msg.contains("login")
                if (ageGated && !entry.url.startsWith("file://") &&
                    YouTubeAuth.isLoggedIn() && !usedSignedInRetry
                ) {
                    usedSignedInRetry = true
                    com.sparktube.app.net.YouTubePlayerUnlock.forceUnlockForUrl(entry.url)
                    catalog = null
                    try {
                        val info = YtRepository.streamInfo(entry.url)
                        if (LiveFilter.isLive(info)) throw LiveContentException()
                        if (currentEntry?.url != entry.url) return@launch
                        catalog = StreamCatalog(info, entry.url)
                        LocalStore.addToHistory(appContext, entry.toVideoEntry())
                        RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                        applySources(playbackSpeed)
                        notify { it.onItemChanged(currentEntry) }
                        notify { it.onCatalogReady() }
                        maybeFetchRelatedFallback()
                        extendQueueFromRelated()
                        ensureService()
                        return@launch
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        notify { it.onError(if (e2.message.isNullOrBlank()) e.javaClass.simpleName else e2.message!!) }
                        return@launch
                    }
                }
                if (!skipBrokenPlaylistItem()) {
                    if (e is LiveContentException) {
                        notify { it.onLiveContentBlocked() }
                    } else {
                        notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
                    }
                }
            } finally {
                setResolving(false)
            }
        }
    }

    private fun setResolving(resolving: Boolean) {
        isResolving = resolving
        notify { it.onResolvingChanged(resolving) }
    }

    private fun skipBrokenPlaylistItem(): Boolean {
        if (!playlistMode || mode != Mode.VIDEO || queueIndex >= queue.size - 1) return false
        val failedIndex = queueIndex
        notify { it.onNotice("This video can't be played — skipping to the next one") }
        scope.launch(Dispatchers.Main) {
            if (mode == Mode.VIDEO && playlistMode && queueIndex == failedIndex) next()
        }
        return true
    }

    private fun resolveCatalog(entry: QueueEntry) {
        resolveJob?.cancel()
        catalog = null
        if (entry.url.startsWith("file://")) {
            setResolving(false)
            notify { it.onCatalogReady() }
            return
        }
        setResolving(true)
        resolveJob = scope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw LiveContentException()
                }
                if (currentEntry?.url != entry.url) return@launch
                val resolvedCatalog = StreamCatalog(info, entry.url)
                catalog = resolvedCatalog
                LocalStore.addToMusicHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                primeMusicUri(entry, resolvedCatalog)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                maybeFetchRelatedFallback()
                if (radioMode) {
                    extendRadioFromRelated()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is LiveContentException) {
                    notify { it.onLiveContentBlocked() }
                } else {
                    notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
                }
            } finally {
                setResolving(false)
            }
        }
    }

    /**
     * YouTube sometimes returns no "related / up next" block (new layout,
     * signed-in session, shorts-heavy pages). When that happens, fetch a
     * search-based on-topic replacement and refresh the UI — used by BOTH the
     * video watch path and the audio/radio path so the up-next list never
     * depends on toggling audio/video mode.
     */
    private fun maybeFetchRelatedFallback() {
        val entry = currentEntry ?: return
        val resolved = catalogForCurrentEntry() ?: return
        if (resolved.related.isNotEmpty() || entry.url.startsWith("file://")) return
        scope.launch {
            val fallback = runCatching {
                YtRepository.relatedFallback(entry.title, entry.uploader, entry.url)
            }.getOrDefault(emptyList())
            if (fallback.isNotEmpty() && catalog === resolved && currentEntry?.url == entry.url) {
                resolved.related = fallback
                notify { it.onCatalogReady() }
                if (radioMode && mode == Mode.AUDIO) {
                    extendRadioFromRelated()
                }
            }
        }
    }

    private fun extendQueueFromRelated() {
        if (playlistMode) return
        val rel = catalogForCurrentEntry()?.related ?: return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url !in known }.take(20)
            .map { it.toQueueEntry(isMusic = mode == Mode.AUDIO) }
        if (additions.isEmpty()) return
        queue.addAll(additions)
        notify { it.onQueueChanged() }
    }

    /** Strict Music-Only radio extension: filters out any non-music items via `LiveFilter.isMusicTrack`. */
    private fun extendRadioFromRelated() {
        val rel = catalogForCurrentEntry()?.related ?: return
        val idx = playerRef?.currentMediaItemIndex ?: queueIndex
        val remaining = (queue.size - idx - 1).coerceAtLeast(0)
        if (remaining >= RADIO_REFILL_BELOW) return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url.isNotBlank() && it.url !in known && LiveFilter.isMusicTrack(it) }
            .take(RADIO_BATCH)
            .map { it.toQueueEntry(isMusic = true) }
        appendRadioEntries(additions)
        if (additions.size + remaining < RADIO_MIN_UPCOMING) {
            topUpRadioFromSearch(currentEntry)
        }
    }

    private fun appendRadioEntries(additions: List<QueueEntry>) {
        if (additions.isEmpty()) return
        val known = queue.map { it.url }.toHashSet()
        val fresh = additions.filter { known.add(it.url) }
        if (fresh.isEmpty()) return
        queue.addAll(fresh)
        playerRef?.addMediaItems(fresh.map { musicMediaItem(it) })
        notify { it.onQueueChanged() }
    }

    private fun topUpRadioFromSearch(seed: QueueEntry?) {
        seed ?: return
        val query = seed.uploader.removeSuffix(" - Topic").ifBlank { seed.title }
        if (query.isBlank()) return
        scope.launch {
            try {
                val result = YtRepository.searchMusic(query, null)
                if (!radioMode || currentEntry?.url != seed.url) return@launch
                val known = queue.map { it.url }.toSet()
                val more = result.items
                    .filter { it.url.isNotBlank() && it.url !in known && LiveFilter.isMusicTrack(it) }
                    .take(RADIO_BATCH)
                    .map { it.toQueueEntry(isMusic = true) }
                appendRadioEntries(more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun advance() {
        if (queueIndex < queue.size - 1) {
            next()
        }
    }

    private fun onPlayerFailed(error: PlaybackException) {
        val isLive = generateSequence(error as Throwable) { it.cause }
            .any { it is LiveContentException }
        fun notifyFailure() {
            if (isLive) notify { it.onLiveContentBlocked() }
            else notify { it.onError("Playback error: ${error.errorCodeName}") }
        }
        if (mode == Mode.AUDIO) {
            val isOfflineItem = currentEntry?.url?.startsWith("file://") == true
            if (isOfflineItem) {
                notify { it.onNotice("Downloaded audio file could not be played") }
            }
            playerRef?.run {
                if (hasNextMediaItem()) {
                    seekToNextMediaItem()
                    val newIdx = currentMediaItemIndex
                    if (newIdx in queue.indices) {
                        queueIndex = newIdx
                        catalog = null
                        notify { it.onItemChanged(currentEntry) }
                        notify { it.onCatalogReady() }
                    }
                    return
                }
            }
            notifyFailure()
            return
        }
        val c = catalogForCurrentEntry() ?: run {
            if (!skipBrokenPlaylistItem()) {
                notifyFailure()
            }
            return
        }
        if (dashActive) {
            dashBlocked = true
            android.util.Log.w("SparkTubeStartup", "adaptive source failed: ${error.errorCodeName}")
            rebuildSource(keepPosition = true)
            return
        }
        if (selectedAudioTrackId != null && !usedFallback) {
            selectedAudioTrackId = null
            notify { it.onNotice("Selected audio track unavailable - using the default track") }
            rebuildSource(keepPosition = true)
            notify { it.onCatalogReady() }
            return
        }
        if (!usedFallback && c.muxed.isNotEmpty()) {
            usedFallback = true
            val hadTrackChoice = selectedAudioTrackId != null
            selectedAudioTrackId = null
            if (hadTrackChoice) {
                notify { it.onNotice("Playing a lower-quality stream with the default audio track") }
            }
            rebuildSource(keepPosition = true)
            notify { it.onCatalogReady() }
            return
        }
        if (skipBrokenPlaylistItem()) return
        notifyFailure()
    }

    private fun applySources(speed: Float) {
        val entry = currentEntry ?: return
        val c = catalogForCurrentEntry() ?: return
        val source = buildSource(entry, c) ?: run {
            notify { it.onError("No playable streams found") }
            return
        }
        sourceAppliedAt = SystemClock.elapsedRealtime()
        player.run {
            volume = 1.0f
            setMediaSource(source, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(speed)
        }
        startStartupWatchdog()
    }

    private fun startStartupWatchdog() {
        if (mode != Mode.VIDEO || audioOnlyMode) return
        watchdogJob?.cancel()
        watchdogDownshifts = 0
        watchdogJob = scope.launch {
            var preparedAt = SystemClock.elapsedRealtime()
            var lastPollAt = preparedAt
            var readyAt = 0L
            var healthyMs = 0L
            var stallSince = 0L
            while (isActive) {
                delay(400)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastPollAt).coerceAtMost(1_000)
                lastPollAt = now
                val p = playerRef ?: break
                when (p.playbackState) {
                    Player.STATE_IDLE, Player.STATE_ENDED -> return@launch
                    Player.STATE_READY -> {
                        if (readyAt == 0L) readyAt = now
                        if (p.isPlaying) {
                            stallSince = 0L
                            healthyMs += dt
                            if (healthyMs >= 15_000) return@launch
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (stallSince == 0L) stallSince = now
                        val startup = readyAt == 0L
                        val waited = now - if (startup) preparedAt else stallSince
                        val limit = if (startup) STARTUP_LIMIT_MS
                        else if (dashActive) STALL_LIMIT_MS * 3 else STALL_LIMIT_MS
                        if (waited >= limit) {
                            if (dashActive) {
                                dashBlocked = true
                                rebuildSource(keepPosition = !startup)
                                preparedAt = SystemClock.elapsedRealtime()
                                readyAt = 0L
                                healthyMs = 0L
                                stallSince = 0L
                                continue
                            }
                            if (!downshiftOneTier(keepPosition = !startup)) return@launch
                            preparedAt = SystemClock.elapsedRealtime()
                            readyAt = 0L
                            healthyMs = 0L
                            stallSince = 0L
                        }
                    }
                }
            }
        }
    }

    private fun downshiftOneTier(keepPosition: Boolean): Boolean {
        if (watchdogDownshifts >= 2) return false
        val c = catalogForCurrentEntry() ?: return false
        val current = effectiveSelectedHeight(c) ?: run {
            c.videoOnly.firstOrNull()?.effectiveHeight() ?: c.muxed.firstOrNull()?.effectiveHeight()
        } ?: return false
        val lower = (c.videoOnly.map { it.effectiveHeight() } +
            c.muxed.map { it.effectiveHeight() })
            .distinct()
            .filter { it < current }
            .sortedDescending()
        if (lower.isEmpty()) return false
        val target = lower.first()
        downshiftHeight = target
        usedFallback = false
        watchdogDownshifts++
        rebuildSource(keepPosition = keepPosition)
        notify { it.onNotice("Slow connection — switching to ${target}p") }
        return true
    }

    private fun rebuildSource(keepPosition: Boolean) {
        val entry = currentEntry ?: return
        val c = catalogForCurrentEntry() ?: return
        val position = if (keepPosition) positionMs else 0L
        val wasPlaying = playerRef?.isPlaying ?: true
        val source = buildSource(entry, c) ?: return
        sourceAppliedAt = SystemClock.elapsedRealtime()
        player.run {
            volume = 1.0f
            setMediaSource(source, position)
            prepare()
            if (wasPlaying || !keepPosition) playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
        }
    }

    private fun buildSource(entry: QueueEntry, c: StreamCatalog): MediaSource? {
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.uploader)
            .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()

        if (mode == Mode.AUDIO || audioOnlyMode) {
            val audio = if (mode == Mode.AUDIO) {
                pickMusicAudio(c)
            } else {
                audioForSelection(c) ?: c.audioTracks.firstOrNull()?.best
            }
            if (audio == null && c.muxed.isNotEmpty()) {
                return progressiveSource(MediaItem.Builder().setUri(audioUri(c.muxed.first())).setMediaMetadata(metadata).build())
            }
            if (audio != null) {
                return progressiveSource(MediaItem.Builder().setUri(audioUri(audio)).setMediaMetadata(metadata).build())
            }
            return null
        }

        val audio = audioForSelection(c)

        if (adaptiveEligible()) {
            val capHeight = if (autoPicked) 0 else AppPrefs.defaultVideoHeightNow(appContext)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("https://sparktube.invalid/manifest.mpd"))
                .setMediaMetadata(metadata)
                .build()
            val dash = DashSources.create(dataSourceFactory, mediaItem, c.allVideoOnly, audio, c.durationSec)
            if (dash != null) {
                applyVideoCap(capHeight)
                dashActive = true
                return dash
            }
        }
        dashActive = false
        applyVideoCap(0)
        val video = videoForSelection(c)

        if (video != null && video.isVideoOnly && audio != null) {
            val videoItem = MediaItem.Builder().setUri(audioUri(video)).setMediaMetadata(metadata).build()
            val audioItem = MediaItem.Builder().setUri(audioUri(audio)).build()
            return MergingMediaSource(
                progressiveSource(videoItem),
                progressiveSource(audioItem)
            )
        }
        if (video != null) {
            return progressiveSource(MediaItem.Builder().setUri(audioUri(video)).setMediaMetadata(metadata).build())
        }
        if (audio != null) {
            return progressiveSource(MediaItem.Builder().setUri(audioUri(audio)).setMediaMetadata(metadata).build())
        }
        return null
    }

    private fun adaptiveEligible(): Boolean =
        AppPrefs.smartStreaming && !dashBlocked && !usedFallback &&
            selectedHeight == null && downshiftHeight == null

    private fun applyVideoCap(capHeight: Int) {
        val p = playerRef ?: player
        val builder = p.trackSelectionParameters.buildUpon()
        if (capHeight > 0) builder.setMaxVideoSize(Int.MAX_VALUE, capHeight)
        else builder.clearVideoSizeConstraints()
        p.trackSelectionParameters = builder.build()
    }

    private fun videoForSelection(c: StreamCatalog): VideoStream? {
        if (usedFallback && c.muxed.isNotEmpty()) return c.muxed.first()
        val height = downshiftHeight ?: selectedHeight
        return when {
            height == null -> {
                val default = AppPrefs.defaultVideoHeightNow(appContext)
                if (default > 0 && !autoPicked) {
                    val candidates = c.videoOnly.ifEmpty { c.muxed }
                    candidates.filter { it.effectiveHeight() <= default }
                        .maxByOrNull { it.effectiveHeight() }
                        ?: candidates.minByOrNull { it.effectiveHeight() }
                } else {
                    c.videoOnly.firstOrNull() ?: c.muxed.firstOrNull()
                }
            }
            c.videoOnly.any { it.effectiveHeight() == height } ->
                c.videoOnly.first { it.effectiveHeight() == height }
            c.muxed.any { it.effectiveHeight() == height } ->
                c.muxed.first { it.effectiveHeight() == height }
            else -> c.videoOnly.firstOrNull() ?: c.muxed.firstOrNull()
        }
    }

    private fun audioForSelection(c: StreamCatalog): AudioStream? {
        val selected = selectedAudioTrackId
        return when {
            selected != null -> c.audioTracks.firstOrNull { it.id == selected }?.best
                ?: c.audioTracks.firstOrNull()?.best
            else -> c.audioTracks.firstOrNull()?.best
        }
    }

    private fun pickMusicAudio(c: StreamCatalog): AudioStream? {
        val group = c.audioTracks.firstOrNull() ?: return null
        val allStreams = group.streams.filter { it.isUrl && it.averageBitrate > 0 }
        if (allStreams.isEmpty()) return group.best
        // Prefer M4A/MP4 AAC streams when available for universal container seek compatibility
        val m4aStreams = allStreams.filter {
            it.format?.mimeType?.contains("mp4", ignoreCase = true) == true ||
                it.format?.suffix.equals("m4a", ignoreCase = true)
        }
        val streams = m4aStreams.ifEmpty { allStreams }
        return when (AppPrefs.musicAudioQuality) {
            AppPrefs.AUDIO_QUALITY_LOW -> streams.last()
            AppPrefs.AUDIO_QUALITY_MEDIUM -> streams[streams.size / 2]
            else -> streams.first()
        }
    }

    private fun audioUri(stream: AudioStream): Uri = Uri.parse(stream.content)
    private fun audioUri(stream: VideoStream): Uri = Uri.parse(stream.content)

    private fun progressiveSource(item: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)

    /** Strict Music-Only radio startup: only appends related items that pass `LiveFilter.isMusicTrack`. */
    private fun startMusicPlaylistWithRelated() {
        val first = currentEntry ?: return
        val items: MutableList<MediaItem>
        val startIdx: Int

        if (radioMode) {
            items = mutableListOf(musicMediaItem(first))
            startIdx = 0
            catalogForCurrentEntry()?.related
                ?.filter { it.url.isNotBlank() && it.url != first.url && LiveFilter.isMusicTrack(it) }
                ?.take(RADIO_FIRST_BATCH)
                ?.forEach { rel ->
                    val qe = rel.toQueueEntry(isMusic = true)
                    if (queue.none { it.url == qe.url }) {
                        queue.add(qe)
                        items.add(musicMediaItem(qe))
                    }
                }
            notify { it.onQueueChanged() }
        } else {
            items = queue.map { musicMediaItem(it) }.toMutableList()
            startIdx = queueIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }

        player.run {
            volume = 1.0f
            setMediaItems(items, startIdx, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
            applyMusicAutoplay()
            applyRepeatShuffle()
        }
        if (radioMode && items.size < RADIO_MIN_UPCOMING) {
            topUpRadioFromSearch(first)
        }
    }

    private fun musicMediaItem(entry: QueueEntry): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.uploader)
            .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        if (entry.url.startsWith("file://")) {
            val file = File(entry.url.removePrefix("file://"))
            return MediaItem.Builder()
                .setMediaId(entry.url)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metadata)
                .build()
        }
        // If this online track already has a completed offline audio download on disk, use it directly
        val offlineAudio = localAudioRecord(entry.url)
        if (offlineAudio != null) {
            val localFile = File(offlineAudio.filePaths.first())
            if (localFile.exists() && localFile.length() > 0L) {
                return MediaItem.Builder()
                    .setMediaId(entry.url)
                    .setUri(Uri.fromFile(localFile))
                    .setMediaMetadata(metadata)
                    .build()
            }
        }
        val key = "music-${musicItemId.incrementAndGet()}"
        if (pendingMusic.size > (if (lowRam) 96 else 256)) pendingMusic.clear()
        pendingMusic[key] = entry
        return MediaItem.Builder()
            .setMediaId(entry.url)
            .setUri("sparktube://queue/$key")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun resolveMusicUri(key: String): Uri {
        val entry = pendingMusic[key]
            ?: throw IOException("Playlist item vanished: $key")
        musicUriCache[entry.url]?.let { return it }
        val info = runBlocking { YtRepository.streamInfo(entry.url) }
        if (LiveFilter.isLive(info)) {
            throw LiveContentException()
        }
        val c = StreamCatalog(info, entry.url)
        val audio = pickMusicAudio(c)
            ?: throw IOException("No audio stream found")
        return cacheMusicUri(entry.url, audio.content)
    }

    private fun cacheMusicUri(url: String, content: String): Uri {
        if (musicUriCache.size > (if (lowRam) 24 else 64)) musicUriCache.clear()
        val uri = Uri.parse(content)
        musicUriCache[url] = uri
        return uri
    }

    private fun primeMusicUri(entry: QueueEntry, verifiedCatalog: StreamCatalog? = catalogForCurrentEntry()) {
        val c = verifiedCatalog ?: return
        if (c.videoId != DownloadCenter.videoIdOf(entry.url)) return
        val best = pickMusicAudio(c) ?: return
        cacheMusicUri(entry.url, best.content)
    }

    private fun prefetchAhead() {
        if (mode != Mode.AUDIO) return
        val idx = playerRef?.currentMediaItemIndex ?: queueIndex
        val targets = ((idx + 1) until minOf(idx + 3, queue.size))
            .mapNotNull { queue.getOrNull(it) }
            .filter { !it.url.startsWith("file://") && !musicUriCache.containsKey(it.url) }
        if (targets.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            targets.forEach { entry ->
                runCatching {
                    val info = YtRepository.streamInfo(entry.url)
                    val c = StreamCatalog(info, entry.url)
                    if (c.videoId == DownloadCenter.videoIdOf(entry.url)) {
                        pickMusicAudio(c)?.let { audio ->
                            cacheMusicUri(entry.url, audio.content)
                        }
                    }
                }
            }
        }
    }

    val dataSourceFactory: DefaultDataSource.Factory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(OkHttpDownloader.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "sparktube") {
                val key = "${uri.host ?: ""}${uri.path.orEmpty()}".removePrefix("//")
                    .removePrefix("queue/")
                forceInitialRangeHeader(dataSpec.buildUpon().setUri(resolveMusicUri(key)).build())
            } else {
                forceInitialRangeHeader(dataSpec)
            }
        }
        val parallel = ParallelRangeDataSource.Factory(resolving)
        DefaultDataSource.Factory(appContext, parallel)
    }

    fun retryCurrent() {
        val entry = currentEntry ?: return
        YtRepository.invalidateStreamCache(entry.url)
        YouTubePlayerUnlock.forceUnlockForUrl(entry.url)
        musicUriCache.remove(entry.url)
        when (mode) {
            Mode.AUDIO -> playMusicQueue(queue.toList(), queueIndex.coerceAtLeast(0), allowRadio = radioMode)
            Mode.VIDEO, Mode.NONE -> {
                mode = Mode.VIDEO
                resolveCurrent()
            }
        }
    }

    private fun forceInitialRangeHeader(spec: DataSpec): DataSpec {
        val scheme = spec.uri.scheme
        if (scheme != "http" && scheme != "https") return spec
        val headers = HashMap(spec.httpRequestHeaders)
        YouTubeAuth.streamHeadersForUrl(spec.uri.toString()).forEach { (k, v) ->
            if (!headers.containsKey(k)) {
                headers[k] = v
            }
        }
        if (spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong() && !headers.containsKey("Range")) {
            headers["Range"] = "bytes=0-"
        }
        return spec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    @OptIn(UnstableApi::class)
    private class QueueForwardingPlayer(player: ExoPlayer) :
        ForwardingPlayer(player) {

        private fun queueHasNext(): Boolean = when (PlaybackCenter.mode) {
            Mode.VIDEO, Mode.AUDIO ->
                PlaybackCenter.queueIndex < PlaybackCenter.queue.size - 1
            Mode.NONE -> false
        }

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                queueHasNext() || super.isCommandAvailable(command)
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean =
            queueHasNext() || super.hasNextMediaItem()

        override fun seekToNext() {
            PlaybackCenter.next()
        }

        override fun seekToNextMediaItem() {
            PlaybackCenter.next()
        }

        override fun seekToPrevious() {
            PlaybackCenter.previous()
        }

        override fun seekToPreviousMediaItem() {
            PlaybackCenter.previous()
        }
    }

    private var forwardingPlayer: QueueForwardingPlayer? = null
    private var forwardingFor: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    fun attachView(view: PlayerView) {
        val raw = playerRef ?: return
        val fwd = forwardingPlayer?.takeIf { forwardingFor === raw }
            ?: QueueForwardingPlayer(raw).also {
                forwardingPlayer = it
                forwardingFor = raw
            }
        view.player = fwd
    }

    fun detachView(view: PlayerView) {
        if (view.player === forwardingPlayer) {
            view.player = null
        }
    }

    fun release() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        watchdogJob?.cancel()
        sleepTimerJob?.cancel()
        crossfadeMonitorJob?.cancel()
        crossfadeRampJob?.cancel()
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        scope.cancel()
        forwardingPlayer = null
        forwardingFor = null
        playerRef?.release()
        playerRef = null
    }
}

fun StreamInfoItem.toQueueEntry(isMusic: Boolean = false): QueueEntry = QueueEntry(
    url = url.orEmpty(),
    title = name.orEmpty(),
    uploader = uploaderName ?: "",
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
    durationSec = duration,
    isMusic = isMusic
)

fun QueueEntry.toVideoEntry(): VideoEntry = VideoEntry(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    isMusic = isMusic
)
