package com.sparktube.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges a video-only file and an audio-only file into ONE playable .mp4
 * (no re-encoding, just remux), so MX Player / Gallery / any player has
 * both picture and sound.
 *
 * v1.7.1 hardening (fixes "only video plays in MX Player"):
 *  - Validates BOTH inputs before muxing (a failed/truncated audio download
 *    can no longer produce a silent mp4).
 *  - After muxing, re-opens the output and VERIFIES it really contains one
 *    video track AND one audio track with actual samples — MediaMuxer can
 *    silently drop an unsupported audio track (e.g. Opus on old APIs), which
 *    produced exactly the "video plays but no sound" files.
 *  - Retries the merge once if verification fails.
 *
 * Returns true on success. On any failure the output is deleted and the
 * original two files are left untouched.
 */
internal object AvMuxer {

    private const val TAG = "AvMuxer"

    /** true when the file can be opened and really carries the wanted track. */
    private fun hasTrack(file: File, prefix: String): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).any {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasTrack($prefix) failed on ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    /** Output sanity check: merged file must have video AND audio with samples. */
    private fun verifyMuxedOutput(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            // A tiny output (< ~200KB for a video) almost always means the
            // audio track was dropped mid-mux.
            hasVideo && hasAudio
        } catch (e: Exception) {
            Log.w(TAG, "verifyMuxedOutput failed on ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    fun merge(videoFile: File, audioFile: File, outFile: File): Boolean {
        // Input validation FIRST: never produce a silent file from a broken
        // audio download — leave the two source files for the caller instead.
        if (!hasTrack(videoFile, "video/")) {
            Log.w(TAG, "merge aborted: no video track in ${videoFile.name}")
            return false
        }
        if (!hasTrack(audioFile, "audio/")) {
            Log.w(TAG, "merge aborted: no audio track in ${audioFile.name}")
            return false
        }
        return mergeOnce(videoFile, audioFile, outFile) || mergeOnce(videoFile, audioFile, outFile)
    }

    private fun mergeOnce(videoFile: File, audioFile: File, outFile: File): Boolean {
        val tmp = File(outFile.parentFile, outFile.name + ".muxing")
        var videoEx: MediaExtractor? = null
        var audioEx: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            if (tmp.exists()) tmp.delete()

            videoEx = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioEx = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val vTrack = findTrack(videoEx, "video/") ?: return false
            val aTrack = findTrack(audioEx, "audio/") ?: return false
            videoEx.selectTrack(vTrack)
            audioEx.selectTrack(aTrack)

            val vFormat = videoEx.getTrackFormat(vTrack)
            val aFormat = audioEx.getTrackFormat(aTrack)

            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vFormat)
            val aOut = muxer.addTrack(aFormat)
            muxer.start()
            started = true

            val bufSize = maxOf(
                1 shl 20,
                vFormat.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE),
                aFormat.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE)
            )
            val buffer = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()

            copyTrack(videoEx, muxer, vOut, buffer, info)
            copyTrack(audioEx, muxer, aOut, buffer, info)

            muxer.stop()
            started = false
            muxer.release()
            muxer = null

            if (!tmp.exists() || tmp.length() <= 0L) return false
            // Verify BEFORE renaming: a mux that lost the audio track must not
            // ship as the final file — the caller keeps the two sources then.
            if (!verifyMuxedOutput(tmp)) {
                Log.w(TAG, "merged output failed verification (audio/video track missing)")
                return false
            }
            if (outFile.exists()) outFile.delete()
            return tmp.renameTo(outFile)
        } catch (e: Exception) {
            Log.w(TAG, "mux failed: ${e.message}")
            return false
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoEx?.release() }
            runCatching { audioEx?.release() }
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun copyTrack(
        ex: MediaExtractor,
        muxer: MediaMuxer,
        outTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            muxer.writeSampleData(outTrack, buffer, info)
            ex.advance()
        }
    }
}
