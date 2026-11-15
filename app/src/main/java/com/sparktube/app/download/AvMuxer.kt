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
 * Returns true on success. On any failure the output is deleted and the
 * original two files are left untouched.
 */
internal object AvMuxer {

    private const val TAG = "AvMuxer"

    fun merge(videoFile: File, audioFile: File, outFile: File): Boolean {
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
