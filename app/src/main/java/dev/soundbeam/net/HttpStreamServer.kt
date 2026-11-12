package dev.soundbeam.net

import dev.soundbeam.audio.AudioSink
import dev.soundbeam.audio.BITS
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.audio.Wav
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that serves the live capture as an endless WAV stream at /stream.wav.
 * Used both as the "HTTP stream" output (VLC etc.) and as the source URL handed to DLNA renderers.
 */
class HttpStreamServer(private val preferredPort: Int = 8765) : AudioSink {

    private class Client {
        val queue = ArrayBlockingQueue<ByteArray>(120) // ~5 s of audio
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** Starts listening and returns the bound port. */
    fun start(): Int {
        val s = try {
            ServerSocket(preferredPort)
        } catch (e: IOException) {
            ServerSocket(0)
        }
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

    override fun write(pcm: ByteArray, len: Int) {
        if (clients.isEmpty()) return
        val copy = pcm.copyOf(len)
        for (c in clients) {
            if (!c.queue.offer(copy)) {
                c.queue.poll() // slow client: drop the oldest chunk, keep the stream live
                c.queue.offer(copy)
            }
        }
    }

    override fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        clients.clear()
    }

    private fun handle(sock: Socket) {
        val client = Client()
        try {
            sock.tcpNoDelay = true
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1)?.substringBefore('?')
            val out = sock.getOutputStream()

            if (path != "/stream.wav") {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Accept-Ranges: none\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray())
            if (method == "HEAD") {
                out.flush()
                return
            }
            out.write(Wav.header(SAMPLE_RATE, CHANNELS, BITS, Wav.UNKNOWN_LENGTH))
            out.flush()

            clients.add(client)
            while (running && !sock.isClosed) {
                val chunk = client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(chunk)
            }
        } catch (_: IOException) {
            // client went away
        } finally {
            clients.remove(client)
            try { sock.close() } catch (_: IOException) {}
        }
    }
}
