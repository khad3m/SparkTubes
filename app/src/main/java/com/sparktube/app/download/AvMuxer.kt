package com.sparktube.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges a video-only file and an audio-only file into ONE playable .mp4 (or .webm
 * for VP9+Opus) with no re-encoding, so MX Player / Gallery / any external player
 * has both picture and sound.
 */
internal object AvMuxer {

    private const val TAG = "AvMuxer"

    fun isWebmPair(videoFile: File, audioFile: File): Boolean {
        val vMime = trackMime(videoFile, "video/")
        val aMime = trackMime(audioFile, "audio/")
        return vMime.contains("vp9", ignoreCase = true) ||
            vMime.contains("vp8", ignoreCase = true) ||
            aMime.contains("opus", ignoreCase = true) ||
            aMime.contains("vorbis", ignoreCase = true)
    }

    private fun trackMime(file: File, prefix: String): String {
        if (!file.exists() || file.length() < 256L) return ""
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith(prefix)) return mime
            }
            ""
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun hasTrack(file: File, prefix: String): Boolean =
        trackMime(file, prefix).isNotEmpty()

    /** Output sanity check: merged file must have video AND audio with actual readable samples. */
    private fun verifyMuxedOutput(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            }
            if (videoTrack < 0 || audioTrack < 0) return false
            val buf = ByteBuffer.allocate(64 * 1024)
            ex.selectTrack(videoTrack)
            val vSample = ex.readSampleData(buf, 0)
            ex.unselectTrack(videoTrack)
            buf.clear()
            ex.selectTrack(audioTrack)
            val aSample = ex.readSampleData(buf, 0)
            vSample > 0 && aSample > 0
        } catch (e: Exception) {
            Log.w(TAG, "verifyMuxedOutput failed on ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    fun merge(videoFile: File, audioFile: File, outFile: File): Boolean {
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
            val vMime = vFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            val aMime = aFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()

            val outputFormat = if (
                vMime.contains("vp9") || vMime.contains("vp8") ||
                aMime.contains("opus") || aMime.contains("vorbis")
            ) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(tmp.absolutePath, outputFormat)
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

            val vSamples = copyTrack(videoEx, muxer, vOut, buffer, info)
            val aSamples = copyTrack(audioEx, muxer, aOut, buffer, info)

            muxer.stop()
            started = false
            muxer.release()
            muxer = null

            if (vSamples <= 0 || aSamples <= 0 || !tmp.exists() || tmp.length() <= 0L) {
                Log.w(TAG, "mux produced insufficient samples: v=$vSamples a=$aSamples")
                return false
            }
            if (!verifyMuxedOutput(tmp)) {
                Log.w(TAG, "merged output failed verification (audio/video samples missing)")
                return false
            }
            if (outFile.exists()) outFile.delete()
            if (tmp.renameTo(outFile)) return true
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
            return outFile.exists() && outFile.length() > 0L
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
    ): Int {
        var count = 0
        var lastPtsUs = -1L
        while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            var pts = ex.sampleTime
            if (pts < 0L) {
                ex.advance()
                continue
            }
            if (pts < lastPtsUs) {
                pts = lastPtsUs
            }
            lastPtsUs = pts
            info.offset = 0
            info.size = size
            info.presentationTimeUs = pts
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            muxer.writeSampleData(outTrack, buffer, info)
            count++
            ex.advance()
        }
        return count
    }
}
