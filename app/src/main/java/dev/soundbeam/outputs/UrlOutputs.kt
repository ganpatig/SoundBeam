package dev.soundbeam.outputs

import dev.soundbeam.Engine
import dev.soundbeam.core.Device
import dev.soundbeam.core.Output
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.StreamEnv
import dev.soundbeam.net.CastClient
import dev.soundbeam.net.HeosClient
import dev.soundbeam.net.Upnp
import kotlinx.coroutines.launch
import java.io.IOException

/** Plain DLNA / UPnP renderer: pulls the lossless WAV stream. */
class DlnaOutput(device: Device) : Output(device) {
    private val r = device.renderer ?: throw IllegalArgumentException("No UPnP description")
    @Volatile private var started = false
    private var volume = -1

    override fun start(env: StreamEnv) {
        val np = Engine.nowPlaying.value
        val meta = Upnp.didl(
            np?.title?.takeIf { it.isNotBlank() } ?: "SoundBeam", np?.artist, env.wavUrl, "audio/wav",
            env.artworkUrl(), sonosRadio = false,
        )
        Upnp.setUriAndPlay(r, env.wavUrl, meta)
        started = true
        if (volume >= 0) runCatching { Upnp.setVolume(r, volume) }
    }

    override fun setVolume(percent: Int) {
        volume = percent
        if (started) Engine.scope.launch { runCatching { Upnp.setVolume(r, percent) } }
    }

    override fun stop() {
        if (started) runCatching { Upnp.stop(r) }
    }
}

/**
 * Sonos: plays our AAC stream as an internet radio station (x-rincon-mp3radio), which Sonos
 * buffers well; the current title is delivered as ICY StreamTitle metadata.
 */
class SonosOutput(device: Device) : Output(device) {
    private val r = device.renderer ?: throw IllegalArgumentException("No UPnP description")
    @Volatile private var started = false
    private var volume = -1

    override fun start(env: StreamEnv) {
        val uri = env.aacUrl.replaceFirst("http://", "x-rincon-mp3radio://")
        val meta = Upnp.didl("SoundBeam", null, uri, "audio/aac", env.artworkUrl(), sonosRadio = true)
        try {
            Upnp.setUriAndPlay(r, uri, meta)
        } catch (e: IOException) {
            if (e.message?.contains("800") == true) {
                throw IOException("This speaker is grouped - select the group coordinator instead")
            }
            throw e
        }
        started = true
        if (volume >= 0) runCatching { Upnp.setVolume(r, volume) }
    }

    override fun setVolume(percent: Int) {
        volume = percent
        if (started) Engine.scope.launch { runCatching { Upnp.setVolume(r, percent) } }
    }

    override fun stop() {
        if (started) runCatching { Upnp.stop(r) }
    }
}

/** Denon / Marantz HEOS via the HEOS CLI "play_stream" command. */
class HeosOutput(device: Device) : Output(device) {
    private val pid = device.txt["pid"]?.toLongOrNull() ?: throw IllegalArgumentException("No HEOS player id")
    private var client: HeosClient? = null
    private var volume = -1

    override fun start(env: StreamEnv) {
        val c = HeosClient(device.host).connect()
        client = c
        c.playStream(pid, env.aacUrl)
        if (volume >= 0) runCatching { c.setVolume(pid, volume) }
    }

    override fun setVolume(percent: Int) {
        volume = percent
        client?.let { c -> Engine.scope.launch { runCatching { c.setVolume(pid, percent) } } }
    }

    override fun stop() {
        client?.let { c ->
            runCatching { c.stop(pid) }
            c.close()
        }
        client = null
    }
}

/** Google Cast: Default Media Receiver playing our AAC stream as a live stream. */
class CastOutput(device: Device) : Output(device) {
    private var client: CastClient? = null
    private var volume = -1
    @Volatile private var stopping = false

    override fun start(env: StreamEnv) {
        val c = CastClient(device.host, device.port)
        c.onClosed = {
            if (!stopping) Engine.setOutputState(device.id, OutputState.Failed("disconnected by the Chromecast"))
        }
        c.connect()
        client = c
        val np = Engine.nowPlaying.value
        c.load(
            env.aacUrl, "audio/aac",
            np?.title?.takeIf { it.isNotBlank() } ?: "SoundBeam",
            np?.artist, env.artworkUrl(),
        )
        if (volume >= 0) runCatching { c.setVolume(volume) }
    }

    override fun setVolume(percent: Int) {
        volume = percent
        client?.let { c -> Engine.scope.launch { runCatching { c.setVolume(percent) } } }
    }

    override fun stop() {
        stopping = true
        client?.stop()
        client = null
    }
}
