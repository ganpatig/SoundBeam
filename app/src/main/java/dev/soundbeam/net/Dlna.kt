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

/** A UPnP/DLNA MediaRenderer on the local network. */
data class Renderer(
    val id: String,
    val name: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingControlUrl: String?,
    val renderingControlType: String?,
)

/** Finds DLNA/UPnP media renderers via SSDP multicast. */
object SsdpScanner {
    private const val ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun scan(ctx: Context, timeoutMs: Int = 3500): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("soundbeam-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $ST\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        String(p.data, 0, p.length).lineSequence()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                            ?.let { locations.add(it) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        locations.mapNotNull { describe(it) }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    private fun describe(location: String): Renderer? = try {
        val conn = URL(location).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val doc = conn.inputStream.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        conn.disconnect()
        val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim() ?: "Unknown device"
        val udn = doc.getElementsByTagName("UDN").item(0)?.textContent?.trim() ?: location
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as? Element ?: continue
            val type = el.getElementsByTagName("serviceType").item(0)?.textContent?.trim() ?: continue
            val ctl = el.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
            val abs = URL(URL(location), ctl).toString()
            if (type.contains(":AVTransport:")) av = abs to type
            if (type.contains(":RenderingControl:")) rc = abs to type
        }
        if (av == null) null else Renderer(udn, name, av.first, av.second, rc?.first, rc?.second)
    } catch (e: Exception) {
        null
    }
}

/** Minimal UPnP AVTransport / RenderingControl client. */
object Upnp {
    fun setUriAndPlay(r: Renderer, streamUrl: String, title: String) {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/wav:*\">${esc(streamUrl)}</res></item></DIDL-Lite>"
        soap(
            r.avTransportUrl, r.avTransportType, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to streamUrl, "CurrentURIMetaData" to didl),
        )
        soap(r.avTransportUrl, r.avTransportType, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
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
            if (code !in 200..299) throw IOException("$action failed (HTTP $code)")
        } finally {
            c.disconnect()
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
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
