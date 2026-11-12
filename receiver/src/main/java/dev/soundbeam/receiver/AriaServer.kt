package dev.soundbeam.receiver

import android.media.AudioManager
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * AriaCast receiver endpoints over WebSocket: /audio (raw PCM frames), /control (commands and
 * volume), /metadata (now-playing push/subscribe) and /stats.
 */
class AriaServer(
    port: Int,
    private val player: PcmPlayer,
    private val audioManager: AudioManager,
) : WebSocketServer(InetSocketAddress(port)) {

    @Volatile private var audioConn: WebSocket? = null
    private val controls = CopyOnWriteArraySet<WebSocket>()
    private val metas = CopyOnWriteArraySet<WebSocket>()
    private val stats = CopyOnWriteArraySet<WebSocket>()
    private var metadata = JSONObject()

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    private fun path(conn: WebSocket): String = conn.getAttachment<String>() ?: ""

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket, draft: Draft, request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val path = request.resourceDescriptor.substringBefore('?')
        if (path == "/audio" && audioConn?.isOpen == true) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Another sender is already streaming")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor.substringBefore('?')
        conn.setAttachment(path)
        when (path) {
            "/audio" -> {
                audioConn = conn
                player.start()
                conn.send(
                    JSONObject().put("status", "READY").put("sample_rate", PcmPlayer.RATE)
                        .put("channels", 2).put("frame_size", PcmPlayer.FRAME_BYTES).toString(),
                )
                ReceiverState.update { it.copy(sender = conn.remoteSocketAddress?.address?.hostAddress, playing = true, error = null) }
            }
            "/control" -> {
                controls += conn
                conn.send(
                    JSONObject().put("status", "READY").put("volume_available", true)
                        .put("current_volume", volumePercent()).toString(),
                )
            }
            "/metadata" -> {
                metas += conn
                conn.send(JSONObject().put("type", "metadata").put("data", metadata).toString())
            }
            "/stats" -> stats += conn
            else -> conn.close(CloseFrame.POLICY_VALIDATION, "Unknown endpoint")
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        when (path(conn)) {
            "/audio" -> if (audioConn === conn) {
                audioConn = null
                player.stop()
                ReceiverState.update { it.copy(sender = null, playing = false) }
            }
            "/control" -> controls -= conn
            "/metadata" -> metas -= conn
            "/stats" -> stats -= conn
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        if (path(conn) != "/audio") return
        val b = ByteArray(message.remaining())
        message.get(b)
        player.enqueue(b)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val j = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (path(conn)) {
            "/control" -> handleControl(conn, j)
            "/metadata" -> handleMetadata(conn, j)
        }
    }

    private fun handleControl(conn: WebSocket, j: JSONObject) {
        val cmd = j.optString("command").ifEmpty { j.optString("action") }
        if (cmd == "volume" || cmd == "volume_set") {
            when {
                j.has("value") -> setVolume(j.optInt("value"))
                j.has("level") -> setVolume(j.optInt("level"))
                j.optString("direction") == "up" -> setVolume(volumePercent() + 5)
                j.optString("direction") == "down" -> setVolume(volumePercent() - 5)
            }
            conn.send(JSONObject().put("command", "volume").put("level", volumePercent()).put("success", true).toString())
            return
        }
        // Playback commands from a remote control: forward to everyone else (e.g. the sender)
        if (cmd.isNotEmpty()) {
            val out = JSONObject().put("action", if (cmd == "play_pause") "toggle" else cmd)
            if (j.has("position_ms")) out.put("position_ms", j.optLong("position_ms"))
            controls.filter { it !== conn }.forEach { runCatching { it.send(out.toString()) } }
            conn.send(JSONObject().put("command", cmd).put("success", true).toString())
        }
    }

    private fun handleMetadata(conn: WebSocket, j: JSONObject) {
        when (j.optString("type")) {
            "update" -> {
                val d = j.optJSONObject("data") ?: JSONObject()
                synchronized(this) {
                    d.keys().forEach { k -> metadata.put(snake(k), d.get(k)) }
                }
                conn.send(JSONObject().put("type", "ack").put("success", true).toString())
                publishMetadata()
            }
            "get" -> conn.send(JSONObject().put("type", "metadata").put("data", metadata).toString())
            "clear" -> {
                synchronized(this) { metadata = JSONObject() }
                conn.send(JSONObject().put("type", "ack").put("success", true).toString())
                publishMetadata()
            }
            else -> conn.send(JSONObject().put("type", "error").put("message", "Unknown type").toString())
        }
    }

    private fun publishMetadata() {
        val msg = JSONObject().put("type", "metadata").put("data", metadata).toString()
        metas.forEach { runCatching { it.send(msg) } }
        fun s(k: String) = metadata.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        ReceiverState.update {
            it.copy(title = s("title"), artist = s("artist"), album = s("album"), artworkUrl = s("artwork_url"))
        }
    }

    private fun snake(k: String) = when (k) {
        "artworkUrl" -> "artwork_url"
        "durationMs" -> "duration_ms"
        "positionMs" -> "position_ms"
        "isPlaying" -> "is_playing"
        else -> k
    }

    fun pushStats() {
        if (stats.isEmpty()) return
        val msg = JSONObject()
            .put("receivedFrames", player.received.get())
            .put("bufferedFrames", player.queued())
            .put("queuedFrames", player.queued())
            .put("underruns", player.underruns.get())
            .put("overruns", player.overruns.get())
            .put("droppedFrames", player.overruns.get())
            .put("bufferLevel", "%.1f%%".format(player.queued() * 100.0 / PcmPlayer.MAX_FRAMES))
            .toString()
        stats.forEach { runCatching { it.send(msg) } }
    }

    private fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val idx = (percent.coerceIn(0, 100) * max + 50) / 100
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) ReceiverState.update { it.copy(error = ex.message) }
    }

    override fun onStart() {}
}
