package dev.soundbeam

import dev.soundbeam.sync.SyncPlanner
import dev.soundbeam.sync.SyncSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SyncTest {
    private val rate = 48000

    /** A fake microphone recording: the pattern played by a speaker, heard in a real-ish room. */
    private fun recording(
        arrivalMs: Double,
        seconds: Double = 8.0,
        level: Double = 0.15,
        echoMs: Double = 23.0,
        noise: Double = 0.02,
        music: Double = 0.0,
        seed: Int = 1,
    ): ShortArray {
        val n = (rate * seconds).toInt()
        val sig = DoubleArray(n)
        val pattern = SyncSignal.pattern(rate, 1, amplitude = 1.0)
        val start = arrivalMs * rate / 1000
        fun add(at: Double, gain: Double) {
            val s0 = at.toInt()
            for (i in pattern.indices) {
                val j = s0 + i
                if (j in 0 until n) sig[j] += pattern[i] / 32767.0 * gain
            }
        }
        add(start, level)                                   // direct sound
        add(start + echoMs * rate / 1000, level * 0.6)      // wall reflection
        add(start + 2.7 * echoMs * rate / 1000, level * 0.35)
        val rnd = Random(seed)
        for (i in 0 until n) {
            sig[i] += (rnd.nextDouble() * 2 - 1) * noise
            if (music > 0) {
                val t = i.toDouble() / rate
                sig[i] += music * (0.5 * sin(2 * PI * 110 * t) + 0.3 * sin(2 * PI * 440 * t + 1) +
                    0.2 * sin(2 * PI * 2637 * t) * sin(2 * PI * 0.7 * t))
            }
        }
        return ShortArray(n) { (sig[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun detectedMs(rec: ShortArray) = SyncSignal.detect(rec, rate)?.let { it * 1000 / rate }

    @Test
    fun findsDirectSoundNotEcho() {
        val ms = detectedMs(recording(arrivalMs = 1834.0))
        assertNotNull(ms)
        assertTrue("got $ms", abs(ms!! - 1834.0) < 1.0)
    }

    @Test
    fun worksWithLoudMusicAndNoise() {
        val ms = detectedMs(recording(arrivalMs = 3210.5, level = 0.08, noise = 0.05, music = 0.25, seed = 7))
        assertNotNull(ms)
        assertTrue("got $ms", abs(ms!! - 3210.5) < 1.0)
    }

    @Test
    fun quietRoomWithoutPatternGivesNothing() {
        assertNull(detectedMs(recording(arrivalMs = 99_999.0, noise = 0.05, music = 0.2)))
    }

    @Test
    fun planLinesEveryoneUpWithTheSlowest() {
        // AirPlay heard after 0.8 s, Sonos after 2.9 s, Chromecast after 2.1 s (no delays yet)
        val plan = SyncPlanner.plan(
            mapOf("airplay" to 800.0, "sonos" to 2900.0, "cast" to 2100.0),
            emptyMap(),
        )
        assertEquals(2100, plan["airplay"])
        assertEquals(0, plan["sonos"])
        assertEquals(800, plan["cast"])
    }

    @Test
    fun planAccountsForExistingDelays() {
        // AirPlay already held back 2000 ms and now arrives 100 ms after Sonos: reduce it
        val plan = SyncPlanner.plan(mapOf("airplay" to 3000.0, "sonos" to 2900.0), mapOf("airplay" to 2000))
        // Sonos is now the slowest (gets 0); AirPlay keeps 1900 ms so both arrive together
        assertEquals(1900, plan["airplay"])
        assertEquals(0, plan["sonos"])
    }
}
