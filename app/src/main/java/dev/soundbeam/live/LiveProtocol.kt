package dev.soundbeam.live

import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "SoundBeam Live": a deliberately minimal low-latency protocol for Watch Together.
 *
 * Raw 16-bit PCM in small UDP packets (256 frames ≈ 5.8 ms at 44.1 kHz), no codec, no TCP,
 * no retransmission. The receiver keeps a tiny adaptive jitter buffer (~40 ms). Over Wi-Fi or a
 * phone hotspot this adds well under 0.1 s, versus 1–3 s for AirPlay/Cast/DLNA buffering.
 *
 * Packet: "SBL1" | type:u8 | channels:u8 | frames:u16 | seq:u32 | sampleRate:u32 | payload
 *   type 0 = audio (payload = interleaved s16le), 1 = info (payload = UTF-8 sender name), 2 = stop
 * Discovery: sender broadcasts "SBL1?" to UDP [DISCOVERY_PORT]; receivers answer with JSON
 *   {"name": "...", "port": 12890}.
 */
object LiveProtocol {
    const val AUDIO_PORT = 12890
    const val DISCOVERY_PORT = 12891
    const val FRAMES_PER_PACKET = 256
    const val HEADER = 16
    const val TYPE_AUDIO = 0
    const val TYPE_INFO = 1
    const val TYPE_STOP = 2
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'B'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    val PROBE = "SBL1?".toByteArray()

    class Packet(val type: Int, val seq: Long, val sampleRate: Int, val channels: Int, val pcm: ShortArray, val text: String?)

    fun encode(type: Int, seq: Long, sampleRate: Int, channels: Int, pcm: ShortArray?, off: Int, frames: Int, gain: Float = 1f, text: String? = null): ByteArray {
        val payload = when {
            pcm != null -> frames * channels * 2
            text != null -> text.toByteArray().size
            else -> 0
        }
        val bb = ByteBuffer.allocate(HEADER + payload)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.put(MAGIC)
        bb.put(type.toByte())
        bb.put(channels.toByte())
        bb.putShort(frames.toShort())
        bb.putInt(seq.toInt())
        bb.putInt(sampleRate)
        if (pcm != null) {
            bb.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * channels) {
                val s = pcm[off + i]
                bb.putShort(if (gain == 1f) s else (s * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        } else if (text != null) {
            bb.put(text.toByteArray())
        }
        return bb.array()
    }

    fun decode(data: ByteArray, len: Int): Packet? {
        if (len < HEADER) return null
        for (i in 0 until 4) if (data[i] != MAGIC[i]) return null
        val bb = ByteBuffer.wrap(data, 0, len).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        val type = bb.get().toInt() and 0xFF
        val channels = (bb.get().toInt() and 0xFF).coerceIn(1, 2)
        val frames = bb.short.toInt() and 0xFFFF
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val rate = bb.int
        return when (type) {
            TYPE_AUDIO -> {
                val n = minOf(frames * channels, (len - HEADER) / 2)
                val pcm = ShortArray(n)
                bb.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) pcm[i] = bb.short
                Packet(type, seq, rate, channels, pcm, null)
            }
            TYPE_INFO -> Packet(type, seq, rate, channels, ShortArray(0), String(data, HEADER, len - HEADER))
            else -> Packet(type, seq, rate, channels, ShortArray(0), null)
        }
    }

    /** Broadcast addresses of every active IPv4 interface (Wi-Fi, hotspot, Ethernet). */
    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .forEach { nif -> nif.interfaceAddresses.mapNotNull { it.broadcast }.forEach { out += it } }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }

    data class Found(val name: String, val host: String, val port: Int)

    /** Finds phones that are in "Listen to a friend" mode on the same Wi-Fi or hotspot. */
    fun discover(timeoutMs: Int = 1500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 200
            repeat(2) {
                broadcastAddresses().forEach { a -> runCatching { s.send(DatagramPacket(PROBE, PROBE.size, a, DISCOVERY_PORT)) } }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val j = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                    val host = p.address.hostAddress ?: continue
                    found[host] = Found(j.optString("name", "Phone"), host, j.optInt("port", AUDIO_PORT))
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return found.values.toList()
    }
}

/** Sends live PCM to one listening phone. Pure JVM, so it can be tested without a device. */
class LiveSender(host: String, private val port: Int, private val senderName: String, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val address = InetAddress.getByName(host)
    private val socket = DatagramSocket()
    private var seq = 0L
    private val pending = ShortArray(LiveProtocol.FRAMES_PER_PACKET * channels)
    private var pendingFrames = 0
    @Volatile var gain = 1f

    /** Accepts any number of frames; emits a packet every [LiveProtocol.FRAMES_PER_PACKET] frames. */
    fun send(pcm: ShortArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = minOf(len - off, (LiveProtocol.FRAMES_PER_PACKET - pendingFrames) * channels)
            System.arraycopy(pcm, off, pending, pendingFrames * channels, n)
            pendingFrames += n / channels
            off += n
            if (pendingFrames == LiveProtocol.FRAMES_PER_PACKET) {
                val p = LiveProtocol.encode(
                    LiveProtocol.TYPE_AUDIO, seq++, sampleRate, channels, pending, 0, LiveProtocol.FRAMES_PER_PACKET, gain,
                )
                runCatching { socket.send(DatagramPacket(p, p.size, address, port)) }
                pendingFrames = 0
            }
        }
    }

    fun sendInfo() {
        val p = LiveProtocol.encode(LiveProtocol.TYPE_INFO, seq, sampleRate, channels, null, 0, 0, text = senderName)
        runCatching { socket.send(DatagramPacket(p, p.size, address, port)) }
    }

    fun sendStop() {
        val p = LiveProtocol.encode(LiveProtocol.TYPE_STOP, seq, sampleRate, channels, null, 0, 0)
        repeat(3) { runCatching { socket.send(DatagramPacket(p, p.size, address, port)) } }
    }

    override fun close() = socket.close()
}
