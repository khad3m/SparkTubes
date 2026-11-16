package com.sparktube.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time STFT Spectral + Mid-Side AudioProcessor for ExoPlayer:
 * - [Mode.ONLY_VOCAL]: 1024-point Hann-windowed 50%-overlap STFT spectral vocal
 *   isolator. For every frequency bin independently, computes Mid (L+R) vs Side (L-R)
 *   energy, applies a steep Wiener center-channel mask (suppresses stereo instruments
 *   per-bin), hard-cuts < 300 Hz (kick/bass) and > 4.5 kHz (cymbals/hi-hats), and
 *   applies a spectral vocal-formant gate so only pure human vocals remain.
 * - [Mode.NOISE_CANCEL]: 85 Hz rumble high-pass + 7.2 kHz hiss low-pass + smooth gate.
 */
@OptIn(UnstableApi::class)
object SparkAudioProcessor : BaseAudioProcessor() {

    enum class Mode { OFF, ONLY_VOCAL, NOISE_CANCEL }

    @Volatile
    var mode: Mode = Mode.OFF

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 512

    // Precomputed Hann window & Radix-2 FFT twiddle tables
    private val hann = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / FFT_SIZE))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE).also { tbl ->
        var j = 0
        for (i in 0 until FFT_SIZE) {
            tbl[i] = j
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
        }
    }
    private val cosTable = FloatArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val sinTable = FloatArray(FFT_SIZE / 2) { sin(2.0 * PI * it / FFT_SIZE).toFloat() }

    // STFT sliding input & overlap-add output buffers
    private val inL = FloatArray(FFT_SIZE)
    private val inR = FloatArray(FFT_SIZE)
    private var inFill = 0
    private val olaOut = FloatArray(FFT_SIZE)
    private val outReady = FloatArray(HOP_SIZE)
    private var outReadyLen = 0

    // FFT scratch arrays
    private val reL = FloatArray(FFT_SIZE)
    private val imL = FloatArray(FFT_SIZE)
    private val reR = FloatArray(FFT_SIZE)
    private val imR = FloatArray(FFT_SIZE)
    private val vocalBandWeight = FloatArray(FFT_SIZE / 2 + 1)

    // Noise-cancel biquad states
    private var hpX1 = 0f; private var hpX2 = 0f; private var hpY1 = 0f; private var hpY2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f; private var lpY1 = 0f; private var lpY2 = 0f
    private var hpB0 = 1f; private var hpB1 = 0f; private var hpB2 = 0f; private var hpA1 = 0f; private var hpA2 = 0f
    private var lpB0 = 1f; private var lpB1 = 0f; private var lpB2 = 0f; private var lpA1 = 0f; private var lpA2 = 0f
    private var envMono = 0f
    private var gateGain = 1f

    private var configuredSampleRate = 44100
    private var lastModeConfigured: Mode? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configuredSampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        buildVocalBandWeights(configuredSampleRate)
        buildNoiseCancelFilters(configuredSampleRate)
        lastModeConfigured = mode
        return inputAudioFormat
    }

    override fun onFlush() = resetState()
    override fun onReset() = resetState()

    private fun resetState() {
        inL.fill(0f)
        inR.fill(0f)
        inFill = 0
        olaOut.fill(0f)
        outReady.fill(0f)
        outReadyLen = 0
        hpX1 = 0f; hpX2 = 0f; hpY1 = 0f; hpY2 = 0f
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        envMono = 0f
        gateGain = 1f
    }

    private fun buildVocalBandWeights(sampleRate: Int) {
        val binHz = sampleRate.toFloat() / FFT_SIZE
        for (k in 0..FFT_SIZE / 2) {
            val f = k * binHz
            vocalBandWeight[k] = when {
                f < 280f -> 0f
                f < 430f -> {
                    val t = (f - 280f) / 150f
                    0.5f * (1f - cos(PI.toFloat() * t))
                }
                f <= 3400f -> {
                    // Slight presence contour around 1.2k - 2.6k Hz for crisp human voice
                    if (f in 1100f..2700f) 1.18f else 1.0f
                }
                f < 4500f -> {
                    val t = (f - 3400f) / 1100f
                    0.5f * (1f + cos(PI.toFloat() * t))
                }
                else -> 0f
            }
        }
    }

    private fun buildNoiseCancelFilters(sampleRate: Int) {
        val sr = sampleRate.toDouble()
        run {
            val w0 = 2.0 * PI * 85.0 / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * 0.7071)
            val a0 = 1.0 + alpha
            hpB0 = ((1.0 + cosW) / 2.0 / a0).toFloat()
            hpB1 = (-(1.0 + cosW) / a0).toFloat()
            hpB2 = ((1.0 + cosW) / 2.0 / a0).toFloat()
            hpA1 = (-2.0 * cosW / a0).toFloat()
            hpA2 = ((1.0 - alpha) / a0).toFloat()
        }
        run {
            val w0 = 2.0 * PI * 7200.0 / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * 0.7071)
            val a0 = 1.0 + alpha
            lpB0 = ((1.0 - cosW) / 2.0 / a0).toFloat()
            lpB1 = ((1.0 - cosW) / a0).toFloat()
            lpB2 = ((1.0 - cosW) / 2.0 / a0).toFloat()
            lpA1 = (-2.0 * cosW / a0).toFloat()
            lpA2 = ((1.0 - alpha) / a0).toFloat()
        }
    }

    private fun runNoiseCancelSample(sample: Float): Float {
        val hp = hpB0 * sample + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2
        hpX2 = hpX1; hpX1 = sample
        hpY2 = hpY1; hpY1 = hp
        val lp = lpB0 * hp + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
        lpX2 = lpX1; lpX1 = hp
        lpY2 = lpY1; lpY1 = lp
        return lp
    }

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len shr 1
            val step = FFT_SIZE / len
            var i = 0
            while (i < FFT_SIZE) {
                for (k in 0 until half) {
                    val idx = k * step
                    val c = cosTable[idx]
                    val s = if (inverse) sinTable[idx] else -sinTable[idx]
                    val tRe = re[i + k + half] * c - im[i + k + half] * s
                    val tIm = re[i + k + half] * s + im[i + k + half] * c
                    re[i + k + half] = re[i + k] - tRe
                    im[i + k + half] = im[i + k] - tIm
                    re[i + k] += tRe
                    im[i + k] += tIm
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val invN = 1f / FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    /** Processes one 1024-sample Hann-windowed frame and updates [outReady] with [HOP_SIZE] vocal samples. */
    private fun processVocalFrame(stereo: Boolean) {
        for (i in 0 until FFT_SIZE) {
            val w = hann[i]
            reL[i] = inL[i] * w
            imL[i] = 0f
            if (stereo) {
                reR[i] = inR[i] * w
                imR[i] = 0f
            }
        }
        fft(reL, imL, inverse = false)
        if (stereo) fft(reR, imR, inverse = false)

        // First pass: find peak vocal-band mid energy for adaptive spectral gate
        var peakMag = 1e-6f
        for (k in 0..FFT_SIZE / 2) {
            if (vocalBandWeight[k] <= 0f) continue
            val mRe = if (stereo) 0.5f * (reL[k] + reR[k]) else reL[k]
            val mIm = if (stereo) 0.5f * (imL[k] + imR[k]) else imL[k]
            val mag = sqrt(mRe * mRe + mIm * mIm)
            if (mag > peakMag) peakMag = mag
        }
        val gateThresh = peakMag * 0.10f

        // Second pass: per-bin Wiener center-channel mask + vocal bandpass + spectral gate
        for (k in 0..FFT_SIZE / 2) {
            val bandW = vocalBandWeight[k]
            if (bandW <= 0f) {
                reL[k] = 0f
                imL[k] = 0f
            } else {
                val mRe = if (stereo) 0.5f * (reL[k] + reR[k]) else reL[k]
                val mIm = if (stereo) 0.5f * (imL[k] + imR[k]) else imL[k]
                val midPow = mRe * mRe + mIm * mIm

                val centerMask = if (stereo) {
                    val sRe = 0.5f * (reL[k] - reR[k])
                    val sIm = 0.5f * (imL[k] - imR[k])
                    val sidePow = sRe * sRe + sIm * sIm
                    val ratio = midPow / (midPow + 6.5f * sidePow + 1e-8f)
                    ratio * ratio
                } else {
                    1f
                }

                val mag = sqrt(midPow)
                val gate = if (mag < gateThresh) (mag / gateThresh).pow(2f) else 1f
                val totalGain = bandW * centerMask * gate * 1.6f

                reL[k] = mRe * totalGain
                imL[k] = mIm * totalGain
            }
            if (k in 1 until FFT_SIZE / 2) {
                reL[FFT_SIZE - k] = reL[k]
                imL[FFT_SIZE - k] = -imL[k]
            }
        }

        fft(reL, imL, inverse = true)

        // Hann-windowed 50%-overlap add (sum of squared Hann with 50% hop is 0.75 -> scale by 1/0.75)
        for (i in 0 until FFT_SIZE) {
            olaOut[i] += reL[i] * hann[i] * 1.3333f
        }
        for (i in 0 until HOP_SIZE) {
            outReady[i] = olaOut[i]
        }
        System.arraycopy(olaOut, HOP_SIZE, olaOut, 0, HOP_SIZE)
        olaOut.fill(0f, HOP_SIZE, FFT_SIZE)
        outReadyLen = HOP_SIZE

        // Shift input window by HOP_SIZE
        System.arraycopy(inL, HOP_SIZE, inL, 0, HOP_SIZE)
        if (stereo) System.arraycopy(inR, HOP_SIZE, inR, 0, HOP_SIZE)
        inFill = HOP_SIZE
    }

    private fun toShortClamped(v: Float): Short =
        (v * 32767f).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val activeMode = mode
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (activeMode == Mode.OFF) {
            if (lastModeConfigured != Mode.OFF) {
                resetState()
                lastModeConfigured = Mode.OFF
            }
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (lastModeConfigured != activeMode) {
            resetState()
            lastModeConfigured = activeMode
        }

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val stereo = channels == 2

        if (activeMode == Mode.ONLY_VOCAL) {
            val maxOutBytes = ((remaining / (if (stereo) 4 else 2)) + FFT_SIZE) * (if (stereo) 4 else 2)
            val out = replaceOutputBuffer(maxOutBytes)

            while (inputBuffer.remaining() >= (if (stereo) 4 else 2)) {
                inL[inFill] = inputBuffer.short / 32768f
                if (stereo) {
                    inR[inFill] = inputBuffer.short / 32768f
                }
                inFill++
                if (inFill == FFT_SIZE) {
                    processVocalFrame(stereo)
                    for (i in 0 until outReadyLen) {
                        val s = toShortClamped(outReady[i])
                        out.putShort(s)
                        if (stereo) out.putShort(s)
                    }
                    outReadyLen = 0
                }
            }
            out.flip()
            return
        }

        // Mode.NOISE_CANCEL
        val out = replaceOutputBuffer(remaining)
        if (stereo) {
            while (inputBuffer.remaining() >= 4) {
                val l = inputBuffer.short / 32768f
                val r = inputBuffer.short / 32768f
                val mono = 0.5f * (l + r)
                envMono = envMono * 0.95f + abs(mono) * 0.05f
                val targetGate = if (envMono < 0.0045f) 0.12f else 1.0f
                gateGain = gateGain * 0.94f + targetGate * 0.06f

                val clean = runNoiseCancelSample(mono) * gateGain
                val diff = 0.5f * (l - r) * gateGain * 0.85f
                out.putShort(toShortClamped(clean + diff))
                out.putShort(toShortClamped(clean - diff))
            }
        } else {
            while (inputBuffer.remaining() >= 2) {
                val x = inputBuffer.short / 32768f
                envMono = envMono * 0.95f + abs(x) * 0.05f
                val targetGate = if (envMono < 0.0045f) 0.12f else 1.0f
                gateGain = gateGain * 0.94f + targetGate * 0.06f
                out.putShort(toShortClamped(runNoiseCancelSample(x) * gateGain))
            }
        }
        while (inputBuffer.hasRemaining()) {
            out.put(inputBuffer.get())
        }
        out.flip()
    }
}
