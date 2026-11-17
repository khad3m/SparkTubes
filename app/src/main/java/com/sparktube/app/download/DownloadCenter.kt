package com.sparktube.app.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.playback.effectiveHeight
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.DeviceTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Built-in multi-threaded download engine with:
 * - Play / Pause / Resume / Retry (`STATUS_PAUSED`, `.partmeta` byte-offset persistence, and automatic
 *   YouTube CDN stream URL re-extraction when retrying expired links)
 * - Public Local Storage (`Download/SparkTube`, `Music/SparkTube`, `Movies/SparkTube`) + Custom Folder
 *   support (`AppPrefs.downloadFolderMode`, `AppPrefs.customDownloadPath`, SAF tree export, and
 *   `MediaScannerConnection` indexing so files appear immediately in File Manager & Gallery)
 * - 6x parallel HTTP Range acceleration per file
 */
object DownloadCenter {

    private const val TAG = "DownloadCenter"

    const val TYPE_AUDIO = "AUDIO"
    const val TYPE_VIDEO = "VIDEO"
    const val TYPE_AV = "AV"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_DONE = "DONE"
    const val STATUS_FAILED = "FAILED"

    /** Parallel connections per file (acceleration factor). */
    private fun threadsPerFile(): Int = appCtx?.let { DeviceTuning.downloadThreads(it) } ?: 3

    /** Minimum file size (bytes) below which parallel splitting is pointless. */
    private const val MIN_SIZE_FOR_SPLIT = 2L * 1024L * 1024L

    /** Chunk retry attempts before a download fails. */
    private const val CHUNK_RETRIES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Shared engine client: generous dispatcher + connection pool for parallel range requests. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 24
            }
        )
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    private val syncLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val jobIdSeq = AtomicLong(1L)

    /** Live jobs by internal job id. */
    private val jobs = ConcurrentHashMap<Long, DownloadJob>()

    private var appCtx: Context? = null

    /** All registry records belonging to one video or local file URL. */
    fun recordsFor(context: Context, videoUrl: String): List<DownloadRecord> {
        if (videoUrl.startsWith("file://")) {
            val localPath = videoUrl.removePrefix("file://")
            val byPath = LocalStore.downloads(context).filter { it.filePaths.contains(localPath) }
            if (byPath.isNotEmpty()) return byPath
        }
        val id = videoIdOf(videoUrl)
        return LocalStore.downloads(context).filter { it.videoId == id }
    }

    /** Whether the video/song page should show "Downloaded". */
    fun isDownloaded(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any {
            it.status == STATUS_DONE &&
                it.filePaths.isNotEmpty() &&
                it.filePaths.all { p -> File(p).let { f -> f.exists() && f.length() > 0L } }
        }

    fun hasActiveDownload(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any {
            it.status == STATUS_PENDING || it.status == STATUS_RUNNING
        }

    fun hasPausedDownload(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any { it.status == STATUS_PAUSED }

    fun hasFailedDownload(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any { it.status == STATUS_FAILED }

    /**
     * Aggregate progress (0..100) across all active or paused records of one video/song.
     * Returns -1 when nothing is downloading or paused.
     */
    fun downloadProgress(context: Context, videoUrl: String): Int {
        val relevant = recordsFor(context, videoUrl).filter {
            it.status == STATUS_PENDING || it.status == STATUS_RUNNING || it.status == STATUS_PAUSED
        }
        if (relevant.isEmpty()) return -1
        return recordProgress(relevant.first())
    }

    /**
     * Human-readable byte progress for an active or paused video/song download (e.g. "3.4 MB / 7.8 MB").
     */
    fun downloadBytesText(context: Context, videoUrl: String): String {
        val relevant = recordsFor(context, videoUrl).filter {
            it.status == STATUS_PENDING || it.status == STATUS_RUNNING || it.status == STATUS_PAUSED
        }
        if (relevant.isEmpty()) return ""
        return recordBytesText(relevant.first())
    }

    /**
     * Progress (0..100) for one specific [DownloadRecord].
     * Works for RUNNING, PENDING, PAUSED (reading `.partmeta` on disk if needed), and DONE.
     */
    fun recordProgress(record: DownloadRecord): Int {
        if (record.status == STATUS_DONE) return 100
        var done = 0L
        var total = 0L
        record.downloadIds.forEachIndexed { idx, id ->
            val job = jobs[id]
            if (job != null && job.size > 0L) {
                done += job.progress.get().coerceAtLeast(0L)
                total += job.size.coerceAtLeast(0L)
            } else {
                val path = record.filePaths.getOrNull(idx)
                if (path != null) {
                    val (d, t) = readPartMetaBytes(File(path))
                    done += d
                    total += t
                }
            }
        }
        if (total <= 0L) return if (record.status == STATUS_FAILED) -1 else 0
        return ((done * 100L) / total).toInt().coerceIn(0, 100)
    }

    /**
     * Formatted downloaded/total size string for a single [DownloadRecord].
     */
    fun recordBytesText(record: DownloadRecord): String {
        if (record.status == STATUS_DONE) {
            val bytes = record.filePaths.sumOf { p -> File(p).takeIf { it.exists() }?.length() ?: 0L }
            return if (bytes > 0L) formatBytes(bytes) else ""
        }
        var done = 0L
        var total = 0L
        record.downloadIds.forEachIndexed { idx, id ->
            val job = jobs[id]
            if (job != null && job.size > 0L) {
                done += job.progress.get().coerceAtLeast(0L)
                total += job.size.coerceAtLeast(0L)
            } else {
                val path = record.filePaths.getOrNull(idx)
                if (path != null) {
                    val (d, t) = readPartMetaBytes(File(path))
                    done += d
                    total += t
                }
            }
        }
        return formatBytesPair(done, total)
    }

    private fun partMetaFileFor(targetFile: File): File =
        File(targetFile.parentFile, "${targetFile.name}.partmeta")

    private fun readPartMetaBytes(targetFile: File): Pair<Long, Long> {
        return runCatching {
            val meta = partMetaFileFor(targetFile)
            if (!meta.exists()) return 0L to 0L
            val lines = meta.readLines()
            val total = lines.getOrNull(0)?.toLongOrNull() ?: 0L
            val chunks = lines.getOrNull(1)?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
            chunks.sum() to total
        }.getOrDefault(0L to 0L)
    }

    private fun formatBytesPair(done: Long, total: Long): String {
        if (total <= 0L && done <= 0L) return ""
        return if (total > 0L) {
            "${formatBytes(done.coerceAtMost(total))} / ${formatBytes(total)}"
        } else {
            formatBytes(done)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
        else -> "$bytes B"
    }

    /** Pauses an active download without deleting partial bytes on disk. */
    fun pause(context: Context, record: DownloadRecord) = synchronized(syncLock) {
        appCtx = context.applicationContext
        record.downloadIds.forEach { id ->
            jobs.remove(id)?.pause()
        }
        LocalStore.updateDownloadRecord(context, record.copy(status = STATUS_PAUSED))
    }

    /** Resumes a paused download from the exact byte offset where it stopped. */
    fun resume(context: Context, record: DownloadRecord) {
        appCtx = context.applicationContext
        if (record.streamUrls.size == record.filePaths.size && record.streamUrls.all { it.isNotBlank() }) {
            launchJobsForRecord(context, record, resumeExisting = true)
        } else {
            retry(context, record)
        }
    }

    /**
     * Retries a failed or paused download. If stream URLs are missing or expired,
     * re-extracts fresh CDN URLs from [YtRepository.streamInfo] automatically.
     */
    fun retry(context: Context, record: DownloadRecord) {
        val ctx = context.applicationContext
        appCtx = ctx
        synchronized(syncLock) {
            record.downloadIds.forEach { id -> jobs.remove(id)?.pause() }
            LocalStore.updateDownloadRecord(ctx, record.copy(status = STATUS_PENDING))
        }
        scope.launch {
            val urls = if (record.streamUrls.size == record.filePaths.size &&
                record.streamUrls.all { it.isNotBlank() } &&
                probeStreamUrlOk(record.streamUrls.first())
            ) {
                record.streamUrls
            } else {
                refreshStreamUrls(record)
            }
            if (urls.size != record.filePaths.size || urls.any { it.isBlank() }) {
                synchronized(syncLock) {
                    LocalStore.updateDownloadRecord(ctx, record.copy(status = STATUS_FAILED))
                }
                mainHandler.post {
                    Toast.makeText(ctx, R.string.download_status_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val updated = record.copy(streamUrls = urls, status = STATUS_RUNNING)
            launchJobsForRecord(ctx, updated, resumeExisting = true)
        }
    }

    /** Convenience toggle for player screens: pauses if running, resumes if paused, retries if failed. */
    fun togglePauseResumeOrRetry(context: Context, videoUrl: String) {
        val records = recordsFor(context, videoUrl)
        val running = records.firstOrNull { it.status == STATUS_RUNNING || it.status == STATUS_PENDING }
        if (running != null) {
            pause(context, running)
            Toast.makeText(context, R.string.download_status_paused, Toast.LENGTH_SHORT).show()
            return
        }
        val paused = records.firstOrNull { it.status == STATUS_PAUSED }
        if (paused != null) {
            resume(context, paused)
            Toast.makeText(context, R.string.download_resumed, Toast.LENGTH_SHORT).show()
            return
        }
        val failed = records.firstOrNull { it.status == STATUS_FAILED }
        if (failed != null) {
            retry(context, failed)
            Toast.makeText(context, R.string.download_retrying, Toast.LENGTH_SHORT).show()
        }
    }

    private fun probeStreamUrlOk(url: String): Boolean = runCatching {
        val builder = Request.Builder().url(url)
        YouTubeAuth.streamHeadersForUrl(url).forEach { (k, v) -> builder.header(k, v) }
        val req = builder.header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private suspend fun refreshStreamUrls(record: DownloadRecord): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val watchUrl = record.sourceUrl.takeIf { it.startsWith("http") }
                ?: "https://www.youtube.com/watch?v=${record.videoId}"
            val catalog = runCatching {
                val info = YtRepository.streamInfo(watchUrl)
                StreamCatalog(info, watchUrl)
            }.getOrElse {
                com.sparktube.app.net.YouTubePlayerUnlock.fetchUnlockedPlayerJson(record.videoId)
                    ?.let { StreamCatalog.fromPlayerJson(it, watchUrl) }
                    ?: throw it
            }
            val audioGroup = catalog.audioTracks.firstOrNull()
            when (record.type) {
                TYPE_AUDIO -> {
                    val wantWebm = record.quality.contains("Opus", ignoreCase = true) ||
                        record.filePaths.firstOrNull()?.endsWith(".webm", ignoreCase = true) == true
                    val stream = audioGroup?.streams
                        ?.filter { it.isUrl }
                        ?.sortedByDescending { it.averageBitrate }
                        ?.firstOrNull {
                            val isWebm = it.format?.mimeType?.contains("webm", ignoreCase = true) == true
                            isWebm == wantWebm
                        }
                        ?: audioGroup?.best
                    listOfNotNull(stream?.content)
                }
                TYPE_AV -> {
                    val height = Regex("(\\d{3,4})").find(record.quality)?.groupValues?.get(1)?.toIntOrNull()
                    val v = catalog.videoOnly.firstOrNull { it.effectiveHeight() == height }
                        ?: catalog.videoOnly.firstOrNull()
                    val a = audioGroup?.streams
                        ?.filter { it.isUrl && it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
                        ?.maxByOrNull { it.averageBitrate }
                        ?: audioGroup?.best
                    if (v != null && a != null) listOf(v.content, a.content) else emptyList()
                }
                else -> {
                    val height = Regex("(\\d{3,4})").find(record.quality)?.groupValues?.get(1)?.toIntOrNull()
                    val v = catalog.muxed.firstOrNull { it.effectiveHeight() == height }
                        ?: catalog.videoOnly.firstOrNull { it.effectiveHeight() == height }
                        ?: catalog.muxed.firstOrNull()
                        ?: catalog.videoOnly.firstOrNull()
                    listOfNotNull(v?.content)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun launchJobsForRecord(context: Context, record: DownloadRecord, resumeExisting: Boolean) {
        val ctx = context.applicationContext
        appCtx = ctx
        val newJobs = mutableListOf<DownloadJob>()
        synchronized(syncLock) {
            record.downloadIds.forEach { id -> jobs.remove(id)?.pause() }
            record.filePaths.forEachIndexed { idx, path ->
                val streamUrl = record.streamUrls.getOrElse(idx) { "" }
                val job = DownloadJob(streamUrl, File(path), resumeExisting = resumeExisting)
                jobs[job.id] = job
                newJobs.add(job)
            }
            val updated = record.copy(
                status = STATUS_RUNNING,
                downloadIds = newJobs.map { it.id }
            )
            LocalStore.updateDownloadRecord(ctx, updated)
        }
        newJobs.forEach { it.start() }
    }

    /** Deletes every download (files + registry). */
    fun clearAll(context: Context) = synchronized(syncLock) {
        jobs.values.forEach { it.cancelAndDelete() }
        jobs.clear()
        LocalStore.downloads(context).forEach { record ->
            record.filePaths.forEach { path ->
                val f = File(path)
                runCatching { partMetaFileFor(f).delete() }
                runCatching { f.delete() }
            }
        }
        LocalStore.clearDownloads(context)
    }

    /**
     * Extracts a canonical video ID from any YouTube watch/short/embed URL
     * OR from a local SparkTube `file://` download path (`<title>__<videoId>_<TYPE>_...` or `<videoId>_<TYPE>_...`).
     */
    fun videoIdOf(url: String): String {
        if (url.startsWith("file://")) {
            val fileName = File(url.removePrefix("file://")).nameWithoutExtension
            val afterDoubleUnderscore = if (fileName.contains("__")) {
                fileName.substringAfterLast("__")
            } else {
                fileName
            }
            val prefix = afterDoubleUnderscore
                .substringBefore("_AUDIO_")
                .substringBefore("_VIDEO_")
                .substringBefore("_AV_")
            if (prefix.isNotBlank() && prefix != afterDoubleUnderscore) {
                return prefix.take(20)
            }
        }
        val vParam = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        val shortLink = Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        val pathId = Regex("(?:shorts|embed|live)/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        val fallback = url.replace(Regex("[^A-Za-z0-9_-]"), "").takeLast(20)
        return (vParam ?: shortLink ?: pathId ?: fallback).take(20)
    }

    private fun dirOf(type: String): String = when (type) {
        TYPE_AUDIO -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_MOVIES
    }

    /**
     * Resolves the writable download directory on local storage according to [AppPrefs.downloadFolderMode]:
     * 1. Custom folder (`AppPrefs.customDownloadPath`) when writable
     * 2. Public `Download/SparkTube` (`/storage/emulated/0/Download/SparkTube`) — default & visible in all File Managers
     * 3. Public `Music/SparkTube` or `Movies/SparkTube`
     * 4. App-private fallback (`getExternalFilesDir`) if public storage is restricted by OEM ROM
     */
    fun resolveDownloadDir(context: Context, type: String): File {
        val candidates = mutableListOf<File>()
        when (AppPrefs.downloadFolderMode) {
            AppPrefs.FOLDER_MODE_CUSTOM -> {
                val custom = AppPrefs.customDownloadPath.trim()
                if (custom.isNotBlank()) {
                    candidates.add(File(custom))
                }
                candidates.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube"))
            }
            AppPrefs.FOLDER_MODE_PUBLIC_MEDIA -> {
                candidates.add(File(Environment.getExternalStoragePublicDirectory(dirOf(type)), "SparkTube"))
                candidates.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube"))
            }
            AppPrefs.FOLDER_MODE_APP_PRIVATE -> {
                val base = context.getExternalFilesDir(dirOf(type)) ?: context.filesDir
                candidates.add(File(base, "SparkTube"))
            }
            else -> { // FOLDER_MODE_PUBLIC_DOWNLOADS (Default)
                candidates.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube"))
                candidates.add(File(Environment.getExternalStoragePublicDirectory(dirOf(type)), "SparkTube"))
            }
        }
        val appPrivateBase = context.getExternalFilesDir(dirOf(type)) ?: context.filesDir
        candidates.add(File(appPrivateBase, "SparkTube"))

        for (dir in candidates) {
            if (ensureDirectoryWritable(dir)) {
                return dir
            }
        }
        return File(appPrivateBase, "SparkTube").apply { mkdirs() }
    }

    private fun ensureDirectoryWritable(dir: File): Boolean = runCatching {
        if (!dir.exists()) dir.mkdirs()
        if (!dir.exists() || !dir.isDirectory) return false
        val probe = File(dir, ".sparktube_probe_${System.nanoTime()}")
        val ok = probe.createNewFile()
        if (ok) probe.delete()
        ok || dir.canWrite()
    }.getOrDefault(false)

    private fun destinationPath(context: Context, fileName: String, type: String): String {
        val dir = resolveDownloadDir(context, type)
        return File(dir, fileName).absolutePath
    }

    private fun safeTitleSlug(title: String): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(36)
            .trim('_')
        return cleaned.ifBlank { "SparkTube" }
    }

    private fun safeQualityToken(quality: String): String =
        quality.replace(Regex("[^A-Za-z0-9_-]"), "")

    /** Starts a single-file download (AUDIO or VIDEO). */
    fun startSingle(
        context: Context,
        videoUrl: String,
        title: String,
        uploader: String,
        thumbUrl: String,
        type: String,
        quality: String,
        streamUrl: String,
        fileExt: String,
        durationSec: Long = 0L
    ) {
        appCtx = context.applicationContext
        val videoId = videoIdOf(videoUrl)
        val cleanExt = fileExt.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").ifBlank {
            if (type == TYPE_AUDIO) "m4a" else "mp4"
        }
        val slug = safeTitleSlug(title)
        val fileName = "${slug}__${videoId}_${type}_${safeQualityToken(quality)}.$cleanExt"
        val path = destinationPath(context, fileName, type)

        synchronized(syncLock) {
            LocalStore.downloads(context)
                .filter { it.videoId == videoId && it.type == type && it.quality == quality }
                .forEach { old ->
                    old.downloadIds.forEach { id -> jobs.remove(id)?.cancelAndDelete() }
                }
        }

        val job = DownloadJob(streamUrl, File(path), resumeExisting = false)
        jobs[job.id] = job
        val canonicalSourceUrl = if (videoUrl.startsWith("http")) videoUrl else "https://www.youtube.com/watch?v=$videoId"
        LocalStore.addDownload(
            context,
            DownloadRecord(
                videoId = videoId,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumbUrl,
                type = type,
                quality = quality,
                status = STATUS_PENDING,
                filePaths = listOf(path),
                downloadIds = listOf(job.id),
                durationSec = durationSec,
                sourceUrl = canonicalSourceUrl,
                streamUrls = listOf(streamUrl)
            )
        )
        job.start()
    }

    /** Starts a video+audio pair download (AV) — both files download in parallel. */
    fun startPair(
        context: Context,
        videoUrl: String,
        title: String,
        uploader: String,
        thumbUrl: String,
        quality: String,
        videoStreamUrl: String,
        audioStreamUrl: String,
        durationSec: Long = 0L,
        audioExt: String = "m4a"
    ) {
        appCtx = context.applicationContext
        val videoId = videoIdOf(videoUrl)
        val qToken = safeQualityToken(quality)
        val slug = safeTitleSlug(title)

        synchronized(syncLock) {
            LocalStore.downloads(context)
                .filter { it.videoId == videoId && it.type == TYPE_AV && it.quality == quality }
                .forEach { old ->
                    old.downloadIds.forEach { id -> jobs.remove(id)?.cancelAndDelete() }
                }
        }

        val videoJob = DownloadJob(
            videoStreamUrl,
            File(destinationPath(context, "${slug}__${videoId}_AV_${qToken}_v.mp4", TYPE_AV)),
            resumeExisting = false
        )
        // v1.7.1: the audio sidecar carries its REAL container extension
        // (m4a for AAC, webm for Opus) so the fallback two-file case plays
        // correctly in external players too.
        val safeAudioExt = audioExt.lowercase()
            .takeIf { it in setOf("m4a", "webm", "mp3", "opus", "ogg") } ?: "m4a"
        val audioJob = DownloadJob(
            audioStreamUrl,
            File(destinationPath(context, "${slug}__${videoId}_AV_${qToken}_a.$safeAudioExt", TYPE_AV)),
            resumeExisting = false
        )
        jobs[videoJob.id] = videoJob
        jobs[audioJob.id] = audioJob
        val canonicalSourceUrl = if (videoUrl.startsWith("http")) videoUrl else "https://www.youtube.com/watch?v=$videoId"
        LocalStore.addDownload(
            context,
            DownloadRecord(
                videoId = videoId,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumbUrl,
                type = TYPE_AV,
                quality = quality,
                status = STATUS_PENDING,
                filePaths = listOf(videoJob.file.absolutePath, audioJob.file.absolutePath),
                downloadIds = listOf(videoJob.id, audioJob.id),
                durationSec = durationSec,
                sourceUrl = canonicalSourceUrl,
                streamUrls = listOf(videoStreamUrl, audioStreamUrl)
            )
        )
        videoJob.start()
        audioJob.start()
    }

    /** Deletes the files of a record and removes it from the registry. */
    fun delete(context: Context, record: DownloadRecord) = synchronized(syncLock) {
        record.downloadIds.forEach { id ->
            jobs.remove(id)?.cancelAndDelete()
        }
        record.filePaths.forEach { path ->
            val f = File(path)
            runCatching { partMetaFileFor(f).delete() }
            runCatching { f.delete() }
        }
        LocalStore.removeDownload(context, record)
    }

    fun refreshStatuses(context: Context) = synchronized(syncLock) {
        syncLocked(context)
    }

    private fun onJobTerminated(jobId: Long) {
        val ctx = appCtx ?: return
        synchronized(syncLock) {
            val affected = LocalStore.downloads(ctx)
                .filter { it.downloadIds.contains(jobId) }
            if (affected.isEmpty()) return
            syncLocked(ctx)
        }
    }

    /** The actual status sync — callers must hold [syncLock]. */
    private fun syncLocked(context: Context) {
        LocalStore.downloads(context).filter {
            it.status != STATUS_DONE && it.status != STATUS_FAILED && it.status != STATUS_PAUSED
        }.forEach { record ->
            val recordJobs = record.downloadIds.mapNotNull { jobs[it] }
            val newStatus = when {
                recordJobs.isEmpty() -> STATUS_FAILED
                recordJobs.any { it.paused.get() } -> STATUS_PAUSED
                recordJobs.any { it.failed.get() } -> STATUS_FAILED
                recordJobs.all { it.finished.get() } -> STATUS_DONE
                else -> STATUS_RUNNING
            }
            if (newStatus != record.status) {
                LocalStore.updateDownloadStatus(context, record.downloadIds.first(), newStatus)
                if (newStatus == STATUS_DONE || newStatus == STATUS_FAILED || newStatus == STATUS_PAUSED) {
                    recordJobs.forEach { jobs.remove(it.id) }
                    if (newStatus == STATUS_DONE) {
                        onRecordCompleted(context, record)
                    }
                }
            }
        }
    }

    private fun onRecordCompleted(context: Context, record: DownloadRecord) {
        val ctx = appCtx ?: context.applicationContext
        // Video+Audio downloads arrive as two files (video-only + audio-only). External players
        // (MX Player, Gallery) only play the video one, so merge them into a single .mp4 first.
        if (record.type == TYPE_AV && record.filePaths.size >= 2) {
            scope.launch(Dispatchers.IO) {
                val merged = mergeAvRecord(ctx, record)
                finishCompletedRecord(ctx, merged ?: record)
            }
            return
        }
        finishCompletedRecord(ctx, record)
    }

    /** @return the updated single-file record, or null if merging failed (two files are kept). */
    private fun mergeAvRecord(ctx: Context, record: DownloadRecord): DownloadRecord? {
        return runCatching {
            val videoFile = File(record.filePaths[0])
            val audioFile = File(record.filePaths[1])
            if (!videoFile.exists() || !audioFile.exists()) return null
            val ext = if (AvMuxer.isWebmPair(videoFile, audioFile)) ".webm" else ".mp4"
            val outName = videoFile.name.removeSuffix("_v.mp4") + ext
            val outFile = File(videoFile.parentFile, outName)
            if (!AvMuxer.merge(videoFile, audioFile, outFile)) return null
            runCatching { videoFile.delete() }
            runCatching { audioFile.delete() }
            runCatching { partMetaFileFor(videoFile).delete() }
            runCatching { partMetaFileFor(audioFile).delete() }
            synchronized(syncLock) {
                val cur = LocalStore.downloads(ctx).firstOrNull { it.downloadIds == record.downloadIds }
                    ?: return null
                val updated = cur.copy(filePaths = listOf(outFile.absolutePath))
                LocalStore.updateDownloadRecord(ctx, updated)
                updated
            }
        }.getOrNull()
    }

    private fun finishCompletedRecord(ctx: Context, record: DownloadRecord) {
        // Index files in Android MediaStore so they immediately show up in Gallery / Music / File Manager
        val existingPaths = record.filePaths.filter { File(it).exists() }.toTypedArray()
        if (existingPaths.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(ctx, existingPaths, null, null)
            }
        }
        // If user selected a SAF tree URI whose path differs from the local file's parent, mirror copy if needed
        if (AppPrefs.downloadFolderMode == AppPrefs.FOLDER_MODE_CUSTOM &&
            AppPrefs.customDownloadTreeUri.isNotBlank()
        ) {
            scope.launch(Dispatchers.IO) {
                exportToSafTreeIfNeeded(ctx, record)
            }
        }
        mainHandler.post {
            Toast.makeText(
                ctx,
                "${ctx.getString(R.string.download_status_done)} • ${AppPrefs.downloadFolderSummary().substringAfterLast('/')}",
                Toast.LENGTH_SHORT
            ).show()
        }
        postCompletionNotification(ctx, record)
    }

    private const val NOTIF_CHANNEL_DOWNLOADS = "sparktube_downloads_done"

    /** "Download complete" notification with tap-to-open-app and an Open (play) action. */
    private fun postCompletionNotification(ctx: Context, record: DownloadRecord) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                nm.getNotificationChannel(NOTIF_CHANNEL_DOWNLOADS) == null
            ) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIF_CHANNEL_DOWNLOADS,
                        "Downloads",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            val id = (record.downloadIds.firstOrNull()?.hashCode() ?: record.title.hashCode())
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val open = launch?.let { android.app.PendingIntent.getActivity(ctx, id, it, flags) }

            val builder = androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(record.title)
                .setContentText(ctx.getString(R.string.download_status_done))
                .setAutoCancel(true)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_STATUS)
                .apply { open?.let { setContentIntent(it) } }

            val file = record.filePaths.firstOrNull()?.let { File(it) }
            if (file != null && file.exists()) {
                // FileProvider can't serve every location (e.g. an SD card); never let that
                // block the notification itself.
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file
                    )
                    val mime = when (file.extension.lowercase()) {
                        "m4a", "mp3", "aac", "opus", "ogg" -> "audio/*"
                        else -> "video/*"
                    }
                    val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    builder.addAction(
                        R.drawable.ic_download, "Open",
                        android.app.PendingIntent.getActivity(ctx, id + 1, view, flags)
                    )
                }
            }
            nm.notify(id, builder.build())
        }
    }

    private fun exportToSafTreeIfNeeded(context: Context, record: DownloadRecord) {
        runCatching {
            val customPath = AppPrefs.customDownloadPath
            // If the file was already written directly into customDownloadPath, no copy is needed
            if (customPath.isNotBlank() && record.filePaths.firstOrNull()?.startsWith(customPath) == true) {
                return
            }
            val treeUri = Uri.parse(AppPrefs.customDownloadTreeUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            record.filePaths.forEach { path ->
                val src = File(path)
                if (!src.exists() || src.length() <= 0L) return@forEach
                val mime = when {
                    src.name.endsWith(".m4a", true) -> "audio/mp4"
                    src.name.endsWith(".webm", true) -> "audio/webm"
                    else -> "video/mp4"
                }
                val destUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    mime,
                    src.name
                ) ?: return@forEach
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                }
            }
        }
    }

    /**
     * One parallel-accelerated file download supporting byte-accurate Pause & Resume via `.partmeta`.
     */
    private class DownloadJob(
        val url: String,
        val file: File,
        val resumeExisting: Boolean
    ) {
        val id = DownloadCenter.jobIdSeq.incrementAndGet()

        val progress = AtomicLong(0L)
        @Volatile
        var size: Long = 0L

        val finished = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val paused = AtomicBoolean(false)

        private var job: Job? = null
        private val cancelled = AtomicBoolean(false)
        private val metaFile = partMetaFileFor(file)

        fun start() {
            file.parentFile?.mkdirs()
            if (!resumeExisting) {
                runCatching { if (file.exists()) file.delete() }
                runCatching { if (metaFile.exists()) metaFile.delete() }
            }
            job = DownloadCenter.scope.launch {
                val ok = runCatching { downloadInternal() }
                    .onFailure { Log.w(TAG, "Download job #$id failed for $url", it) }
                    .getOrElse { false }
                when {
                    paused.get() -> {
                        // Keep file & metaFile intact for later resume
                    }
                    ok && !cancelled.get() && file.exists() && file.length() > 0L -> {
                        finished.set(true)
                        runCatching { metaFile.delete() }
                    }
                    else -> {
                        failed.set(true)
                        if (cancelled.get()) {
                            runCatching { metaFile.delete() }
                            runCatching { file.delete() }
                        }
                    }
                }
                DownloadCenter.onJobTerminated(id)
            }
        }

        fun pause() {
            paused.set(true)
            cancelled.set(true)
            job?.cancel()
        }

        fun cancelAndDelete() {
            cancelled.set(true)
            job?.cancel()
            runCatching { metaFile.delete() }
            runCatching { file.delete() }
        }

        private fun savePartMeta(totalSize: Long, chunkOffsets: AtomicLongArray) {
            runCatching {
                val sb = StringBuilder()
                sb.append(totalSize).append('\n')
                for (i in 0 until chunkOffsets.length()) {
                    if (i > 0) sb.append(',')
                    sb.append(chunkOffsets.get(i))
                }
                metaFile.writeText(sb.toString())
            }
        }

        private fun loadPartMeta(expectedTotal: Long, chunkCount: Int): LongArray? {
            return runCatching {
                if (!metaFile.exists() || !file.exists()) return null
                val lines = metaFile.readLines()
                val savedTotal = lines.getOrNull(0)?.toLongOrNull() ?: return null
                if (savedTotal != expectedTotal) return null
                val parts = lines.getOrNull(1)?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return null
                if (parts.size != chunkCount) return null
                parts.toLongArray()
            }.getOrNull()
        }

        private suspend fun downloadInternal(): Boolean = withContext(Dispatchers.IO) {
            val probe = newRequest()
                .header("Range", "bytes=0-0")
                .build()
            val totalSize: Long
            val rangesSupported: Boolean
            DownloadCenter.client.newCall(probe).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Probe failed: HTTP ${resp.code} for $url")
                    return@withContext false
                }
                val contentRange = resp.header("Content-Range")
                val acceptRanges = resp.header("Accept-Ranges")
                totalSize = contentRange
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: -1L
                rangesSupported = (contentRange != null) ||
                    (acceptRanges?.equals("bytes", ignoreCase = true) == true)
            }

            if (totalSize <= 0L) {
                size = 0L
                return@withContext plainDownload()
            }

            size = totalSize
            val chunkCount = if (!rangesSupported || totalSize < MIN_SIZE_FOR_SPLIT) 1 else threadsPerFile()
            val bounds = (0 until chunkCount).map { i ->
                val start = totalSize * i / chunkCount
                val end = if (i == chunkCount - 1) totalSize - 1L else (totalSize * (i + 1) / chunkCount - 1L)
                start to end
            }

            val restored = if (resumeExisting) loadPartMeta(totalSize, chunkCount) else null
            val chunkWritten = AtomicLongArray(chunkCount)
            var initialProgress = 0L
            if (restored != null) {
                for (i in 0 until chunkCount) {
                    val maxLen = bounds[i].second - bounds[i].first + 1L
                    val w = restored[i].coerceIn(0L, maxLen)
                    chunkWritten.set(i, w)
                    initialProgress += w
                }
            } else {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(totalSize)
                }
            }
            progress.set(initialProgress)
            savePartMeta(totalSize, chunkWritten)

            val results = bounds.mapIndexed { idx, (start, end) ->
                launchSegment(idx, start, end, totalSize, chunkWritten)
            }
            results.map { segJob ->
                segJob.job.join()
                segJob.completed.get()
            }.all { it }
        }

        private fun newRequest(): Request.Builder =
            Request.Builder().url(url).apply {
                YouTubeAuth.streamHeadersForUrl(url).forEach { (k, v) -> header(k, v) }
            }

        /** Plain single-connection download (no size known / no ranges). */
        private suspend fun plainDownload(): Boolean = withContext(Dispatchers.IO) {
            try {
                val request = newRequest().build()
                DownloadCenter.client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Plain download failed: HTTP ${resp.code} for $url")
                        return@withContext false
                    }
                    val body = resp.body ?: return@withContext false
                    val contentLen = body.contentLength()
                    if (contentLen > 0L) size = contentLen
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) return@withContext false
                                val read = input.read(buf)
                                if (read < 0) break
                                out.write(buf, 0, read)
                                progress.addAndGet(read.toLong())
                            }
                            out.flush()
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Plain download error for $url", e)
                false
            }
        }

        /** One contiguous byte range with `.partmeta` offset tracking for instant Pause & Resume. */
        private fun launchSegment(
            chunkIdx: Int,
            start: Long,
            end: Long,
            totalSize: Long,
            chunkWritten: AtomicLongArray
        ): SegmentJob {
            val completedFlag = AtomicBoolean(false)
            val segJob = DownloadCenter.scope.launch(Dispatchers.IO) {
                val totalChunkLen = end - start + 1L
                if (chunkWritten.get(chunkIdx) >= totalChunkLen) {
                    completedFlag.set(true)
                    return@launch
                }
                var attempt = 0
                while (attempt <= CHUNK_RETRIES && !cancelled.get()) {
                    val alreadyDone = chunkWritten.get(chunkIdx).coerceIn(0L, totalChunkLen)
                    val rangeStart = start + alreadyDone
                    if (rangeStart > end) {
                        completedFlag.set(true)
                        return@launch
                    }
                    var sessionWritten = 0L
                    try {
                        val request = newRequest()
                            .header("Range", "bytes=$rangeStart-$end")
                            .build()
                        DownloadCenter.client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("Empty body")
                            RandomAccessFile(file, "rw").use { raf ->
                                raf.seek(rangeStart)
                                val input = body.byteStream()
                                val buf = ByteArray(64 * 1024)
                                val expected = end - rangeStart + 1L
                                var sinceLastMetaSave = 0L
                                while (sessionWritten < expected) {
                                    if (cancelled.get()) {
                                        savePartMeta(totalSize, chunkWritten)
                                        return@launch
                                    }
                                    val read = input.read(
                                        buf,
                                        0,
                                        minOf(buf.size.toLong(), expected - sessionWritten).toInt()
                                    )
                                    if (read < 0) throw IOException("Unexpected end of stream")
                                    raf.write(buf, 0, read)
                                    sessionWritten += read.toLong()
                                    chunkWritten.set(chunkIdx, alreadyDone + sessionWritten)
                                    progress.addAndGet(read.toLong())
                                    sinceLastMetaSave += read.toLong()
                                    if (sinceLastMetaSave >= 256 * 1024L) {
                                        sinceLastMetaSave = 0L
                                        savePartMeta(totalSize, chunkWritten)
                                    }
                                }
                            }
                            savePartMeta(totalSize, chunkWritten)
                            completedFlag.set(true)
                            return@launch
                        }
                    } catch (e: Exception) {
                        if (cancelled.get()) {
                            savePartMeta(totalSize, chunkWritten)
                            return@launch
                        }
                        savePartMeta(totalSize, chunkWritten)
                        attempt++
                        if (attempt > CHUNK_RETRIES) break
                        try { Thread.sleep(300L * attempt) } catch (_: InterruptedException) {}
                    }
                }
            }
            return SegmentJob(segJob, completedFlag)
        }
    }

    /** Small holder: a segment coroutine + its success flag. */
    private class SegmentJob(val job: Job, val completed: AtomicBoolean)
}
