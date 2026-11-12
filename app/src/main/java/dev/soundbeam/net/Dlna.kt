package dev.soundbeam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP device with an AVTransport service (DLNA renderer, Sonos player, HEOS speaker...). */
data class Renderer(
    val id: String,
    val name: String,
    val host: String,
    val manufacturer: String,
    val model: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingControlUrl: String?,
    val renderingControlType: String?,
) {
    val isSonos get() = manufacturer.contains("Sonos", ignoreCase = true)
}

data class SsdpResult(val renderers: List<Renderer>, val heosHosts: List<String>)

/** Finds UPnP renderers, Sonos players and HEOS hubs via SSDP multicast. */
object SsdpScanner {
    private val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:ZonePlayer:1",
        "urn:schemas-denon-com:device:ACT-Denon:1",
    )

    suspend fun scan(ctx: Context, timeoutMs: Int = 3500): SsdpResult = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("soundbeam-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val locations = linkedSetOf<String>()
        val heos = linkedSetOf<String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    for (st in TARGETS) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n\r\n").toByteArray()
                        sock.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val headers = String(p.data, 0, p.length).lineSequence()
                            .mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().uppercase() to l.substring(it + 1).trim() } }
                            .toMap()
                        val st = headers["ST"].orEmpty()
                        if (st.contains("ACT-Denon", ignoreCase = true)) {
                            p.address.hostAddress?.let { heos.add(it) }
                        } else {
                            headers["LOCATION"]?.let { locations.add(it) }
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        val renderers = locations.mapNotNull { describe(it) }.distinctBy { it.id }
        SsdpResult(renderers, heos.toList())
    }

    private fun describe(location: String): Renderer? = try {
        val conn = URL(location).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val doc = conn.inputStream.use {
            val f = DocumentBuilderFactory.newInstance()
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.newDocumentBuilder().parse(it)
        }
        conn.disconnect()
        fun first(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent?.trim()
        val name = first("friendlyName") ?: "Unknown device"
        val udn = first("UDN") ?: location
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as? Element ?: continue
            val type = el.getElementsByTagName("serviceType").item(0)?.textContent?.trim() ?: continue
            val ctl = el.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
            val abs = URL(URL(location), ctl).toString()
            if (type.contains(":AVTransport:") && av == null) av = abs to type
            if (type.contains(":RenderingControl:") && rc == null) rc = abs to type
        }
        if (av == null) {
            null
        } else {
            Renderer(
                id = udn,
                name = name,
                host = URL(location).host,
                manufacturer = first("manufacturer").orEmpty(),
                model = first("modelName").orEmpty(),
                avTransportUrl = av.first,
                avTransportType = av.second,
                renderingControlUrl = rc?.first,
                renderingControlType = rc?.second,
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** Minimal UPnP AVTransport / RenderingControl client. */
object Upnp {
    fun setUriAndPlay(r: Renderer, uri: String, metadata: String) {
        soap(
            r.avTransportUrl, r.avTransportType, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata),
        )
        soap(r.avTransportUrl, r.avTransportType, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    fun didl(title: String, artist: String?, url: String, mime: String, artUrl: String?, sonosRadio: Boolean): String {
        val cls = if (sonosRadio) "object.item.audioItem.audioBroadcast" else "object.item.audioItem.musicTrack"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
            "<item id=\"soundbeam\" parentID=\"0\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            (artist?.let { "<dc:creator>${esc(it)}</dc:creator><upnp:artist>${esc(it)}</upnp:artist>" } ?: "") +
            (artUrl?.let { "<upnp:albumArtURI>${esc(it)}</upnp:albumArtURI>" } ?: "") +
            "<upnp:class>$cls</upnp:class>" +
            (if (sonosRadio) "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON65031_</desc>" else "") +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
    }

    fun stop(r: Renderer) {
        soap(r.avTransportUrl, r.avTransportType, "Stop", listOf("InstanceID" to "0"))
    }

    fun setVolume(r: Renderer, volume: Int) {
        val url = r.renderingControlUrl ?: return
        val type = r.renderingControlType ?: return
        soap(
            url, type, "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()),
        )
    }

    private fun soap(url: String, serviceType: String, action: String, args: List<Pair<String, String>>) {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$serviceType\">")
            args.forEach { (k, v) -> append("<$k>${esc(v)}</$k>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                val upnpCode = Regex("<errorCode>(\\d+)</errorCode>").find(err)?.groupValues?.get(1)
                throw IOException("$action failed (HTTP $code${upnpCode?.let { ", UPnP error $it" } ?: ""})")
            }
        } finally {
            c.disconnect()
        }
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** Best-effort local IPv4 address of the active network. */
fun localIpAddress(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress?.let { return it }
    return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}
