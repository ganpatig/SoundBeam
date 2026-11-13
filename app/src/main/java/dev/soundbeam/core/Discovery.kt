package dev.soundbeam.core

import android.content.Context
import android.net.wifi.WifiManager
import dev.soundbeam.net.HeosClient
import dev.soundbeam.net.Mdns
import dev.soundbeam.net.MdnsService
import dev.soundbeam.net.SsdpScanner
import dev.soundbeam.net.localIpAddress
import dev.soundbeam.outputs.AriaCastOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Runs every discovery mechanism in parallel and merges the results into one device list. */
object Discovery {
    private const val AIRPLAY = "_airplay._tcp.local"
    private const val RAOP = "_raop._tcp.local"
    private const val CAST = "_googlecast._tcp.local"
    private const val ARIACAST = "_audiocast._tcp.local"

    suspend fun scan(ctx: Context): List<Device> = coroutineScope {
        val ssdp = async { runCatching { SsdpScanner.scan(ctx) }.getOrNull() }
        val mdns = async { runCatching { Mdns.browse(ctx, listOf(AIRPLAY, RAOP, CAST, ARIACAST)) }.getOrDefault(emptyList()) }
        val aria = async { runCatching { ariaCastBroadcast(ctx) }.getOrDefault(emptyList()) }

        val devices = ArrayList<Device>()
        val services = mdns.await()
        devices += airplay(services)
        devices += cast(services)
        devices += services.filter { it.type == ARIACAST }.map { ariaDevice(it.instance, it.host, it.port, it.txt) }
        devices += aria.await()

        ssdp.await()?.let { res ->
            for (r in res.renderers) {
                devices += Device(
                    id = "upnp:${r.id}",
                    name = r.name,
                    protocol = if (r.isSonos) Protocol.SONOS else Protocol.DLNA,
                    host = r.host,
                    port = 0,
                    renderer = r,
                    model = listOf(r.manufacturer, r.model).filter { it.isNotBlank() }.joinToString(" "),
                )
            }
            res.heosHosts.firstOrNull()?.let { hub ->
                devices += withContext(Dispatchers.IO) {
                    runCatching {
                        HeosClient(hub).connect().use { c ->
                            c.players().map { p ->
                                Device(
                                    id = "heos:${p.pid}",
                                    name = p.name,
                                    protocol = Protocol.HEOS,
                                    host = p.ip.ifEmpty { hub },
                                    port = HeosClient.PORT,
                                    txt = mapOf("pid" to p.pid.toString()),
                                    model = p.model,
                                )
                            }
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }

        devices.distinctBy { it.id }
            .sortedWith(compareBy<Device>({ it.protocol.ordinal }, { it.name.lowercase() }))
    }

    // ------------------------------------------------------------------------------ AirPlay

    private fun features(txt: Map<String, String>): Long {
        val f = txt["features"] ?: return 0
        val parts = f.split(',').map { it.trim().removePrefix("0x").removePrefix("0X") }
        val lo = parts.getOrNull(0)?.toLongOrNull(16) ?: 0
        val hi = parts.getOrNull(1)?.toLongOrNull(16) ?: 0
        return (lo and 0xFFFFFFFFL) or (hi shl 32)
    }

    private fun airplay(services: List<MdnsService>): List<Device> {
        val out = ArrayList<Device>()
        val v2Macs = HashSet<String>()
        val raopPorts = services.filter { it.type == RAOP }
            .associate { it.instance.substringBefore('@').uppercase() to it.port }
        for (s in services.filter { it.type == AIRPLAY }) {
            val f = features(s.txt)
            val audio = f and (1L shl 9) != 0L
            val pairing = f and (1L shl 48) != 0L
            val mac = s.txt["deviceid"]?.replace(":", "")?.uppercase() ?: continue
            if (!audio || !pairing) continue // fall back to the _raop entry for AirPlay 1
            v2Macs += mac
            out += Device(
                id = "airplay:$mac",
                name = s.instance,
                protocol = Protocol.AIRPLAY2,
                host = s.host,
                port = s.port,
                txt = raopPorts[mac]?.let { s.txt + ("raopport" to it.toString()) } ?: s.txt,
                model = listOfNotNull(s.txt["manufacturer"], s.txt["model"]).joinToString(" ").ifEmpty { null },
            )
        }
        for (s in services.filter { it.type == RAOP }) {
            val mac = s.instance.substringBefore('@').uppercase()
            if (mac in v2Macs) continue
            out += Device(
                id = "airplay:$mac",
                name = s.instance.substringAfter('@'),
                protocol = Protocol.AIRPLAY,
                host = s.host,
                port = s.port,
                txt = s.txt,
                model = s.txt["am"],
            )
        }
        return out
    }

    private fun cast(services: List<MdnsService>): List<Device> =
        services.filter { it.type == CAST }.map { s ->
            Device(
                id = "cast:${s.txt["id"] ?: s.instance}",
                name = s.txt["fn"] ?: s.instance,
                protocol = Protocol.CAST,
                host = s.host,
                port = s.port,
                txt = s.txt,
                model = s.txt["md"],
            )
        }

    // ------------------------------------------------------------------------------ AriaCast

    private fun ariaDevice(name: String, host: String, port: Int, txt: Map<String, String>): Device {
        val isOurs = txt["platform"]?.contains("SoundBeam", ignoreCase = true) == true
        return Device(
            id = "aria:$host:$port",
            name = name,
            protocol = if (isOurs) Protocol.RECEIVER else Protocol.ARIACAST,
            host = host,
            port = port,
            txt = txt,
            model = txt["platform"],
        )
    }

    private suspend fun ariaCastBroadcast(ctx: Context): List<Device> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Device>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("soundbeam-aria").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val msg = "DISCOVER_AUDIOCAST".toByteArray()
                val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
                localIpAddress(ctx)?.let { ip ->
                    // also the /24 directed broadcast, which some routers forward more reliably
                    targets += InetAddress.getByName(ip.substringBeforeLast('.') + ".255")
                }
                repeat(2) { targets.forEach { t -> runCatching { s.send(DatagramPacket(msg, msg.size, t, 12888)) } } }
                val end = System.currentTimeMillis() + 2000
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val j = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                        val host = j.optString("ip").ifEmpty { p.address.hostAddress.orEmpty() }
                        if (host.isEmpty()) continue
                        val port = j.optInt("port", AriaCastOutput.DEFAULT_PORT)
                        val txt = buildMap {
                            j.optString("platform").takeIf { it.isNotEmpty() }?.let { put("platform", it) }
                        }
                        found["$host:$port"] = ariaDevice(j.optString("server_name", "AriaCast receiver"), host, port, txt)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        found.values.toList()
    }
}
