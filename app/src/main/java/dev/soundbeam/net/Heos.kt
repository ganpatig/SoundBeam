package dev.soundbeam.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class HeosPlayer(val pid: Long, val name: String, val ip: String, val model: String)

/**
 * HEOS CLI client (Denon/Marantz HEOS, Denon Home). Text commands over TCP port 1255,
 * one JSON response per line.
 */
class HeosClient(private val host: String) : AutoCloseable {
    private val socket = Socket()
    private lateinit var reader: BufferedReader
    private lateinit var out: OutputStream

    fun connect(): HeosClient {
        socket.connect(InetSocketAddress(host, PORT), 4000)
        socket.soTimeout = 8000
        reader = socket.getInputStream().bufferedReader()
        out = socket.getOutputStream()
        return this
    }

    /** Sends `heos://group/command?args` and returns the final JSON response. */
    @Synchronized
    fun command(cmd: String): JSONObject {
        out.write("heos://$cmd\r\n".toByteArray())
        out.flush()
        val name = cmd.substringBefore('?')
        while (true) {
            val line = reader.readLine() ?: throw IOException("HEOS connection closed")
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val heos = json.optJSONObject("heos") ?: continue
            if (heos.optString("command") != name) continue
            val message = heos.optString("message")
            if (message.contains("command under process")) continue
            if (heos.optString("result") != "success") {
                throw IOException("HEOS $name failed: ${message.ifEmpty { "error" }}")
            }
            return json
        }
    }

    fun players(): List<HeosPlayer> {
        val arr = command("player/get_players").optJSONArray("payload") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            HeosPlayer(p.optLong("pid"), p.optString("name"), p.optString("ip"), p.optString("model"))
        }
    }

    fun playStream(pid: Long, url: String) = command("browse/play_stream?pid=$pid&url=$url")

    fun setVolume(pid: Long, level: Int) = command("player/set_volume?pid=$pid&level=${level.coerceIn(0, 100)}")

    fun stop(pid: Long) = command("player/set_play_state?pid=$pid&state=stop")

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        const val PORT = 1255
    }
}
