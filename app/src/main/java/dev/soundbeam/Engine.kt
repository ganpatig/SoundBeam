package dev.soundbeam

import android.content.Context
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.Equalizer
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.net.Renderer
import dev.soundbeam.net.SsdpScanner
import dev.soundbeam.net.Upnp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide app state shared by the UI and [CaptureService]. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val equalizer = Equalizer(SAMPLE_RATE, CHANNELS)

    val renderers = MutableStateFlow<List<Renderer>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val scanning = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    val httpEnabled = MutableStateFlow(false)
    val saveEnabled = MutableStateFlow(false)
    val streamUrl = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("")
    val eqGains = MutableStateFlow(List(10) { 0f })

    private var prefs: android.content.SharedPreferences? = null
    private var initialised = false

    fun init(ctx: Context) {
        if (initialised) return
        initialised = true
        val p = ctx.getSharedPreferences("soundbeam", Context.MODE_PRIVATE)
        prefs = p
        httpEnabled.value = p.getBoolean("http", false)
        saveEnabled.value = p.getBoolean("save", false)
        p.getString("eq", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }?.let { setEq(it) }
    }

    fun scan(ctx: Context) {
        if (scanning.value) return
        scanning.value = true
        status.value = "Scanning…"
        val app = ctx.applicationContext
        scope.launch {
            try {
                val found = SsdpScanner.scan(app)
                renderers.value = found
                status.value = if (found.isEmpty()) "No DLNA receivers found on this network" else "Found ${found.size} receiver(s)"
            } catch (e: Exception) {
                status.value = "Scan failed: ${e.message}"
            } finally {
                scanning.value = false
            }
        }
    }

    fun toggleSelected(id: String) {
        selected.value = selected.value.let { if (id in it) it - id else it + id }
    }

    fun setHttp(on: Boolean) {
        httpEnabled.value = on
        prefs?.edit()?.putBoolean("http", on)?.apply()
    }

    fun setSave(on: Boolean) {
        saveEnabled.value = on
        prefs?.edit()?.putBoolean("save", on)?.apply()
    }

    fun setEq(gains: List<Float>) {
        eqGains.value = gains
        equalizer.setGains(gains)
        prefs?.edit()?.putString("eq", gains.joinToString(","))?.apply()
    }

    fun setVolume(r: Renderer, volume: Int) {
        scope.launch {
            try {
                Upnp.setVolume(r, volume)
            } catch (e: Exception) {
                status.value = "Volume failed on ${r.name}: ${e.message}"
            }
        }
    }
}
