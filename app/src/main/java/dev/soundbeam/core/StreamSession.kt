package dev.soundbeam.core

import android.content.Context
import android.os.Environment
import dev.soundbeam.Engine
import dev.soundbeam.airplay.AirPlayMaster
import dev.soundbeam.airplay.AirPlayOutput
import dev.soundbeam.audio.AacEncoder
import dev.soundbeam.audio.WavFileSink
import dev.soundbeam.net.HttpStreamServer
import dev.soundbeam.net.localIpAddress
import dev.soundbeam.outputs.AriaCastOutput
import dev.soundbeam.outputs.CastOutput
import dev.soundbeam.outputs.DlnaOutput
import dev.soundbeam.outputs.HeosOutput
import dev.soundbeam.outputs.SonosOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything that exists while streaming: the HTTP server, AAC encoder, AirPlay timeline,
 * file recorder and the set of active outputs. Outputs can be added and removed at any time.
 */
class StreamSession(private val ctx: Context) : StreamEnv {

    override val localIp: String = localIpAddress(ctx) ?: "127.0.0.1"
    override var httpPort: Int = 0
        private set
    override val airplay = AirPlayMaster { Engine.airplayLatencyMs.value }

    private val http = HttpStreamServer(
        delayForHost = { Engine.delayForHost(it) },
        icyTitle = { Engine.nowPlaying.value?.displayTitle?.ifEmpty { null } ?: "SoundBeam" },
        artwork = { Engine.nowPlaying.value?.artworkJpeg },
    )
    private var aac: AacEncoder? = null
    private var file: WavFileSink? = null
    private val outputs = ConcurrentHashMap<String, Output>()
    private val jobs = ArrayList<Job>()

    fun start() {
        httpPort = http.start()
        airplay.start()
        aac = runCatching { AacEncoder { http.writeAac(it) } }.getOrNull()
        if (Engine.saveEnabled.value) {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = WavFileSink(File(dir, "soundbeam-$stamp.wav")).also {
                Engine.status.value = "Recording to ${it.file.absolutePath}"
            }
        }
        Engine.streamUrls.value = if (Engine.httpEnabled.value) listOf(wavUrl, aacUrl) else emptyList()

        val selected = Engine.selected.value
        Engine.devices.value.filter { it.id in selected }.forEach { addOutput(it) }

        // Push track changes to every output; refresh AirPlay progress periodically.
        jobs += Engine.scope.launch {
            Engine.nowPlaying.collect { np ->
                if (np != null) outputs.values.forEach { o -> runCatching { o.onMetadata(np) } }
            }
        }
        jobs += Engine.scope.launch {
            while (true) {
                delay(10_000)
                Engine.nowPlaying.value?.let { np -> outputs.values.forEach { o -> runCatching { o.onMetadata(np) } } }
            }
        }
    }

    override fun artworkUrl(): String? {
        val art = Engine.nowPlaying.value?.artworkJpeg ?: return null
        return "http://$localIp:$httpPort/artwork.jpg?v=${art.size}"
    }

    fun output(id: String): Output? = outputs[id]

    fun activeDevices(): List<Device> = outputs.values.map { it.device }

    fun addOutput(d: Device) {
        if (outputs.containsKey(d.id)) return
        val o: Output = try {
            when (d.protocol) {
                Protocol.AIRPLAY, Protocol.AIRPLAY2 -> AirPlayOutput(d)
                Protocol.CAST -> CastOutput(d)
                Protocol.SONOS -> SonosOutput(d)
                Protocol.HEOS -> HeosOutput(d)
                Protocol.DLNA -> DlnaOutput(d)
                Protocol.ARIACAST, Protocol.RECEIVER -> AriaCastOutput(d)
            }
        } catch (e: Exception) {
            Engine.setOutputState(d.id, OutputState.Failed(e.message ?: "unsupported"))
            return
        }
        outputs[d.id] = o
        Engine.setOutputState(d.id, OutputState.Connecting)
        Engine.scope.launch {
            try {
                o.setVolume(Engine.volumeFor(d.id))
                o.setDelayMs(Engine.delayFor(d.id))
                o.start(this@StreamSession)
                if (outputs[d.id] === o && Engine.outputStates.value[d.id] !is OutputState.Failed) {
                    Engine.setOutputState(d.id, OutputState.Playing)
                }
            } catch (e: Exception) {
                if (outputs[d.id] === o) {
                    outputs.remove(d.id)
                    Engine.setOutputState(d.id, OutputState.Failed(e.message ?: e.javaClass.simpleName))
                }
                runCatching { o.stop() }
            }
        }
    }

    fun removeOutput(id: String) {
        val o = outputs.remove(id) ?: run { Engine.setOutputState(id, null); return }
        Engine.setOutputState(id, null)
        Engine.scope.launch { runCatching { o.stop() } }
    }

    /** Called on the capture thread for every block of equalised PCM. */
    fun onPcm(pcm: ShortArray, len: Int, bytes: ByteArray) {
        val byteLen = len * 2
        http.writeWav(bytes, byteLen)
        aac?.let { runCatching { it.encode(bytes, byteLen) } }
        airplay.write(pcm, len)
        for (o in outputs.values) o.onPcm(pcm, len)
        file?.let { runCatching { it.write(bytes, byteLen) } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        val all = outputs.values.toList()
        outputs.clear()
        // Give receivers a moment to receive TEARDOWN/Stop before the process may be frozen.
        runBlocking {
            withTimeoutOrNull(3000) {
                all.map { o -> Engine.scope.launch { runCatching { o.stop() } } }.forEach { it.join() }
            }
        }
        all.forEach { Engine.setOutputState(it.device.id, null) }
        Engine.outputStates.value = emptyMap()
        http.stop()
        airplay.stop()
        aac?.release()
        file?.let { runCatching { it.stop() } }
        Engine.streamUrls.value = emptyList()
    }
}
