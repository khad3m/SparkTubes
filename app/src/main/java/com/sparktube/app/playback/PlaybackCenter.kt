package com.sparktube.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.LiveFilter
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.net.ParallelRangeDataSource
import com.sparktube.app.util.AppPrefs
import com.google.common.util.concurrent.ListenableFuture
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
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown wherever a resolved stream turns out to be a live broadcast, which
 * SparkTube intentionally never plays. Kept as its own type (rather than a
 * magic "LIVE_CONTENT" string inside a generic exception) so callers can
 * detect it with `is LiveContentException` instead of substring-matching an
 * error message, which silently stops working if the message ever changes.
 */
class LiveContentException :
    IOException("This is a live stream, which SparkTube doesn't support playing.")

/** Best-effort height: itag height or parsed from the resolution string ("1080p60"). */
fun VideoStream.effectiveHeight(): Int =
    if (height > 0) height else StreamCatalog.resolutionHeight(resolution)

/** One item in the play queue (resolved lazily when it starts playing). */
data class QueueEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val isMusic: Boolean = false
)

/** A group of audio streams that share the same dubbing language. */
data class AudioTrackGroup(
    val id: String,
    val label: String,
    val isOriginal: Boolean,
    val streams: List<AudioStream>
) {
    val best: AudioStream? get() = streams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
}

/** Cleaned-up stream catalogue for the currently loaded item. */
class StreamCatalog(info: StreamInfo) {

    val name: String = info.name ?: ""
    val uploaderUrl: String = info.uploaderUrl ?: ""
    val uploaderName: String = info.uploaderName ?: ""
    val uploaderAvatarUrl: String = info.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
    val viewCount: Long = info.viewCount
    val durationSec: Long = info.duration

    /** Video description (HTML for YouTube) — folded/unfolded on the watch page. */
    val description: String = info.description?.content.orEmpty()
    val descriptionIsHtml: Boolean =
        info.description?.type == org.schabi.newpipe.extractor.stream.Description.HTML

    /** Relative upload date, e.g. "3 days ago" (empty when unknown). */
    val uploadDateText: String =
        com.sparktube.app.util.Formatters.formatRelativeTime(info.textualUploadDate)
    val likeCount: Long = info.likeCount

    /** Video-only adaptive streams (higher qualities), distinct heights, best first. */
    val videoOnly: List<VideoStream> = info.videoOnlyStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    /**
     * Every video-only stream (all codecs / fps variants, not de-duplicated by
     * height): the raw material for the adaptive DASH start ([DashSources]).
     */
    val allVideoOnly: List<VideoStream> = info.videoOnlyStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }

    /** Progressive (muxed) streams, fallback only. */
    val muxed: List<VideoStream> = info.videoStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    /** Audio streams grouped into dubbing languages. */
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

    /** Related videos for the watch page (already live-filtered). */
    val related: List<StreamInfoItem> = LiveFilter.sanitize(info.relatedItems)

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
 * App-wide playback engine. Owns the single ExoPlayer instance that the
 * watch page, the mini player, the music screen and the media notification
 * all attach to, so playback survives screen changes and app close.
 *
 * Video mode uses a single merged source. Music mode builds a real
 * playlist whose items are resolved on demand, so the media notification
 * gets native previous / next / seek controls.
 */
@OptIn(UnstableApi::class)
object PlaybackCenter {

    enum class Mode { NONE, VIDEO, AUDIO }

    interface Listener {
        fun onItemChanged(entry: QueueEntry?) {}
        fun onResolvingChanged(isResolving: Boolean) {}
        fun onCatalogReady() {}
        fun onPlaybackStateChanged(isPlaying: Boolean) {}
        fun onQueueChanged() {}
        fun onError(message: String) {}

        /** A resolved item turned out to be live content — see [LiveContentException]. */
        fun onLiveContentBlocked() {}
        fun onFavoriteChanged(url: String, isFavorite: Boolean) {}
        fun onAudioOnlyChanged(audioOnly: Boolean) {}

        /** Short user-facing hint ("switched to the default audio track", …). */
        fun onNotice(message: String) {}
    }

    lateinit var appContext: Context
        private set

    val listeners = mutableListOf<Listener>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Radio sizing: first batch, refill threshold / batch, and the minimum
    // number of upcoming songs before the artist-search top-up kicks in.
    private const val RADIO_FIRST_BATCH = 30
    private const val RADIO_BATCH = 20
    private const val RADIO_REFILL_BELOW = 10
    private const val RADIO_MIN_UPCOMING = 15
    private var playerRef: ExoPlayer? = null
    private var resolveJob: Job? = null

    // ----- Startup watchdog (auto quality downshift) -----

    private var watchdogJob: Job? = null
    private var watchdogDownshifts = 0
    private var downshiftHeight: Int? = null

    // ----- Adaptive (DASH) start -----

    private var dashActive = false
    private var dashBlocked = false
    private var sourceAppliedAt = 0L

    private val STARTUP_LIMIT_MS = 10_000L
    private val STALL_LIMIT_MS = 4_000L

    /** Items of the music playlist that have not been resolved yet. */
    private val pendingMusic = ConcurrentHashMap<String, QueueEntry>()
    private val musicItemId = AtomicLong(0L)

    /** Already-resolved audio URLs for music queue entries (entry.url -> Uri). */
    private val musicUriCache = ConcurrentHashMap<String, Uri>()

    private var prefetchJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null

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

    var catalog: StreamCatalog? = null
        private set

    /** User selections that survive quality/track switches. */
    var selectedAudioTrackId: String? = null
        private set
    private var selectedHeight: Int? = null

    /** Set when the user explicitly picked "Auto" in the gear menu. */
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
            catalog?.audioTracks?.firstOrNull { it.id == id }?.label
        }

    val currentEntry: QueueEntry? get() = queue.getOrNull(queueIndex)
    val hasMedia: Boolean get() = currentEntry != null

    /** Current playback speed in effect. */
    var playbackSpeed: Float = 1.0f
        private set

    // ----- Lifecycle -----

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    val player: ExoPlayer
        get() {
            check(this::appContext.isInitialized) { "PlaybackCenter.init() not called" }
            return playerRef ?: ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ 45_000,
                            /* maxBufferMs = */ 120_000,
                            /* bufferForPlaybackMs = */ 1_000,
                            /* bufferForPlaybackAfterRebufferMs = */ 5_000
                        )
                        .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notify { it.onPlaybackStateChanged(isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && mode == Mode.VIDEO &&
                (playlistMode || AppPrefs.videoAutoplayNext)
            ) {
                advance()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mode != Mode.AUDIO) return
            val index = playerRef?.currentMediaItemIndex ?: return
            if (index < 0 || index >= queue.size) return
            queueIndex = index
            currentEntry?.let { entry ->
                notify { it.onItemChanged(entry) }
                if (!entry.url.startsWith("file://")) {
                    resolveCatalog(entry)
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

    /** Kills the current playback instantly so sounds never overlap. */
    private fun stopPlayerNow() {
        playerRef?.run {
            stop()
            clearMediaItems()
        }
    }

    // ----- Commands -----

    /** Starts a video (watch page). Builds a YouTube-like autoplay queue. */
    fun playVideo(entry: QueueEntry, extraQueue: List<QueueEntry> = emptyList()) {
        initFromApp()
        stopPlayerNow()
        mode = Mode.VIDEO
        radioMode = false
        playlistMode = false
        audioOnlyMode = false
        setQueue(listOf(entry) + extraQueue, 0)
        resolveCurrent()
    }

    /**
     * Plays a fixed video playlist starting at [startIndex].
     */
    fun playPlaylist(entries: List<QueueEntry>, startIndex: Int = 0) {
        initFromApp()
        if (entries.isEmpty()) return
        stopPlayerNow()
        mode = Mode.VIDEO
        radioMode = false
        playlistMode = true
        audioOnlyMode = false
        setQueue(entries, startIndex.coerceIn(0, entries.size - 1))
        resolveCurrent()
    }

    /**
     * Starts a song (optionally as an endless radio of related tracks).
     */
    fun playMusic(entry: QueueEntry, radio: Boolean) {
        playMusicQueue(listOf(entry), 0, allowRadio = radio)
    }

    /**
     * Starts a fixed music playlist (favorites, downloads, …).
     */
    fun playMusicQueue(entries: List<QueueEntry>, startIndex: Int = 0, allowRadio: Boolean = false) {
        initFromApp()
        if (entries.isEmpty()) return
        val start = startIndex.coerceIn(0, entries.size - 1)
        val playlist = if (start == 0) entries else entries.drop(start)
        stopPlayerNow()
        mode = Mode.AUDIO
        radioMode = allowRadio
        playlistMode = false
        audioOnlyMode = false
        pendingMusic.clear()
        setQueue(playlist, 0)
        resolveJob?.cancel()
        setResolving(true)
        resolveJob = scope.launch {
            try {
                val first = currentEntry ?: return@launch
                if (first.url.startsWith("file://")) {
                    catalog = null
                    startMusicPlaylistWithRelated()
                    ensureService()
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                } else {
                    val info = YtRepository.streamInfo(first.url)
                    if (LiveFilter.isLive(info)) {
                        throw LiveContentException()
                    }
                    catalog = StreamCatalog(info)
                    LocalStore.addToMusicHistory(appContext, first.toVideoEntry())
                    RecommendEngine.logPlay(appContext, first.url, first.title, first.uploader)
                    primeMusicUri(first)
                    startMusicPlaylistWithRelated()
                    ensureService()
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                    prefetchAhead()
                }
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

    fun playQueueIndex(index: Int) {
        if (index < 0 || index >= queue.size) return
        when (mode) {
            Mode.AUDIO -> playerRef?.let { p ->
                if (index < p.mediaItemCount) {
                    p.seekTo(index, 0L)
                    p.playWhenReady = true
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

    /** Music settings: pause between songs instead of auto-advancing. */
    fun applyMusicAutoplay() {
        playerRef?.setPauseAtEndOfMediaItems(!AppPrefs.musicAutoplayNext)
    }

    fun next() {
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
        pendingMusic.clear()
        playerRef?.run {
            stop()
            clearMediaItems()
        }
        mode = Mode.NONE
        playlistMode = false
        queue.clear()
        queueIndex = -1
        catalog = null
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(null) }
        notify { it.onPlaybackStateChanged(false) }
    }

    // ----- Quality / track selection -----

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
        if (catalog?.audioTracks?.none { it.id == trackId } == true) return
        selectedAudioTrackId = trackId
        usedFallback = false
        rebuildSource(keepPosition = true)
        notify { it.onCatalogReady() }
    }

    /** Turns the current video into an audio-only background stream. */
    fun switchToAudioOnly() {
        audioOnlyMode = true
        rebuildSource(keepPosition = true)
        notify { it.onAudioOnlyChanged(true) }
    }

    /** Rebuilds the full video source after background-audio mode. */
    fun switchBackToVideo() {
        if (mode != Mode.VIDEO) return
        audioOnlyMode = false
        rebuildSource(keepPosition = true)
        notify { it.onAudioOnlyChanged(false) }
    }

    // ----- Seamless Dual Upper-Left Audio <-> Video Mode Switching -----

    /**
     * Switches from the Video Player (PlayerActivity) directly into the Apple Music
     * Audio Player (NowPlayingActivity) while preserving the current playback position.
     */
    fun switchToAudioPlayerMode() {
        initFromApp()
        val entry = currentEntry ?: return
        val savedPos = positionMs
        val wasPlaying = playerRef?.isPlaying ?: true
        val existingCatalog = catalog

        watchdogJob?.cancel()
        mode = Mode.AUDIO
        audioOnlyMode = true

        if (existingCatalog != null) {
            primeMusicUri(entry)
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
        // Fallback if catalog wasn't resolved yet
        playMusic(entry.copy(isMusic = true), radio = true)
    }

    /**
     * Switches from the Audio Player (NowPlayingActivity) directly into the Video Player
     * (PlayerActivity) while preserving the exact playback position.
     */
    fun switchToVideoPlayerMode() {
        initFromApp()
        val entry = currentEntry ?: return
        val savedPos = positionMs
        val wasPlaying = playerRef?.isPlaying ?: true
        val existingCatalog = catalog

        mode = Mode.VIDEO
        audioOnlyMode = false
        radioMode = false
        if (selectedHeight == -1) selectedHeight = null

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
        // Fallback: resolve full video stream if catalog was missing
        playVideo(entry.copy(isMusic = false))
    }

    // ----- Favorites -----

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

    // ----- Local downloads -----

    fun playDownload(record: DownloadRecord) {
        initFromApp()
        if (record.filePaths.isEmpty()) return
        mode = if (record.type == DownloadCenter.TYPE_AUDIO) Mode.AUDIO else Mode.VIDEO
        audioOnlyMode = record.type == DownloadCenter.TYPE_AUDIO
        radioMode = false
        playlistMode = false
        pendingMusic.clear()
        queue.clear()
        queue.add(
            QueueEntry(
                url = "file://" + record.filePaths.first(),
                title = record.title,
                uploader = record.uploader,
                thumbnailUrl = record.thumbnailUrl,
                durationSec = 0L,
                isMusic = record.type == DownloadCenter.TYPE_AUDIO
            )
        )
        queueIndex = 0
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }

        if (!startLocalFiles(record)) {
            notify { it.onError("Downloaded file is missing") }
            return
        }
        catalog = null
        notify { it.onCatalogReady() }
        ensureService()
    }

    private fun startLocalFiles(record: DownloadRecord): Boolean {
        val sources = record.filePaths
            .filter { File(it).exists() }
            .map { path ->
                progressiveSource(
                    MediaItem.Builder().setUri(Uri.fromFile(File(path))).build()
                )
            }
        if (sources.isEmpty()) return false
        val source = if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
        player.run {
            setMediaSource(source, 0L)
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
                record.filePaths.all { File(it).exists() }
        }

    // ----- Resolution -----

    private fun setQueue(newQueue: List<QueueEntry>, startIndex: Int) {
        queue.clear()
        queue.addAll(newQueue)
        queueIndex = startIndex
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }
    }

    private fun initFromApp() {
        if (this::appContext.isInitialized) return
        throw IllegalStateException("PlaybackCenter.init(context) must be called in Application.onCreate()")
    }

    /** Video mode: resolve the current item and play it. */
    private fun resolveCurrent() {
        val entry = currentEntry ?: return
        resolveJob?.cancel()
        watchdogJob?.cancel()
        downshiftHeight = null
        dashBlocked = false
        dashActive = false
        catalog = null
        notify { it.onItemChanged(currentEntry) }
        setResolving(true)
        resolveJob = scope.launch {
            try {
                val local = if (playlistMode) localVideoRecord(entry.url) else null
                if (local != null && startLocalFiles(local)) {
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                    ensureService()
                    return@launch
                }
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw LiveContentException()
                }
                catalog = StreamCatalog(info)
                usedFallback = false
                LocalStore.addToHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                applySources(playbackSpeed)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                extendQueueFromRelated()
                ensureService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    /** Music mode: resolve metadata for the current song (downloads, radio). */
    private fun resolveCatalog(entry: QueueEntry) {
        resolveJob?.cancel()
        setResolving(true)
        resolveJob = scope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw LiveContentException()
                }
                catalog = StreamCatalog(info)
                LocalStore.addToMusicHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                primeMusicUri(entry)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                if (radioMode) {
                    extendRadioFromRelated()
                }
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

    private fun extendQueueFromRelated() {
        if (playlistMode) return
        val rel = catalog?.related ?: return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url !in known }.take(20)
            .map { it.toQueueEntry(isMusic = mode == Mode.AUDIO) }
        if (additions.isEmpty()) return
        queue.addAll(additions)
        notify { it.onQueueChanged() }
    }

    private fun extendRadioFromRelated() {
        val rel = catalog?.related ?: return
        val idx = playerRef?.currentMediaItemIndex ?: queueIndex
        val remaining = (queue.size - idx - 1).coerceAtLeast(0)
        if (remaining >= RADIO_REFILL_BELOW) return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url.isNotBlank() && it.url !in known && !LiveFilter.isLive(it) }
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
                    .filter { it.url.isNotBlank() && it.url !in known && !LiveFilter.isLive(it) }
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
            playerRef?.run {
                if (hasNextMediaItem()) {
                    seekToNextMediaItem()
                    return
                }
            }
            notifyFailure()
            return
        }
        val c = catalog ?: run {
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
        val c = catalog ?: return
        val source = buildSource(entry, c) ?: run {
            notify { it.onError("No playable streams found") }
            return
        }
        sourceAppliedAt = SystemClock.elapsedRealtime()
        player.run {
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
        val c = catalog ?: return false
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
        val c = catalog ?: return
        val position = if (keepPosition) positionMs else 0L
        val wasPlaying = playerRef?.isPlaying ?: true
        val source = buildSource(entry, c) ?: return
        sourceAppliedAt = SystemClock.elapsedRealtime()
        player.run {
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

        // Music / audio-only background mode: pick the best audio stream.
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
        val streams = group.streams.filter { it.isUrl && it.averageBitrate > 0 }
        if (streams.isEmpty()) return group.best
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

    // ----- Music playlist -----

    private fun startMusicPlaylistWithRelated() {
        val first = currentEntry ?: return
        val items = mutableListOf(musicMediaItem(first))
        if (radioMode) {
            catalog?.related
                ?.filter { it.url.isNotBlank() && it.url != first.url && !LiveFilter.isLive(it) }
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
            queue.drop(1).forEach { items.add(musicMediaItem(it)) }
        }
        player.run {
            setMediaItems(items, 0, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
            applyMusicAutoplay()
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
            return MediaItem.Builder()
                .setUri(Uri.parse(entry.url))
                .setMediaMetadata(metadata)
                .build()
        }
        val key = "music-${musicItemId.incrementAndGet()}"
        if (pendingMusic.size > 128) pendingMusic.clear()
        pendingMusic[key] = entry
        return MediaItem.Builder()
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
        val audio = pickMusicAudio(StreamCatalog(info))
            ?: throw IOException("No audio stream found")
        return cacheMusicUri(entry.url, audio.content)
    }

    private fun cacheMusicUri(url: String, content: String): Uri {
        if (musicUriCache.size > 64) musicUriCache.clear()
        val uri = Uri.parse(content)
        musicUriCache[url] = uri
        return uri
    }

    private fun primeMusicUri(entry: QueueEntry) {
        val best = catalog?.let { pickMusicAudio(it) } ?: return
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
                    pickMusicAudio(StreamCatalog(info))?.let { audio ->
                        cacheMusicUri(entry.url, audio.content)
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

    private fun forceInitialRangeHeader(spec: DataSpec): DataSpec {
        val scheme = spec.uri.scheme
        if (scheme != "http" && scheme != "https") return spec
        if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) return spec
        if (spec.httpRequestHeaders.containsKey("Range")) return spec
        val headers = HashMap(spec.httpRequestHeaders)
        headers["Range"] = "bytes=0-"
        return spec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    // ----- View attach helpers -----

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
