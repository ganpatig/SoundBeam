package dev.soundbeam.outputs

import android.os.SystemClock
import dev.soundbeam.Engine
import dev.soundbeam.audio.Resampler
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.Device
import dev.soundbeam.core.NowPlaying
import dev.soundbeam.core.Output
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.StreamEnv
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AriaCast sender (Music Assistant's AriaCast Receiver, AriaCast servers, and the
 * SoundBeam Receiver app for Fire TV / Android TV). Audio is 48 kHz stereo PCM in
 * 3840-byte (20 ms) WebSocket binary frames; metadata and volume go over side channels.
 */
class AriaCastOutput(device: Device) : Output(device) {

    private class Ws(uri: URI, val onText: (String) -> Unit, val onClosed: () -> Unit) : WebSocketClient(uri) {
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String?) { message?.let(onText) }
        override fun onMessage(bytes: ByteBuffer?) {}
        override fun onClose(code: Int, reason: String?, remote: Boolean) = onClosed()
        override fun onError(ex: Exception?) {}
    }

    private class Frame(val at: Long, val data: ByteArray)

    private val base = "ws://${device.host}:${device.port}"
    private var audio: Ws? = null
    private var control: Ws? = null
    private var meta: Ws? = null
    private lateinit var env: StreamEnv

    private val resampler = Resampler(SAMPLE_RATE, OUT_RATE)
    private val frame = ByteArray(FRAME_BYTES)
    private var framePos = 0
    private val queue = ArrayBlockingQueue<Frame>(400)
    @Volatile private var delayMs = 0
    @Volatile private var active = false
    @Volatile private var stopped = false
    private var volume = -1
    private val isMusicPlatform get() = device.txt["platform"]?.contains("Music", ignoreCase = true) == true

    override fun start(env: StreamEnv) {
        this.env = env
        val ready = CountDownLatch(1)
        val a = Ws(URI("$base/audio"), onText = { text ->
            val j = runCatching { JSONObject(text) }.getOrNull()
            if (j?.optString("status") == "READY" || j?.optString("type") == "handshake") ready.countDown()
        }, onClosed = {
            if (!stopped) Engine.setOutputState(device.id, OutputState.Failed("receiver closed the connection"))
            active = false
        })
        if (!a.connectBlocking(5, TimeUnit.SECONDS)) throw IOException("Could not connect to ${device.host}:${device.port}")
        if (!ready.await(3, TimeUnit.SECONDS)) {
            a.close()
            throw IOException("Receiver did not send READY (it may already be busy)")
        }
        audio = a
        active = true
        thread(isDaemon = true, name = "ariacast-send") { sendLoop() }

        // Optional side channels: failures here don't stop audio.
        control = runCatching {
            Ws(URI("$base/control"), onText = { handleControl(it) }, onClosed = {}).also { it.connectBlocking(3, TimeUnit.SECONDS) }
        }.getOrNull()
        meta = runCatching {
            Ws(URI("$base/metadata"), onText = {}, onClosed = {}).also { it.connectBlocking(3, TimeUnit.SECONDS) }
        }.getOrNull()
        if (volume >= 0) sendVolume()
        Engine.nowPlaying.value?.let { onMetadata(it) }
    }

    override fun onPcm(pcm: ShortArray, len: Int) {
        if (!active) return
        val out = resampler.process(pcm, len)
        val now = SystemClock.elapsedRealtime()
        for (s in out) {
            frame[framePos++] = s.toInt().toByte()
            frame[framePos++] = (s.toInt() shr 8).toByte()
            if (framePos == FRAME_BYTES) {
                if (!queue.offer(Frame(now, frame.copyOf()))) {
                    queue.poll()
                    queue.offer(Frame(now, frame.copyOf()))
                }
                framePos = 0
            }
        }
    }

    private fun sendLoop() {
        while (!stopped) {
            val f = try { queue.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { break } ?: continue
            val wait = f.at + delayMs - SystemClock.elapsedRealtime()
            if (wait > 0) try { Thread.sleep(minOf(wait, 10_000)) } catch (_: InterruptedException) { break }
            val a = audio ?: break
            if (a.isOpen) runCatching { a.send(f.data) }
        }
    }

    private fun handleControl(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        val action = j.optString("action")
        if (action.isNotEmpty()) Engine.mediaCommand(action, j.optLong("position_ms", -1))
    }

    override fun setDelayMs(ms: Int) {
        delayMs = ms
    }

    override fun setVolume(percent: Int) {
        volume = percent
        if (active) sendVolume()
    }

    private fun sendVolume() {
        if (isMusicPlatform) return
        val c = control ?: return
        runCatching {
            c.send(JSONObject().put("command", "volume").put("value", volume).toString())
        }
    }

    override fun onMetadata(np: NowPlaying) {
        val m = meta ?: return
        if (!m.isOpen) return
        val data = JSONObject()
            .put("title", np.title ?: JSONObject.NULL)
            .put("artist", np.artist ?: JSONObject.NULL)
            .put("album", np.album ?: JSONObject.NULL)
            .put("artworkUrl", env.artworkUrl() ?: JSONObject.NULL)
            .put("durationMs", np.durationMs)
            .put("positionMs", np.currentPositionMs(SystemClock.elapsedRealtime()))
            .put("isPlaying", np.playing)
        runCatching { m.send(JSONObject().put("type", "update").put("data", data).toString()) }
    }

    override fun stop() {
        stopped = true
        active = false
        listOf(audio, control, meta).forEach { runCatching { it?.close() } }
    }

    companion object {
        const val OUT_RATE = 48000
        const val FRAME_BYTES = 3840
        const val DEFAULT_PORT = 12889
    }
}
