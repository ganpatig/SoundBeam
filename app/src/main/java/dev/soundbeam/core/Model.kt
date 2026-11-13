package dev.soundbeam.core

import dev.soundbeam.net.Renderer

enum class Protocol(val label: String) {
    AIRPLAY2("AirPlay 2"),
    AIRPLAY("AirPlay"),
    CAST("Chromecast"),
    SONOS("Sonos"),
    HEOS("HEOS"),
    DLNA("DLNA"),
    ARIACAST("AriaCast"),
    RECEIVER("SoundBeam Receiver"),
    LIVE("Friend's phone"),
}

/** A receiver found on the network. [id] is stable across scans so settings can be remembered. */
data class Device(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
    val renderer: Renderer? = null,
    val model: String? = null,
)

/** What another app is currently playing, read from its media session. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long, // SystemClock.elapsedRealtime() of positionMs
    val playing: Boolean,
    val artworkJpeg: ByteArray?,
    val sourcePackage: String?,
) {
    fun currentPositionMs(now: Long): Long =
        if (playing) positionMs + (now - positionUpdatedAt) else positionMs

    val displayTitle: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() })
            .joinToString(" - ")

    // ByteArray in a data class: compare by content where it matters for change detection
    fun sameTrack(o: NowPlaying?) = o != null && o.title == title && o.artist == artist && o.album == album

    override fun equals(other: Any?): Boolean =
        other is NowPlaying && sameTrack(other) && other.playing == playing &&
            other.positionMs == positionMs && other.durationMs == durationMs &&
            (other.artworkJpeg?.size ?: 0) == (artworkJpeg?.size ?: 0)

    override fun hashCode(): Int = (title?.hashCode() ?: 0) * 31 + (artist?.hashCode() ?: 0)
}

sealed class OutputState(val label: String) {
    object Connecting : OutputState("Connecting…")
    object Playing : OutputState("Playing")
    class WaitingForPin(val deviceName: String) : OutputState("Waiting for PIN")
    class Failed(val reason: String) : OutputState("Failed: $reason")
}

/** Everything an output needs to know about the running stream. */
interface StreamEnv {
    val localIp: String
    val httpPort: Int
    val wavUrl get() = "http://$localIp:$httpPort/stream.wav"
    val aacUrl get() = "http://$localIp:$httpPort/stream.aac"
    fun artworkUrl(): String?
    val airplay: dev.soundbeam.airplay.AirPlayMaster
}

/**
 * One active receiver. [start] runs on a background thread and may block on network I/O.
 * [onPcm] is called on the capture thread with equalised 44.1 kHz stereo PCM and must not block.
 */
abstract class Output(val device: Device) {
    abstract fun start(env: StreamEnv)
    open fun onPcm(pcm: ShortArray, len: Int) {}
    open fun setVolume(percent: Int) {}
    open fun setDelayMs(ms: Int) {}
    open fun onMetadata(np: NowPlaying) {}
    abstract fun stop()
}
