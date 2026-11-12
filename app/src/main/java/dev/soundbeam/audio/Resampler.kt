package dev.soundbeam.audio

/** Streaming linear-interpolation resampler for interleaved stereo 16-bit PCM. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0          // fractional read position relative to the current input block
    private var lastL = 0
    private var lastR = 0

    fun process(input: ShortArray, len: Int): ShortArray {
        val inFrames = len / 2
        if (inFrames == 0) return ShortArray(0)
        val out = ShortArray(((inFrames / step) + 2).toInt() * 2)
        var o = 0
        // pos is measured so that index -1 refers to the last frame of the previous block
        while (pos < inFrames - 1 + 1e-9) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = pos - i
            val l0: Int; val r0: Int
            if (i < 0) { l0 = lastL; r0 = lastR } else { l0 = input[i * 2].toInt(); r0 = input[i * 2 + 1].toInt() }
            val l1 = input[(i + 1) * 2].toInt()
            val r1 = input[(i + 1) * 2 + 1].toInt()
            if (o + 1 >= out.size) break
            out[o++] = (l0 + (l1 - l0) * frac).toInt().toShort()
            out[o++] = (r0 + (r1 - r0) * frac).toInt().toShort()
            pos += step
        }
        pos -= inFrames
        lastL = input[(inFrames - 1) * 2].toInt()
        lastR = input[(inFrames - 1) * 2 + 1].toInt()
        return out.copyOf(o)
    }
}
