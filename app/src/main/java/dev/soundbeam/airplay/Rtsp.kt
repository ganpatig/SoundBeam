package dev.soundbeam.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class RtspResponse(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String) = headers[name.lowercase()]
    val ok get() = status in 200..299
}

/**
 * RTSP/HTTP-style request/response connection used by AirPlay. After pairing, [enableEncryption]
 * switches the stream to the HomeKit framing: [len:2 LE][ciphertext][tag:16], ChaCha20-Poly1305,
 * nonce = 64-bit LE frame counter, AAD = the 2-byte length.
 */
class RtspConnection(val host: String, val port: Int) {
    private val socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 1

    private var writeKey: ByteArray? = null
    private var readKey: ByteArray? = null
    private var writeCounter = 0L
    private var readCounter = 0L
    private val plainIn = ByteArrayOutputStream()
    private var plainBuf = ByteArray(0)
    private var plainPos = 0

    val localAddress: String get() = socket.localAddress.hostAddress ?: "0.0.0.0"

    /** Headers added to every request. */
    val commonHeaders = LinkedHashMap<String, String>()

    fun connect(timeoutMs: Int = 5000) {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        socket.keepAlive = true
        input = socket.getInputStream().buffered()
        output = socket.getOutputStream()
    }

    fun enableEncryption(sharedSecret: ByteArray) {
        writeKey = Crypto.hkdfSha512(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key")
        readKey = Crypto.hkdfSha512(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key")
    }

    @Synchronized
    fun request(
        method: String,
        uri: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): RtspResponse {
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        commonHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\n\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        send(if (body != null) head + body else head)
        return readResponse()
    }

    private fun send(data: ByteArray) {
        val key = writeKey
        if (key == null) {
            output.write(data)
        } else {
            var off = 0
            val frames = ByteArrayOutputStream()
            while (off < data.size) {
                val n = minOf(1024, data.size - off)
                val len = byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())
                val sealed = Crypto.chachaSeal(key, Crypto.counterNonce(writeCounter++), data.copyOfRange(off, off + n), len)
                frames.write(len)
                frames.write(sealed)
                off += n
            }
            output.write(frames.toByteArray())
        }
        output.flush()
    }

    // --- reading -------------------------------------------------------------------------------

    private fun readByte(): Int {
        if (readKey == null) return input.read()
        if (plainPos >= plainBuf.size) {
            if (!readFrame()) return -1
        }
        return plainBuf[plainPos++].toInt() and 0xFF
    }

    private fun readFrame(): Boolean {
        val key = readKey ?: return false
        val l0 = input.read()
        val l1 = input.read()
        if (l0 < 0 || l1 < 0) return false
        val n = l0 or (l1 shl 8)
        val sealed = readExactly(input, n + 16)
        val plain = Crypto.chachaOpen(key, Crypto.counterNonce(readCounter++), sealed, byteArrayOf(l0.toByte(), l1.toByte()))
        plainBuf = plain
        plainPos = 0
        return true
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = readByte()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readResponse(): RtspResponse {
        var statusLine = readLine() ?: throw IOException("Connection closed by $host")
        while (statusLine.isBlank()) statusLine = readLine() ?: throw IOException("Connection closed by $host")
        val parts = statusLine.split(" ", limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad response: $statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        for (i in 0 until len) {
            val b = readByte()
            if (b < 0) throw IOException("Truncated body from $host")
            body[i] = b.toByte()
        }
        return RtspResponse(status, parts.getOrElse(2) { "" }, headers, body)
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw IOException("Unexpected end of stream")
                off += r
            }
            return buf
        }
    }
}
