package dev.soundbeam.outputs

import dev.soundbeam.Engine
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.Device
import dev.soundbeam.core.Output
import dev.soundbeam.core.StreamEnv
import dev.soundbeam.live.LiveSender
import kotlin.concurrent.thread

/** Watch Together: streams to a friend's phone with the low-latency SoundBeam Live protocol. */
class LiveOutput(device: Device) : Output(device) {
    @Volatile private var sender: LiveSender? = null
    @Volatile private var stopped = false
    private var gain = 1f

    override fun start(env: StreamEnv) {
        val s = LiveSender(device.host, device.port, Engine.phoneName, SAMPLE_RATE, CHANNELS)
        s.gain = gain
        s.sendInfo()
        sender = s
        thread(isDaemon = true, name = "live-info") {
            while (!stopped) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                sender?.sendInfo()
            }
        }
    }

    // Sent straight from the capture thread: no queue, no extra delay.
    override fun onPcm(pcm: ShortArray, len: Int) {
        sender?.send(pcm, len)
    }

    override fun setVolume(percent: Int) {
        gain = percent.coerceIn(0, 100) / 100f
        sender?.gain = gain
    }

    override fun stop() {
        stopped = true
        sender?.let {
            it.sendStop()
            it.close()
        }
        sender = null
    }
}
