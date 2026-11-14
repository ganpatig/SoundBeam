package dev.soundbeam.sync

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import dev.soundbeam.Engine
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.Device
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol
import dev.soundbeam.core.Speakers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

sealed class SyncState {
    object Idle : SyncState()
    class Running(val step: String, val progress: Float) : SyncState()
    class Done(val lines: List<String>, val warnings: List<String>) : SyncState()
    class Failed(val message: String) : SyncState()
}

/** A set of outputs that is measured and delayed together (all AirPlay speakers share one clock). */
data class SyncGroup(val key: String, val name: String, val devices: List<Device>)

/**
 * Automatic speaker sync. For each group in turn: silence the other speakers, replace the stream
 * with a three-chirp test pattern, record the phone's microphone and find when the chirps arrive.
 * The differences become per-speaker delays so every speaker lines up with the slowest one.
 */
object AutoSync {
    val state = MutableStateFlow<SyncState>(SyncState.Idle)

    // ---- capture-thread side ------------------------------------------------------------------
    @Volatile private var silence = false
    @Volatile private var pattern: ShortArray? = null
    @Volatile private var injectStartNs = 0L
    private var pos = 0

    /** Called from [dev.soundbeam.core.StreamSession.onPcm] before audio goes anywhere. */
    fun inject(pcm: ShortArray, len: Int): Boolean {
        if (!silence) return false
        val p = pattern
        if (p == null) {
            java.util.Arrays.fill(pcm, 0, len, 0)
            return true
        }
        if (pos == 0) injectStartNs = System.nanoTime()
        for (i in 0 until len) pcm[i] = if (pos < p.size) p[pos++] else 0
        return true
    }

    // ---- groups ------------------------------------------------------------------------------

    /** Speakers that can be synced right now, grouped as they must be measured. */
    fun groups(): List<SyncGroup> {
        val session = Engine.session ?: return emptyList()
        val states = Engine.outputStates.value
        val active = session.activeDevices().filter {
            it.protocol != Protocol.LIVE && states[it.id] is OutputState.Playing
        }
        val (airplay, others) = active.partition { it.protocol == Protocol.AIRPLAY || it.protocol == Protocol.AIRPLAY2 }
        val names = Speakers.group(Engine.devices.value, Engine.via.value).associate { g -> g.chosen.host to g.name }
        val out = ArrayList<SyncGroup>()
        if (airplay.isNotEmpty()) {
            val label = if (airplay.size == 1) names[airplay[0].host] ?: airplay[0].name else "AirPlay speakers (${airplay.size})"
            out += SyncGroup("airplay", label, airplay)
        }
        others.forEach { d -> out += SyncGroup(d.id, names[d.host] ?: d.name, listOf(d)) }
        return out
    }

    // ---- calibration -------------------------------------------------------------------------

    suspend fun run(ctx: Context) {
        if (state.value is SyncState.Running) return
        val session = Engine.session
        val groups = groups()
        if (session == null || groups.size < 2) {
            state.value = SyncState.Failed("Play on at least two different kinds of speakers first.")
            return
        }
        val allIds = session.activeDevices().map { it.id }
        val measured = LinkedHashMap<String, Double>()
        val warnings = ArrayList<String>()
        try {
            silence = true
            for ((index, g) in groups.withIndex()) {
                state.value = SyncState.Running("Listening to ${g.name}…", index.toFloat() / groups.size)
                // Only this group may make sound
                val ids = g.devices.map { it.id }.toSet()
                allIds.forEach { id ->
                    val vol = if (id in ids) maxOf(Engine.volumeFor(id), 35) else 0
                    session.output(id)?.setVolume(vol)
                }
                delay(1500) // volume commands and silence reach the speakers
                val ms = measureOnce()
                if (ms == null) {
                    warnings += "Couldn't hear ${g.name} – turn it up or move the phone closer, then try again."
                } else {
                    measured[g.key] = ms
                }
                pattern = null
                delay(300)
            }
        } catch (e: SecurityException) {
            finish(session, allIds)
            state.value = SyncState.Failed("SoundBeam needs microphone access to listen to your speakers.")
            return
        } catch (e: Exception) {
            finish(session, allIds)
            state.value = SyncState.Failed("Sync stopped: ${e.message}")
            return
        }
        finish(session, allIds)

        if (measured.size < 2) {
            state.value = SyncState.Failed(
                warnings.firstOrNull() ?: "Couldn't hear enough speakers to compare them.",
            )
            return
        }
        // Current delays per group (AirPlay group shares one value)
        val current = groups.filter { it.key in measured }.associate { g -> g.key to Engine.delayFor(g.devices.first().id) }
        val plan = SyncPlanner.plan(measured, current)
        val lines = ArrayList<String>()
        for (g in groups) {
            val d = plan[g.key] ?: continue
            g.devices.forEach { Engine.setDelay(it.id, d) }
            lines += if (d == 0) "${g.name}: no delay (slowest)" else "${g.name}: held back %.2f s".format(d / 1000.0)
        }
        state.value = SyncState.Done(lines, warnings)
    }

    private fun finish(session: dev.soundbeam.core.StreamSession, ids: List<String>) {
        pattern = null
        silence = false
        ids.forEach { id -> session.output(id)?.setVolume(Engine.volumeFor(id)) }
    }

    fun reset() {
        if (state.value !is SyncState.Running) state.value = SyncState.Idle
    }

    /** Plays the pattern once and returns ms from injection to arrival at the mic, or null. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before streaming can start
    private suspend fun measureOnce(): Double? = withContext(Dispatchers.IO) {
        val rate = 48000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microphone unavailable")
        try {
            val total = rate * (MAX_LATENCY_MS + SyncSignal.PATTERN_MS + 800) / 1000
            val buf = ShortArray(total)
            rec.startRecording()
            var n = 0
            // Warm up, then start the test sound
            n += rec.read(buf, n, rate / 4)
            val ts = AudioTimestamp()
            val haveTs = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            val fallbackNs = System.nanoTime()
            val fallbackFrame = n
            pos = 0
            pattern = SyncSignal.pattern(SAMPLE_RATE, CHANNELS)
            while (n < total) {
                val r = rec.read(buf, n, minOf(4096, total - n))
                if (r <= 0) break
                n += r
            }
            val idx = SyncSignal.detect(buf.copyOf(n), rate) ?: return@withContext null
            val arrivalNs = if (haveTs) {
                ts.nanoTime + ((idx - ts.framePosition) * 1e9 / rate).toLong()
            } else {
                fallbackNs + ((idx - fallbackFrame) * 1e9 / rate).toLong()
            }
            (arrivalNs - injectStartNs) / 1e6
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Longest speaker delay we listen for (Sonos / DLNA can buffer several seconds). */
    private const val MAX_LATENCY_MS = 7000
}
