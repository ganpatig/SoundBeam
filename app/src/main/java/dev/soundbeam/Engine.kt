package dev.soundbeam

import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import dev.soundbeam.airplay.Crypto
import dev.soundbeam.airplay.PairedAccessory
import dev.soundbeam.airplay.PairingIdentity
import dev.soundbeam.airplay.hexToBytes
import dev.soundbeam.airplay.toHex
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.Equalizer
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.Device
import dev.soundbeam.core.Discovery
import dev.soundbeam.core.NowPlaying
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.StreamSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Process-wide app state shared by the UI, [CaptureService] and the outputs. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val equalizer = Equalizer(SAMPLE_RATE, CHANNELS)

    // discovery & selection
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val scanning = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val volumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val outputStates = MutableStateFlow<Map<String, OutputState>>(emptyMap())

    // stream
    val running = MutableStateFlow(false)
    val httpEnabled = MutableStateFlow(false)
    val saveEnabled = MutableStateFlow(false)
    val streamUrls = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("")
    val eqGains = MutableStateFlow(List(10) { 0f })
    val airplayLatencyMs = MutableStateFlow(2000)

    // now playing
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val notificationAccess = MutableStateFlow(false)
    @Volatile var mediaController: MediaController? = null

    // AirPlay PIN pairing
    val pinRequest = MutableStateFlow<Device?>(null)
    private var pinAnswer: CompletableDeferred<String?>? = null

    @Volatile var session: StreamSession? = null

    private lateinit var prefs: SharedPreferences
    private var initialised = false

    fun init(ctx: Context) {
        if (initialised) return
        initialised = true
        prefs = ctx.getSharedPreferences("soundbeam", Context.MODE_PRIVATE)
        httpEnabled.value = prefs.getBoolean("http", false)
        saveEnabled.value = prefs.getBoolean("save", false)
        airplayLatencyMs.value = prefs.getInt("airplayLatency", 2000)
        selected.value = prefs.getStringSet("selected", emptySet())!!.toSet()
        volumes.value = readIntMap("volumes")
        delays.value = readIntMap("delays")
        prefs.getString("eq", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }?.let { setEq(it) }
    }

    private fun readIntMap(key: String): Map<String, Int> {
        val j = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrNull() ?: return emptyMap()
        return j.keys().asSequence().associateWith { j.optInt(it) }
    }

    private fun writeIntMap(key: String, m: Map<String, Int>) {
        prefs.edit().putString(key, JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ------------------------------------------------------------------------------ discovery

    fun scan(ctx: Context) {
        if (scanning.value) return
        scanning.value = true
        status.value = "Scanning…"
        val app = ctx.applicationContext
        scope.launch {
            try {
                val found = Discovery.scan(app)
                // keep devices that are currently streaming even if they missed this scan
                val active = session?.activeDevices().orEmpty()
                devices.value = (found + active.filter { a -> found.none { it.id == a.id } })
                status.value = if (found.isEmpty()) "No receivers found on this network" else "Found ${found.size} receiver(s)"
            } catch (e: Exception) {
                status.value = "Scan failed: ${e.message}"
            } finally {
                scanning.value = false
            }
        }
    }

    // ------------------------------------------------------------------------------ selection

    fun toggleSelected(device: Device) {
        val now = selected.value.let { if (device.id in it) it - device.id else it + device.id }
        selected.value = now
        prefs.edit().putStringSet("selected", now).apply()
        session?.let { s -> if (device.id in now) s.addOutput(device) else s.removeOutput(device.id) }
    }

    fun volumeFor(id: String) = volumes.value[id] ?: 50

    fun delayFor(id: String) = delays.value[id] ?: 0

    fun setVolume(id: String, v: Int) {
        volumes.value = volumes.value + (id to v)
        writeIntMap("volumes", volumes.value)
        session?.output(id)?.setVolume(v)
    }

    fun setDelay(id: String, ms: Int) {
        delays.value = delays.value + (id to ms)
        writeIntMap("delays", delays.value)
        session?.output(id)?.setDelayMs(ms)
    }

    /** Delay for HTTP clients, looked up by the address the receiver connects from. */
    fun delayForHost(host: String): Int {
        val ids = selected.value
        val d = devices.value.firstOrNull { it.id in ids && it.host == host } ?: return 0
        return delayFor(d.id)
    }

    fun setOutputState(id: String, state: OutputState?) {
        outputStates.value = if (state == null) outputStates.value - id else outputStates.value + (id to state)
    }

    // ------------------------------------------------------------------------------ settings

    fun setHttp(on: Boolean) {
        httpEnabled.value = on
        prefs.edit().putBoolean("http", on).apply()
    }

    fun setSave(on: Boolean) {
        saveEnabled.value = on
        prefs.edit().putBoolean("save", on).apply()
    }

    fun setAirplayLatency(ms: Int) {
        airplayLatencyMs.value = ms
        prefs.edit().putInt("airplayLatency", ms).apply()
    }

    fun setEq(gains: List<Float>) {
        eqGains.value = gains
        equalizer.setGains(gains)
        if (initialised) prefs.edit().putString("eq", gains.joinToString(",")).apply()
    }

    // ------------------------------------------------------------------------------ media control

    /** Forwards a remote command (from an AriaCast receiver) to the app that is playing. */
    fun mediaCommand(action: String, positionMs: Long = -1) {
        val c = mediaController?.transportControls ?: return
        when (action.lowercase()) {
            "play" -> c.play()
            "pause" -> c.pause()
            "toggle", "play_pause" -> if (nowPlaying.value?.playing == true) c.pause() else c.play()
            "next" -> c.skipToNext()
            "previous" -> c.skipToPrevious()
            "stop" -> c.stop()
            "seek" -> if (positionMs >= 0) c.seekTo(positionMs)
        }
    }

    // ------------------------------------------------------------------------------ AirPlay identity

    @Synchronized
    fun airplayIdentity(): PairingIdentity {
        var id = prefs.getString("apId", null)
        var seed = prefs.getString("apSeed", null)
        if (id == null || seed == null) {
            id = Crypto.randomBytes(8).toHex().uppercase()
            seed = Crypto.randomBytes(32).toHex()
            prefs.edit().putString("apId", id).putString("apSeed", seed).apply()
        }
        return PairingIdentity(id, seed.hexToBytes())
    }

    fun airplayDeviceIdColon(): String =
        airplayIdentity().pairingId.takeLast(12).chunked(2).joinToString(":")

    fun airplayPairing(deviceId: String): PairedAccessory? {
        val s = prefs.getString("pair:$deviceId", null) ?: return null
        val (acc, pk) = s.split('|').takeIf { it.size == 2 } ?: return null
        return PairedAccessory(acc, pk.hexToBytes())
    }

    fun saveAirplayPairing(deviceId: String, a: PairedAccessory) {
        prefs.edit().putString("pair:$deviceId", "${a.accessoryId}|${a.publicKey.toHex()}").apply()
    }

    fun forgetAirplayPairing(deviceId: String) {
        prefs.edit().remove("pair:$deviceId").apply()
    }

    /** Blocks the calling (background) thread until the user enters the PIN shown on the receiver. */
    fun awaitPin(device: Device): String? {
        val d = CompletableDeferred<String?>()
        synchronized(this) {
            pinAnswer?.complete(null)
            pinAnswer = d
        }
        pinRequest.value = device
        return runBlocking { withTimeoutOrNull(180_000) { d.await() } }.also {
            if (pinRequest.value?.id == device.id) pinRequest.value = null
        }
    }

    fun submitPin(pin: String?) {
        pinRequest.value = null
        synchronized(this) {
            pinAnswer?.complete(pin?.trim()?.takeIf { it.isNotEmpty() })
            pinAnswer = null
        }
    }
}
