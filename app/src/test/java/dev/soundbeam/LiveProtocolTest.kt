package dev.soundbeam

import dev.soundbeam.live.JitterBuffer
import dev.soundbeam.live.LiveProtocol
import dev.soundbeam.live.LiveSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LiveProtocolTest {

    @Test
    fun packetRoundTrip() {
        val pcm = ShortArray(512) { (it * 37 - 9000).toShort() }
        val bytes = LiveProtocol.encode(LiveProtocol.TYPE_AUDIO, 42, 44100, 2, pcm, 0, 256)
        assertEquals(LiveProtocol.HEADER + 1024, bytes.size)
        val p = LiveProtocol.decode(bytes, bytes.size)!!
        assertEquals(LiveProtocol.TYPE_AUDIO, p.type)
        assertEquals(42L, p.seq)
        assertEquals(44100, p.sampleRate)
        assertArrayEquals(pcm, p.pcm)

        val info = LiveProtocol.encode(LiveProtocol.TYPE_INFO, 0, 44100, 2, null, 0, 0, text = "Ganpati's S23")
        assertEquals("Ganpati's S23", LiveProtocol.decode(info, info.size)!!.text)
        assertNull(LiveProtocol.decode(ByteArray(20), 20))
    }

    @Test
    fun jitterBufferPrimesAtTargetThenPlays() {
        val jb = JitterBuffer(44100, 2, minTargetMs = 40)
        var seq = 0L
        // 256 frames ≈ 5.8 ms per packet; 40 ms needs 7 packets
        repeat(6) { jb.push(seq++, ShortArray(512)) }
        assertNull("must wait until 40 ms is buffered", jb.pull())
        jb.push(seq++, ShortArray(512))
        assertNotNull(jb.pull())
    }

    @Test
    fun jitterBufferConcealsLossAndBoundsDelay() {
        val jb = JitterBuffer(44100, 2, minTargetMs = 40)
        jb.push(0, ShortArray(512) { 1 })
        jb.push(3, ShortArray(512) { 1 }) // packets 1 and 2 lost -> two silent packets inserted
        assertEquals(4 * 256 * 1000 / 44100, jb.bufferedMs())
        // A burst of 100 packets (~580 ms) must never be kept: delay stays near target
        for (s in 4L until 104L) jb.push(s, ShortArray(512))
        assertTrue("buffered ${jb.bufferedMs()} ms", jb.bufferedMs() <= 40 + 30 + 6)
    }

    @Test
    fun jitterBufferGrowsTargetAfterUnderrun() {
        val jb = JitterBuffer(44100, 2, minTargetMs = 40)
        var seq = 0L
        repeat(8) { jb.push(seq++, ShortArray(512)) }
        while (jb.pull() != null) Unit // drain -> underrun
        assertEquals(1L, jb.underruns)
        assertEquals(50, jb.targetMs)
    }

    /** Real UDP over loopback: sender -> socket -> decoder -> jitter buffer. */
    @Test
    fun loopbackStreamDeliversAudioWithLowDelay() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { rx ->
            rx.soTimeout = 1000
            val sender = LiveSender("127.0.0.1", rx.localPort, "test", 44100, 2)
            val jb = JitterBuffer(44100, 2, minTargetMs = 40)
            val block = ShortArray(512) { (it % 200).toShort() } // what the capture thread hands over
            val buf = ByteArray(4096)
            var first: ShortArray? = null
            val start = System.nanoTime()
            var playableAtMs = -1.0
            // Feed 20 capture blocks (~116 ms of audio) in real time-ish
            repeat(20) {
                sender.send(block, block.size)
                val p = DatagramPacket(buf, buf.size)
                rx.receive(p)
                val pkt = LiveProtocol.decode(buf, p.length)!!
                jb.push(pkt.seq, pkt.pcm)
                if (playableAtMs < 0) {
                    jb.pull()?.let {
                        first = it
                        playableAtMs = (System.nanoTime() - start) / 1e6
                    }
                }
            }
            sender.close()
            assertArrayEquals(block, first)
            // Network + parsing overhead on loopback must be tiny; the buffer itself is the 40 ms target.
            println("Loopback: first audio playable after ${"%.1f".format(playableAtMs)} ms of sending (buffer target 40 ms)")
            assertTrue(playableAtMs in 0.0..200.0)
        }
    }
}
