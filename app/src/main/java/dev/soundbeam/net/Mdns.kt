package dev.soundbeam.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A DNS-SD service instance found via mDNS. */
data class MdnsService(
    val type: String,      // e.g. "_airplay._tcp.local"
    val instance: String,  // first label, e.g. "Living Room"
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
)

/** Small mDNS / DNS-SD browser (PTR → SRV/TXT/A) that needs no system service or library. */
object Mdns {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353

    private class Collected {
        val ptr = HashMap<String, MutableSet<String>>()            // type -> instance full names
        val srv = HashMap<String, Pair<String, Int>>()             // instance -> (target, port)
        val txt = HashMap<String, Map<String, String>>()           // instance -> txt
        val a = HashMap<String, String>()                          // hostname -> ip
        val source = HashMap<String, String>()                     // instance -> responder ip
    }

    suspend fun browse(ctx: Context, types: List<String>, timeoutMs: Long = 3000): List<MdnsService> =
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("soundbeam-mdns").apply { setReferenceCounted(false); acquire() }
            val c = Collected()
            try {
                val sock = MulticastSocket(null)
                sock.reuseAddress = true
                try {
                    sock.bind(InetSocketAddress(PORT))
                } catch (e: Exception) {
                    sock.close()
                    return@withContext browseUnicast(types, timeoutMs, c)
                }
                sock.use { s ->
                    s.soTimeout = 250
                    val iface = localIpAddress(ctx)?.let { ip ->
                        runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(ip)) }.getOrNull()
                    }
                    runCatching {
                        if (iface != null) s.joinGroup(InetSocketAddress(GROUP, PORT), iface) else @Suppress("DEPRECATION") s.joinGroup(GROUP)
                    }
                    val q = query(types.map { it to TYPE_PTR })
                    // Query from 5353 (multicast answers) and also from an ephemeral port (legacy unicast answers)
                    val uni = java.net.DatagramSocket().apply { soTimeout = 250 }
                    uni.use {
                        repeat(2) {
                            s.send(DatagramPacket(q, q.size, GROUP, PORT))
                            uni.send(DatagramPacket(q, q.size, GROUP, PORT))
                            receiveInto(listOf(s, uni), c, timeoutMs / 2)
                        }
                        followUp(listOf(s, uni), s, c, types)
                    }
                }
            } finally {
                if (lock.isHeld) lock.release()
            }
            assemble(c, types)
        }

    private fun browseUnicast(types: List<String>, timeoutMs: Long, c: Collected): List<MdnsService> {
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 250
            val q = query(types.map { it to TYPE_PTR })
            repeat(2) {
                s.send(DatagramPacket(q, q.size, GROUP, PORT))
                receiveInto(listOf(s), c, timeoutMs / 2)
            }
            followUp(listOf(s), s, c, types)
        }
        return assemble(c, types)
    }

    /** Ask directly for SRV/TXT/A records that did not arrive as additional records. */
    private fun followUp(socks: List<java.net.DatagramSocket>, sender: java.net.DatagramSocket, c: Collected, types: List<String>) {
        val qs = ArrayList<Pair<String, Int>>()
        for (t in types) for (inst in c.ptr[t].orEmpty()) {
            if (inst !in c.srv) qs.add(inst to TYPE_SRV)
            if (inst !in c.txt) qs.add(inst to TYPE_TXT)
            val target = c.srv[inst]?.first
            if (target != null && target !in c.a) qs.add(target to TYPE_A)
        }
        if (qs.isEmpty()) return
        qs.chunked(10).forEach { chunk ->
            val q = query(chunk)
            runCatching { sender.send(DatagramPacket(q, q.size, GROUP, PORT)) }
        }
        receiveInto(socks, c, 1200)
        // A records for SRV targets learned in the follow-up
        val missingA = c.srv.values.map { it.first }.filter { it !in c.a }.distinct()
        if (missingA.isNotEmpty()) {
            val q = query(missingA.map { it to TYPE_A })
            runCatching { sender.send(DatagramPacket(q, q.size, GROUP, PORT)) }
            receiveInto(socks, c, 800)
        }
    }

    private fun receiveInto(socks: List<java.net.DatagramSocket>, c: Collected, durationMs: Long) {
        val end = System.currentTimeMillis() + durationMs
        val buf = ByteArray(9000)
        while (System.currentTimeMillis() < end) {
            for (s in socks) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    runCatching { parse(buf.copyOf(p.length), p.address.hostAddress ?: "", c) }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
    }

    private fun assemble(c: Collected, types: List<String>): List<MdnsService> {
        val out = ArrayList<MdnsService>()
        for (t in types) for (inst in c.ptr[t].orEmpty()) {
            val srv = c.srv[inst] ?: continue
            val host = c.a[srv.first] ?: c.source[inst] ?: continue
            val label = inst.removeSuffix(".$t")
            out.add(MdnsService(t, label, host, srv.second, c.txt[inst].orEmpty()))
        }
        return out
    }

    // --------------------------------------------------------------------- DNS wire format

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    private fun query(questions: List<Pair<String, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, questions.size.toByte(), 0, 0, 0, 0, 0, 0))
        for ((name, type) in questions) {
            writeName(out, name)
            out.write(0); out.write(type)
            out.write(0); out.write(1) // class IN (QM)
        }
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        // Instance labels may contain dots; split only the known DNS-SD suffix structure.
        val labels = splitName(name)
        for (l in labels) {
            val b = l.toByteArray()
            out.write(minOf(b.size, 63))
            out.write(b, 0, minOf(b.size, 63))
        }
        out.write(0)
    }

    private fun splitName(name: String): List<String> {
        val m = Regex("^(.*)\\.(_[^.]+)\\.(_tcp|_udp)\\.local$").find(name)
        return if (m != null && m.groupValues[1].isNotEmpty()) {
            listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], "local")
        } else {
            name.split('.').filter { it.isNotEmpty() }
        }
    }

    private fun readName(d: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = d[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len and 0xC0 == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
                if (end < 0) end = pos + 2
                pos = ptr
                if (++jumps > 20) break
                continue
            }
            labels.add(String(d, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }
        return labels.joinToString(".") to (if (end >= 0) end else pos)
    }

    private fun parse(d: ByteArray, from: String, c: Collected) {
        if (d.size < 12) return
        fun u16(p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        val qd = u16(4)
        val rrCount = u16(6) + u16(8) + u16(10)
        var pos = 12
        repeat(qd) { pos = readName(d, pos).second + 4 }
        repeat(rrCount) {
            val (name, p) = readName(d, pos)
            val type = u16(p)
            val rdLen = u16(p + 8)
            val rd = p + 10
            when (type) {
                TYPE_PTR -> {
                    val target = readName(d, rd).first
                    c.ptr.getOrPut(name.lowercase()) { LinkedHashSet() }.add(target)
                    c.source.putIfAbsent(target, from)
                }
                TYPE_SRV -> {
                    val port = u16(rd + 4)
                    val target = readName(d, rd + 6).first
                    c.srv[name] = target to port
                    c.source.putIfAbsent(name, from)
                }
                TYPE_TXT -> {
                    val m = LinkedHashMap<String, String>()
                    var q = rd
                    while (q < rd + rdLen) {
                        val l = d[q].toInt() and 0xFF
                        if (l > 0 && q + 1 + l <= d.size) {
                            val s = String(d, q + 1, l, Charsets.UTF_8)
                            val eq = s.indexOf('=')
                            if (eq > 0) m[s.substring(0, eq).lowercase()] = s.substring(eq + 1) else m[s.lowercase()] = ""
                        }
                        q += 1 + l
                    }
                    c.txt[name] = m
                }
                TYPE_A -> if (rdLen == 4) {
                    c.a[name] = (0 until 4).joinToString(".") { (d[rd + it].toInt() and 0xFF).toString() }
                }
            }
            pos = rd + rdLen
        }
        // Normalise PTR keys: service types are case-insensitive
        val fixed = c.ptr.entries.associate { it.key.lowercase() to it.value }
        c.ptr.clear(); c.ptr.putAll(fixed.mapValues { it.value.toMutableSet() })
    }
}
