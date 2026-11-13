package dev.soundbeam.net

import android.os.SystemClock
import dev.soundbeam.audio.BITS
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.audio.Wav
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Serves the live capture over HTTP:
 *  - /stream.wav  endless PCM WAV (DLNA renderers, VLC)
 *  - /stream.aac  ADTS AAC with optional ICY metadata (Chromecast, Sonos, HEOS)
 *  - /artwork.jpg current cover art
 * Each client can be held back by a per-receiver delay so different systems line up.
 */
class HttpStreamServer(
    private val preferredPort: Int = 8765,
    private val delayForHost: (String) -> Int,
    private val icyTitle: () -> String,
    private val artwork: () -> ByteArray?,
) {
    private enum class Kind { WAV, AAC }

    private class Chunk(val at: Long, val data: ByteArray)

    private class Client(val kind: Kind, val host: String) {
        val queue = ArrayBlockingQueue<Chunk>(2400) // ~14 s of small capture blocks
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Int {
        val s = try { ServerSocket(preferredPort) } catch (e: IOException) { ServerSocket(0) }
        server = s
        running = true
        thread(isDaemon = true, name = "soundbeam-http-accept") {
            while (running) {
                try {
                    val sock = s.accept()
                    thread(isDaemon = true, name = "soundbeam-http-client") { handle(sock) }
                } catch (e: IOException) {
                    if (!running) break
                }
            }
        }
        return s.localPort
    }

    fun hasClients() = clients.isNotEmpty()

    fun writeWav(pcm: ByteArray, len: Int) = push(Kind.WAV, pcm.copyOf(len))

    fun writeAac(frame: ByteArray) = push(Kind.AAC, frame)

    private fun push(kind: Kind, data: ByteArray) {
        if (clients.isEmpty()) return
        val chunk = Chunk(SystemClock.elapsedRealtime(), data)
        for (c in clients) {
            if (c.kind != kind) continue
            if (!c.queue.offer(chunk)) {
                c.queue.poll() // slow client: drop the oldest chunk, keep the stream live
                c.queue.offer(chunk)
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        clients.clear()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1)?.substringBefore('?')
            val out = sock.getOutputStream()
            val host = sock.inetAddress.hostAddress ?: ""

            when (path) {
                "/stream.wav" -> stream(sock, out, method, host, Kind.WAV, false)
                "/stream.aac" -> stream(sock, out, method, host, Kind.AAC, headers["icy-metadata"] == "1")
                "/artwork.jpg" -> {
                    val art = artwork()
                    if (art == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${art.size}\r\n" +
                                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        if (method != "HEAD") out.write(art)
                    }
                    out.flush()
                }
                else -> {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
            }
        } catch (_: IOException) {
        } finally {
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private fun stream(sock: Socket, out: OutputStream, method: String, host: String, kind: Kind, icy: Boolean) {
        val mime = if (kind == Kind.WAV) "audio/wav" else "audio/aac"
        val sb = StringBuilder()
        sb.append(if (icy) "HTTP/1.0 200 OK\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Accept-Ranges: none\r\n")
        sb.append("transferMode.dlna.org: Streaming\r\n")
        sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
        if (icy) {
            sb.append("icy-name: SoundBeam\r\n")
            sb.append("icy-metaint: $META_INT\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (method == "HEAD") {
            out.flush()
            return
        }
        if (kind == Kind.WAV) out.write(Wav.header(SAMPLE_RATE, CHANNELS, BITS, Wav.UNKNOWN_LENGTH))
        out.flush()

        val client = Client(kind, host)
        clients.add(client)
        try {
            var untilMeta = META_INT
            var lastTitle: String? = null
            while (running && !sock.isClosed) {
                val chunk = client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                val wait = chunk.at + delayForHost(host) - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(minOf(wait, 10_000))
                if (!icy) {
                    out.write(chunk.data)
                    continue
                }
                var off = 0
                while (off < chunk.data.size) {
                    val n = minOf(untilMeta, chunk.data.size - off)
                    out.write(chunk.data, off, n)
                    off += n
                    untilMeta -= n
                    if (untilMeta == 0) {
                        val title = icyTitle()
                        if (title != lastTitle) {
                            lastTitle = title
                            val text = "StreamTitle='${title.replace("'", "’")}';".toByteArray()
                            val blocks = minOf(255, (text.size + 15) / 16)
                            val block = ByteArray(1 + blocks * 16)
                            block[0] = blocks.toByte()
                            System.arraycopy(text, 0, block, 1, minOf(text.size, blocks * 16))
                            out.write(block)
                        } else {
                            out.write(0)
                        }
                        untilMeta = META_INT
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            clients.remove(client)
        }
    }

    companion object {
        private const val META_INT = 8192
    }
}
