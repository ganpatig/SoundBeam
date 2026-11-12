package dev.soundbeam.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Google Cast v2 sender: TLS to port 8009, length-prefixed CastMessage protobufs carrying JSON.
 * Launches the Default Media Receiver and loads our live stream URL.
 */
class CastClient(private val host: String, private val port: Int) {
    private lateinit var socket: SSLSocket
    private lateinit var out: OutputStream
    private val requestId = AtomicInteger(1)
    @Volatile private var running = false
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    private var launched = CountDownLatch(1)
    var onClosed: (() -> Unit)? = null

    fun connect() {
        // Cast devices present self-signed certificates; trust is established by being on the LAN.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(host, port), 5000)
        socket = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.startHandshake()
        out = socket.getOutputStream()
        running = true
        thread(isDaemon = true, name = "cast-read") { readLoop() }
        thread(isDaemon = true, name = "cast-heartbeat") {
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                runCatching { send(NS_HEARTBEAT, RECEIVER, JSONObject().put("type", "PING")) }
            }
        }
        send(NS_CONNECTION, RECEIVER, JSONObject().put("type", "CONNECT"))
    }

    fun load(url: String, contentType: String, title: String, artist: String?, imageUrl: String?) {
        if (transportId == null) {
            launched = CountDownLatch(1)
            send(NS_RECEIVER, RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER).put("requestId", requestId.getAndIncrement()))
            if (!launched.await(20, TimeUnit.SECONDS)) throw IOException("Chromecast did not start the media receiver")
        }
        val tid = transportId ?: throw IOException("No Cast transport")
        send(NS_CONNECTION, tid, JSONObject().put("type", "CONNECT"))
        val metadata = JSONObject()
            .put("metadataType", 3) // MusicTrackMediaMetadata
            .put("title", title)
        artist?.let { metadata.put("artist", it) }
        imageUrl?.let { metadata.put("images", JSONArray().put(JSONObject().put("url", it))) }
        val media = JSONObject()
            .put("contentId", url)
            .put("contentType", contentType)
            .put("streamType", "LIVE")
            .put("metadata", metadata)
        send(
            NS_MEDIA, tid,
            JSONObject().put("type", "LOAD").put("requestId", requestId.getAndIncrement())
                .put("media", media).put("autoplay", true),
        )
    }

    fun setVolume(percent: Int) {
        send(
            NS_RECEIVER, RECEIVER,
            JSONObject().put("type", "SET_VOLUME").put("requestId", requestId.getAndIncrement())
                .put("volume", JSONObject().put("level", percent.coerceIn(0, 100) / 100.0)),
        )
    }

    fun stop() {
        running = false
        runCatching {
            sessionId?.let {
                send(NS_RECEIVER, RECEIVER, JSONObject().put("type", "STOP").put("sessionId", it).put("requestId", requestId.getAndIncrement()))
            }
        }
        runCatching { socket.close() }
    }

    // ------------------------------------------------------------------------------ wire

    @Synchronized
    private fun send(namespace: String, destination: String, payload: JSONObject) {
        val msg = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { msg.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            msg.write(x.toInt())
        }
        fun field(n: Int, s: String) {
            val b = s.toByteArray()
            varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); msg.write(b)
        }
        varint((1 shl 3).toLong()); varint(0)  // protocol_version = CASTV2_1_0
        field(2, SENDER)
        field(3, destination)
        field(4, namespace)
        varint((5 shl 3).toLong()); varint(0)  // payload_type = STRING
        field(6, payload.toString())
        val body = msg.toByteArray()
        val len = body.size
        out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
        out.write(body)
        out.flush()
    }

    private fun readLoop() {
        try {
            val din = DataInputStream(socket.getInputStream())
            while (running) {
                val len = din.readInt()
                if (len <= 0 || len > 1 shl 20) break
                val buf = ByteArray(len)
                din.readFully(buf)
                val fields = parseFields(buf)
                val ns = fields[4] ?: continue
                val payload = fields[6] ?: continue
                handle(ns, fields[2].orEmpty(), runCatching { JSONObject(payload) }.getOrNull() ?: continue)
            }
        } catch (_: Exception) {
        } finally {
            if (running) {
                running = false
                onClosed?.invoke()
            }
        }
    }

    private fun handle(ns: String, source: String, json: JSONObject) {
        when (json.optString("type")) {
            "PING" -> runCatching { send(NS_HEARTBEAT, source.ifEmpty { RECEIVER }, JSONObject().put("type", "PONG")) }
            "RECEIVER_STATUS" -> {
                val apps = json.optJSONObject("status")?.optJSONArray("applications") ?: return
                for (i in 0 until apps.length()) {
                    val app = apps.optJSONObject(i) ?: continue
                    if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER) {
                        transportId = app.optString("transportId")
                        sessionId = app.optString("sessionId")
                        launched.countDown()
                    }
                }
            }
            "CLOSE" -> if (source == transportId) { transportId = null }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> if (ns == NS_MEDIA) {
                running = false
                onClosed?.invoke()
            }
        }
    }

    /** Extracts length-delimited string fields from a CastMessage. */
    private fun parseFields(b: ByteArray): Map<Int, String> {
        val m = HashMap<Int, String>()
        var p = 0
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (p < b.size) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            return r
        }
        while (p < b.size) {
            val key = varint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    if (p + len > b.size) break
                    m[field] = String(b, p, len)
                    p += len
                }
                else -> break
            }
        }
        return m
    }

    companion object {
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val SENDER = "sender-soundbeam"
        private const val RECEIVER = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}
