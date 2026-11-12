package dev.soundbeam.airplay

import android.os.SystemClock
import dev.soundbeam.audio.Alac
import dev.soundbeam.audio.SAMPLE_RATE
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Shared AirPlay timeline. All AirPlay receivers (v1 and v2) get the same RTP packets, sequence
 * numbers and timestamps, anchored to one NTP clock, so they play in sync with each other.
 */
class AirPlayMaster(private val latencyMs: () -> Int) {

    private val timingSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    val timingPort: Int get() = timingSocket.localPort
    val controlPort: Int get() = controlSocket.localPort

    private val ssrc = Crypto.random.nextInt()
    private var seq = Crypto.random.nextInt(0xFFFF)
    private var rtpPos = Crypto.random.nextInt().toLong() and 0xFFFFFFFFL

    private val pending = ShortArray(Alac.FRAMES_PER_PACKET * 2)
    private var pendingFrames = 0
    private val history = arrayOfNulls<ByteArray>(HISTORY)

    private val sessions = CopyOnWriteArrayList<AirPlayOutput>()
    @Volatile private var running = false

    private val baseUnixNs = System.currentTimeMillis() * 1_000_000L
    private val baseElapsedNs = SystemClock.elapsedRealtimeNanos()

    fun start() {
        running = true
        thread(isDaemon = true, name = "airplay-timing") { timingLoop() }
        thread(isDaemon = true, name = "airplay-control") { controlLoop() }
        thread(isDaemon = true, name = "airplay-sync") {
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                sessions.forEach { sendSync(it, first = false) }
            }
        }
    }

    fun stop() {
        running = false
        timingSocket.close()
        controlSocket.close()
        sessions.clear()
    }

    /** Next (sequence number, rtptime) a newly joining session will receive. */
    @Synchronized
    fun nextPosition(): Pair<Int, Long> = seq to rtpPos

    @Synchronized
    fun activate(s: AirPlayOutput) {
        sendSync(s, first = true)
        sessions.add(s)
    }

    fun deactivate(s: AirPlayOutput) {
        sessions.remove(s)
    }

    /** Current rtptime of the newest captured audio, used for metadata RTP-Info headers. */
    @Synchronized
    fun currentRtpTime(): Long = rtpPos

    /** Feeds equalised 44.1 kHz stereo PCM from the capture thread. */
    fun write(pcm: ShortArray, len: Int) {
        if (sessions.isEmpty()) {
            // keep the clock moving so late joiners stay consistent
            synchronized(this) { rtpPos = (rtpPos + len / 2) and 0xFFFFFFFFL }
            return
        }
        var off = 0
        while (off < len) {
            val n = minOf(len - off, (Alac.FRAMES_PER_PACKET - pendingFrames) * 2)
            System.arraycopy(pcm, off, pending, pendingFrames * 2, n)
            pendingFrames += n / 2
            off += n
            if (pendingFrames == Alac.FRAMES_PER_PACKET) {
                emitPacket()
                pendingFrames = 0
            }
        }
    }

    private fun emitPacket() {
        val payload = Alac.encodeVerbatim(pending, 0, Alac.FRAMES_PER_PACKET)
        val pkt: ByteArray
        val s: Int
        synchronized(this) {
            s = seq
            pkt = ByteArray(12 + payload.size)
            pkt[0] = 0x80.toByte()
            pkt[1] = 0x60
            pkt[2] = (s shr 8).toByte(); pkt[3] = s.toByte()
            writeInt(pkt, 4, rtpPos)
            writeInt(pkt, 8, ssrc.toLong())
            System.arraycopy(payload, 0, pkt, 12, payload.size)
            history[s and (HISTORY - 1)] = pkt
            seq = (seq + 1) and 0xFFFF
            rtpPos = (rtpPos + Alac.FRAMES_PER_PACKET) and 0xFFFFFFFFL
        }
        for (session in sessions) session.sendPacket(s, pkt)
    }

    private fun sendSync(s: AirPlayOutput, first: Boolean) {
        val target = s.controlTarget() ?: return
        val latencySamples = latencyMs().toLong() * SAMPLE_RATE / 1000
        val next: Long
        synchronized(this) { next = rtpPos }
        val cur = (next - latencySamples - s.offsetSamples) and 0xFFFFFFFFL
        val p = ByteArray(20)
        p[0] = if (first) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xD4.toByte()
        p[2] = 0x00
        p[3] = 0x07
        writeInt(p, 4, cur)
        writeNtp(p, 8, nowNs())
        writeInt(p, 16, next)
        try {
            controlSocket.send(DatagramPacket(p, p.size, target.first, target.second))
        } catch (_: Exception) {
        }
    }

    private fun timingLoop() {
        val buf = ByteArray(128)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                timingSocket.receive(pkt)
                val recv = nowNs()
                if (pkt.length != 32 || buf[0] != 0x80.toByte() || buf[1] != 0xD2.toByte()) continue
                val res = ByteArray(32)
                res[0] = 0x80.toByte()
                res[1] = 0xD3.toByte()
                res[2] = buf[2]
                System.arraycopy(buf, 24, res, 8, 8)
                writeNtp(res, 16, recv)
                writeNtp(res, 24, nowNs())
                timingSocket.send(DatagramPacket(res, res.size, pkt.address, pkt.port))
            } catch (e: SocketException) {
                if (!running) break
            } catch (_: Exception) {
            }
        }
    }

    private fun controlLoop() {
        val buf = ByteArray(1500)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                controlSocket.receive(pkt)
                // Retransmit request: 80 d5 <seq> <first seq:2> <count:2>
                if (pkt.length < 8 || (buf[1].toInt() and 0x7F) != 0x55) continue
                val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                val session = sessions.firstOrNull { it.remoteAddress() == pkt.address } ?: continue
                for (i in 0 until minOf(count, HISTORY)) {
                    val s = (first + i) and 0xFFFF
                    val old = history[s and (HISTORY - 1)] ?: continue
                    val oldSeq = ((old[2].toInt() and 0xFF) shl 8) or (old[3].toInt() and 0xFF)
                    if (oldSeq == s) session.sendPacket(s, old)
                }
            } catch (e: SocketException) {
                if (!running) break
            } catch (_: Exception) {
            }
        }
    }

    fun nowNs(): Long = baseUnixNs + (SystemClock.elapsedRealtimeNanos() - baseElapsedNs)

    companion object {
        private const val HISTORY = 1024
        private const val NTP_EPOCH_OFFSET = 2208988800L

        fun writeInt(b: ByteArray, off: Int, v: Long) {
            b[off] = (v shr 24).toByte(); b[off + 1] = (v shr 16).toByte()
            b[off + 2] = (v shr 8).toByte(); b[off + 3] = v.toByte()
        }

        fun writeNtp(b: ByteArray, off: Int, unixNs: Long) {
            val sec = unixNs / 1_000_000_000L + NTP_EPOCH_OFFSET
            val frac = ((unixNs % 1_000_000_000L) shl 32) / 1_000_000_000L
            writeInt(b, off, sec)
            writeInt(b, off + 4, frac)
        }

        @Suppress("unused")
        fun address(host: String): InetAddress = InetAddress.getByName(host)
    }
}
