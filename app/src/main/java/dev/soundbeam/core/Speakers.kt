package dev.soundbeam.core

/**
 * One physical speaker as the user sees it. A Sonos Move can be reachable over AirPlay 2 and
 * over Sonos's own protocol; a Denon AVR over AirPlay, HEOS and DLNA. We show it once and let
 * the user switch the connection type in the speaker's settings if the default misbehaves.
 */
data class SpeakerGroup(
    val key: String,
    val name: String,
    val options: List<Device>,
    val chosen: Device,
) {
    val kind: SpeakerKind
        get() = when {
            options.any { it.protocol == Protocol.RECEIVER } -> SpeakerKind.TV
            (chosen.model ?: "").contains("TV", ignoreCase = true) -> SpeakerKind.TV
            chosen.protocol == Protocol.DLNA -> SpeakerKind.TV
            else -> SpeakerKind.SPEAKER
        }
}

enum class SpeakerKind { SPEAKER, TV }

object Speakers {
    /** Which connection we use by default when a speaker offers several. */
    private val DEFAULT_ORDER = listOf(
        Protocol.AIRPLAY2, Protocol.CAST, Protocol.SONOS, Protocol.HEOS,
        Protocol.AIRPLAY, Protocol.RECEIVER, Protocol.ARIACAST, Protocol.DLNA,
    )

    /** Which discovery source tends to have the nicest human name. */
    private val NAME_ORDER = listOf(
        Protocol.AIRPLAY2, Protocol.AIRPLAY, Protocol.CAST, Protocol.HEOS,
        Protocol.RECEIVER, Protocol.ARIACAST, Protocol.SONOS, Protocol.DLNA,
    )

    fun groupKey(d: Device): String =
        if (d.protocol == Protocol.CAST && d.port != 8009) "${d.host}:${d.port}" else d.host

    fun group(devices: List<Device>, via: Map<String, String>): List<SpeakerGroup> =
        devices.groupBy { groupKey(it) }.map { (key, list) ->
            val sorted = list.sortedBy { DEFAULT_ORDER.indexOf(it.protocol) }
            val chosen = list.firstOrNull { it.id == via[key] } ?: sorted.first()
            val name = list.minBy { NAME_ORDER.indexOf(it.protocol) }.name
            SpeakerGroup(key, cleanName(name), sorted, chosen)
        }.sortedBy { it.name.lowercase() }

    /** Sonos UPnP names look like "192.168.1.20 - Sonos Move 2 - RINCON_..."; keep the readable part. */
    private fun cleanName(n: String): String {
        val parts = n.split(" - ").map { it.trim() }
        if (parts.size >= 2 && parts[0].matches(Regex("[0-9.]+"))) return parts[1]
        return n
    }

    fun connectionTitle(p: Protocol): String = when (p) {
        Protocol.AIRPLAY2 -> "AirPlay 2"
        Protocol.AIRPLAY -> "AirPlay"
        Protocol.CAST -> "Chromecast"
        Protocol.SONOS -> "Sonos"
        Protocol.HEOS -> "HEOS"
        Protocol.DLNA -> "DLNA"
        Protocol.ARIACAST -> "AriaCast"
        Protocol.RECEIVER -> "SoundBeam Receiver"
    }

    fun connectionHelp(p: Protocol): String = when (p) {
        Protocol.AIRPLAY2 -> "Best choice for HomePod, Apple TV, Sonos and AirPlay speakers. Several AirPlay speakers stay in sync with each other."
        Protocol.AIRPLAY -> "For older AirPlay speakers and AirPort Express."
        Protocol.CAST -> "For Chromecast, Google Nest speakers and TVs with Chromecast built in."
        Protocol.SONOS -> "Uses Sonos's own connection. Try this if AirPlay is unreliable on this speaker."
        Protocol.HEOS -> "For Denon and Marantz HEOS speakers and receivers."
        Protocol.DLNA -> "Works with many smart TVs and AV receivers. Usually a few seconds behind."
        Protocol.ARIACAST -> "For Music Assistant and other AriaCast receivers."
        Protocol.RECEIVER -> "A Fire TV or Android TV running the free SoundBeam Receiver app."
    }
}
