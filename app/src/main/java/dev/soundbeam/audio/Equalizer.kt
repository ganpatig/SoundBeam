package dev.soundbeam.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 10-band graphic equalizer built from RBJ-cookbook peaking biquads, applied in software to the
 * captured PCM before it is sent to any output, so the setting affects every receiver.
 */
class Equalizer(private val sampleRate: Int, private val channels: Int) {

    private class Band(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private class State(val bands: List<Band>, val preamp: Double, channels: Int) {
        val z1 = Array(channels) { DoubleArray(bands.size) }
        val z2 = Array(channels) { DoubleArray(bands.size) }
    }

    @Volatile
    private var state: State? = null

    /** Gains in dB, one per entry of [FREQS]. Safe to call from any thread. */
    fun setGains(gainsDb: List<Float>) {
        val bands = ArrayList<Band>()
        var maxBoost = 0f
        gainsDb.forEachIndexed { i, g ->
            if (i < FREQS.size && abs(g) > 0.05f) {
                bands.add(peaking(FREQS[i].toDouble(), g.toDouble()))
                maxBoost = max(maxBoost, g)
            }
        }
        // Back off by half of the largest boost so strong boosts clip less.
        val preamp = 10.0.pow(-(maxBoost / 2.0) / 20.0)
        state = if (bands.isEmpty()) null else State(bands, preamp, channels)
    }

    /** Processes [len] interleaved 16-bit samples in place. */
    fun process(buf: ShortArray, len: Int) {
        val st = state ?: return
        var i = 0
        while (i + channels <= len) {
            for (ch in 0 until channels) {
                var x = buf[i + ch] * st.preamp
                val z1 = st.z1[ch]
                val z2 = st.z2[ch]
                for (k in st.bands.indices) {
                    val b = st.bands[k]
                    val y = b.b0 * x + z1[k]
                    z1[k] = b.b1 * x - b.a1 * y + z2[k]
                    z2[k] = b.b2 * x - b.a2 * y
                    x = y
                }
                buf[i + ch] = x.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            i += channels
        }
    }

    private fun peaking(freq: Double, gainDb: Double): Band {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * Q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        return Band(
            b0 = (1.0 + alpha * a) / a0,
            b1 = (-2.0 * cosW) / a0,
            b2 = (1.0 - alpha * a) / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha / a) / a0,
        )
    }

    companion object {
        val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        private const val Q = 1.41 // roughly one octave per band

        val PRESETS: Map<String, List<Float>> = linkedMapOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
            "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
            "Loudness" to listOf(5f, 4f, 1f, 0f, -1f, 0f, 1f, 3f, 4f, 3f),
            "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
        )
    }
}
