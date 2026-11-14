package dev.soundbeam.sync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Test signal and detection for automatic speaker sync (pure Kotlin, unit-tested on the JVM).
 *
 * Each speaker plays three short frequency sweeps ("chirps"). A chirp correlates into a single
 * sharp peak, so its arrival time in the microphone recording can be found to well under a
 * millisecond even with music or talking in the room. Requiring the three-chirp rhythm rejects
 * random noise peaks; taking the earliest matching peak ignores room echoes, which arrive later.
 */
object SyncSignal {
    const val CHIRPS = 3
    const val CHIRP_MS = 120
    const val SPACING_MS = 480
    const val F0 = 1000.0
    const val F1 = 6000.0
    const val PATTERN_MS = SPACING_MS * (CHIRPS - 1) + CHIRP_MS

    /** One windowed linear sweep from [F0] to [F1]. */
    fun chirp(rate: Int): DoubleArray {
        val n = rate * CHIRP_MS / 1000
        val T = CHIRP_MS / 1000.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            val phase = 2 * PI * (F0 * t + (F1 - F0) * t * t / (2 * T))
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1)) // Hann window: no clicks
            sin(phase) * w
        }
    }

    /** The full three-chirp pattern as interleaved 16-bit PCM. */
    fun pattern(rate: Int, channels: Int, amplitude: Double = 0.5): ShortArray {
        val c = chirp(rate)
        val frames = rate * PATTERN_MS / 1000 + 1
        val out = ShortArray(frames * channels)
        for (k in 0 until CHIRPS) {
            val start = rate * SPACING_MS * k / 1000
            for (i in c.indices) {
                val s = (c[i] * amplitude * 32767).roundToInt().toShort()
                for (ch in 0 until channels) out[(start + i) * channels + ch] = s
            }
        }
        return out
    }

    /**
     * Finds where the first chirp of the pattern starts in a mono [mic] recording.
     * Returns the sample index (fractional), or null if the pattern isn't clearly there.
     */
    fun detect(mic: ShortArray, rate: Int): Double? {
        val ref = chirp(rate)
        if (mic.size < ref.size * 2) return null
        val corr = crossCorrelate(mic, ref)
        val env = DoubleArray(corr.size) { abs(corr[it]) }
        val globalMax = env.maxOrNull() ?: return null
        if (globalMax <= 0.0) return null
        val noise = median(env)
        val threshold = max(0.25 * globalMax, 8.0 * noise)

        // Local maxima above threshold, at least 60 ms apart (strongest wins inside a window)
        val minGap = rate * 60 / 1000
        val peaks = ArrayList<Int>()
        var i = 0
        while (i < env.size) {
            if (env[i] >= threshold) {
                var best = i
                val end = minOf(env.size, i + minGap)
                for (j in i until end) if (env[j] > env[best]) best = j
                peaks += best
                i = best + minGap
            } else {
                i++
            }
        }
        val spacing = rate * SPACING_MS / 1000
        val tol = rate * 15 / 1000
        fun near(target: Int, minLevel: Double) =
            peaks.any { abs(it - target) <= tol && env[it] >= minLevel }
        for (p in peaks) {
            val level = env[p] * 0.4
            if (near(p + spacing, level) && near(p + 2 * spacing, level)) return refine(env, p)
        }
        return null
    }

    /** Parabolic interpolation around a peak for sub-sample precision. */
    private fun refine(env: DoubleArray, p: Int): Double {
        if (p <= 0 || p >= env.size - 1) return p.toDouble()
        val a = env[p - 1]
        val b = env[p]
        val c = env[p + 1]
        val d = a - 2 * b + c
        return if (d == 0.0) p.toDouble() else p + 0.5 * (a - c) / d
    }

    private fun median(v: DoubleArray): Double {
        val step = maxOf(1, v.size / 20000)
        val sample = DoubleArray(v.size / step) { v[it * step] }
        sample.sort()
        return sample[sample.size / 2]
    }

    /** corr[k] = Σ mic[k+i]·ref[i], computed with an FFT. */
    fun crossCorrelate(mic: ShortArray, ref: DoubleArray): DoubleArray {
        var n = 1
        while (n < mic.size + ref.size) n = n shl 1
        val aRe = DoubleArray(n)
        val aIm = DoubleArray(n)
        val bRe = DoubleArray(n)
        val bIm = DoubleArray(n)
        for (i in mic.indices) aRe[i] = mic[i].toDouble()
        for (i in ref.indices) bRe[i] = ref[i]
        fft(aRe, aIm, false)
        fft(bRe, bIm, false)
        for (i in 0 until n) {
            // a * conj(b)
            val re = aRe[i] * bRe[i] + aIm[i] * bIm[i]
            val im = aIm[i] * bRe[i] - aRe[i] * bIm[i]
            aRe[i] = re
            aIm[i] = im
        }
        fft(aRe, aIm, true)
        val len = mic.size - ref.size + 1
        return DoubleArray(len) { aRe[it] / n }
    }

    /** In-place iterative radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Turns measured arrival times into per-group delays so everything lines up with the slowest. */
object SyncPlanner {
    const val MAX_DELAY_MS = 8000

    /**
     * [measuredMs]: group → time from sending the test sound until the mic heard it, measured
     * with the group's [currentDelayMs] already in effect. Returns the new delay per group;
     * the latest group gets 0 extra and the smallest resulting delay is normalised to 0.
     */
    fun plan(measuredMs: Map<String, Double>, currentDelayMs: Map<String, Int>): Map<String, Int> {
        if (measuredMs.isEmpty()) return emptyMap()
        val latest = measuredMs.values.max()
        val raw = measuredMs.mapValues { (g, ms) -> (currentDelayMs[g] ?: 0) + (latest - ms) }
        val floor = raw.values.min()
        return raw.mapValues { (_, d) -> (d - floor).roundToInt().coerceIn(0, MAX_DELAY_MS) }
    }
}
