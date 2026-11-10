package com.cadence.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cadence.player.data.RgMode
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

data class GainInfo(
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val trackPeak: Float?,
    val albumPeak: Float?,
)

object ReplayGain {
    /** Linear multiplier for a track given the user's settings. */
    fun linearGain(info: GainInfo?, mode: RgMode, preampDb: Float, preventClipping: Boolean): Float {
        if (mode == RgMode.OFF) return 1f
        val useAlbum = mode == RgMode.ALBUM && info?.albumGainDb != null
        val gainDb = (if (useAlbum) info?.albumGainDb else info?.trackGainDb) ?: 0f
        val peak = if (useAlbum) info?.albumPeak else info?.trackPeak
        var linear = 10.0.pow(((gainDb + preampDb) / 20f).toDouble()).toFloat()
        if (preventClipping && peak != null && peak > 0f) linear = min(linear, 1f / peak)
        return linear
    }

    fun toDb(linear: Float): Float = (20.0 * log10(linear.toDouble())).toFloat()
}

/**
 * Applies a per-track gain to PCM.
 *
 * The processor is handed the gain for every item in the queue plus a cursor. At each natural end of stream
 * (gapless item boundary) the cursor advances, so the new gain applies to the very first sample of the next
 * track instead of up to a few hundred ms late. The player also corrects the cursor on every media item
 * transition, so a missed boundary self-heals.
 */
@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {
    @Volatile private var gains = floatArrayOf(1f)
    @Volatile private var cursor = 0

    fun setSequence(sequence: FloatArray, startCursor: Int) {
        gains = if (sequence.isEmpty()) floatArrayOf(1f) else sequence
        cursor = startCursor
    }

    fun setCursor(index: Int) {
        cursor = index
    }

    private fun currentGain(): Float {
        val g = gains
        return g[cursor.coerceIn(0, g.lastIndex)]
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET // inactive: other encodings pass through untouched
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val g = currentGain()
        val out = replaceOutputBuffer(inputBuffer.remaining())
        if (g == 1f) {
            out.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                val v = inputBuffer.getFloat() * g
                out.putFloat(if (g > 1f) soften(v) else v.coerceIn(-1f, 1f))
            }
        } else if (g > 1f) {
            // Boosting: peaks can exceed full scale, so roll them off smoothly instead of hard clipping.
            while (inputBuffer.remaining() >= 2) {
                val v = soften(inputBuffer.getShort() / 32768f * g)
                out.putShort((v * 32767f).toInt().toShort())
            }
        } else {
            while (inputBuffer.remaining() >= 2) {
                val s = inputBuffer.getShort().toInt()
                out.putShort((s * g).toInt().toShort())
            }
        }
        out.flip()
    }

    /** Linear below 0.9, then a tanh knee that tops out at 1.0. */
    private fun soften(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0f) -y else y
    }

    override fun onQueueEndOfStream() {
        if (cursor < gains.lastIndex) cursor++
    }
}
