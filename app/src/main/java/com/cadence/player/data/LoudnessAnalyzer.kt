package com.cadence.player.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

data class Measured(val gainDb: Float, val peak: Float)

/**
 * Estimates ReplayGain for a file that has no tags: decodes up to 90 s (starting about a third of the way in,
 * to skip quiet intros), applies the ITU-R BS.1770 K-weighting filter, gates quiet blocks like EBU R128, and
 * converts the integrated loudness to a ReplayGain 2.0 value (reference -18 LUFS).
 * It measures part of the song rather than all of it, which is accurate enough for level matching.
 */
object LoudnessAnalyzer {
    private const val WINDOW_US = 90_000_000L

    fun measure(context: Context, uri: Uri): Measured? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = if (durationUs > WINDOW_US * 2) durationUs / 3 else 0L
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val meter = LoudnessMeter()
            meter.configure(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var spins = 0
            while (!outputDone && spins++ < 400_000) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleTime = extractor.sampleTime
                        val size = if (sampleTime < 0 || sampleTime - startUs > WINDOW_US) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val out = decoder.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            meter.feed(out, floatPcm)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        floatPcm = out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        meter.configure(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                }
            }
            return meter.result()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

private class LoudnessMeter {
    private var channels = 2
    private var used = 2
    private var blockLen = 19_200
    private var shelf = arrayOf<Biquad>()
    private var highpass = arrayOf<Biquad>()
    private val sumSq = DoubleArray(2)
    private var frames = 0
    private val blocks = ArrayList<Double>()
    private var peak = 0f

    fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount.coerceAtLeast(1)
        used = channels.coerceAtMost(2)
        blockLen = (0.4 * sampleRate).toInt().coerceAtLeast(1)
        shelf = Array(used) { highShelf(sampleRate.toDouble()) }
        highpass = Array(used) { highPass(sampleRate.toDouble()) }
        sumSq.fill(0.0)
        frames = 0
    }

    fun feed(buf: ByteBuffer, floatPcm: Boolean) {
        buf.order(ByteOrder.nativeOrder())
        val frameBytes = (if (floatPcm) 4 else 2) * channels
        while (buf.remaining() >= frameBytes) {
            for (c in 0 until channels) {
                val s = if (floatPcm) buf.getFloat().toDouble() else buf.getShort() / 32768.0
                if (c < used) {
                    peak = max(peak, abs(s).toFloat())
                    val y = highpass[c].process(shelf[c].process(s))
                    sumSq[c] += y * y
                }
            }
            if (++frames >= blockLen) {
                var z = 0.0
                for (c in 0 until used) z += sumSq[c] / blockLen
                blocks += z
                frames = 0
                sumSq.fill(0.0)
            }
        }
    }

    fun result(): Measured? {
        fun lufs(z: Double) = 10 * log10(z) - 0.691
        val aboveAbsolute = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = lufs(aboveAbsolute.average()) - 10.0
        val gated = aboveAbsolute.filter { lufs(it) > relativeGate }
        if (gated.isEmpty()) return null
        val loudness = lufs(gated.average())
        return Measured((-18.0 - loudness).toFloat().coerceIn(-30f, 30f), peak)
    }

    // BS.1770 stage 1 (head-related high shelf) and stage 2 (RLB high-pass), for any sample rate.
    private fun highShelf(fs: Double): Biquad {
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0,
        )
    }

    private fun highPass(fs: Double): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / fs)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }
}
