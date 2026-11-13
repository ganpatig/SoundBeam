package dev.soundbeam.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.soundbeam.Engine
import dev.soundbeam.MainActivity
import dev.soundbeam.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** What the "Listen to a friend" side is doing, for the UI. */
object LiveReceiverState {
    val listening = MutableStateFlow(false)
    val sender = MutableStateFlow<String?>(null)
    val delayMs = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
}

/**
 * Receives SoundBeam Live audio and plays it with as little delay as Android allows:
 * a low-latency AudioTrack fed from a ~40 ms adaptive [JitterBuffer].
 */
class LiveReceiver(private val name: String) {
    @Volatile private var running = false
    private var audio: DatagramSocket? = null
    private var discovery: DatagramSocket? = null
    @Volatile private var buffer: JitterBuffer? = null
    @Volatile private var rate = 0
    @Volatile private var channels = 2
    @Volatile private var senderAddr: InetAddress? = null
    @Volatile private var lastPacketAt = 0L

    fun start() {
        running = true
        audio = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = 256 * 1024
            soTimeout = 500
            bind(InetSocketAddress(LiveProtocol.AUDIO_PORT))
        }
        discovery = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(LiveProtocol.DISCOVERY_PORT))
        }
        thread(name = "live-net") { netLoop() }
        thread(isDaemon = true, name = "live-discovery") { discoveryLoop() }
        thread(name = "live-play") { playLoop() }
        LiveReceiverState.listening.value = true
    }

    fun stop() {
        running = false
        audio?.close()
        discovery?.close()
        LiveReceiverState.listening.value = false
        LiveReceiverState.sender.value = null
    }

    private fun discoveryLoop() {
        val s = discovery ?: return
        val buf = ByteArray(64)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (p.length != LiveProtocol.PROBE.size || !buf.copyOf(p.length).contentEquals(LiveProtocol.PROBE)) continue
                val reply = JSONObject().put("name", name).put("port", LiveProtocol.AUDIO_PORT).toString().toByteArray()
                s.send(DatagramPacket(reply, reply.size, p.address, p.port))
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun netLoop() {
        val s = audio ?: return
        val buf = ByteArray(4096)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val pkt = LiveProtocol.decode(buf, p.length) ?: continue
                val from = p.address
                val now = SystemClock.elapsedRealtime()
                // Follow one sender at a time; a new one may take over after 2 s of silence.
                if (senderAddr != null && senderAddr != from && now - lastPacketAt < 2000) continue
                if (senderAddr != from) {
                    senderAddr = from
                    buffer?.clear()
                }
                lastPacketAt = now
                when (pkt.type) {
                    LiveProtocol.TYPE_INFO -> LiveReceiverState.sender.value = pkt.text ?: from.hostAddress
                    LiveProtocol.TYPE_STOP -> {
                        senderAddr = null
                        buffer?.clear()
                        LiveReceiverState.sender.value = null
                    }
                    LiveProtocol.TYPE_AUDIO -> {
                        if (pkt.sampleRate != rate || pkt.channels != channels || buffer == null) {
                            rate = pkt.sampleRate
                            channels = pkt.channels
                            buffer = JitterBuffer(rate, channels)
                        }
                        if (LiveReceiverState.sender.value == null) LiveReceiverState.sender.value = from.hostAddress
                        buffer?.push(pkt.seq, pkt.pcm)
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (senderAddr != null && SystemClock.elapsedRealtime() - lastPacketAt > 3000) {
                    senderAddr = null
                    buffer?.clear()
                    LiveReceiverState.sender.value = null
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var trackRate = 0
        var trackChannels = 0
        var lastReport = 0L
        try {
            while (running) {
                val jb = buffer
                if (jb == null || rate == 0) {
                    Thread.sleep(5)
                    continue
                }
                if (track == null || trackRate != rate || trackChannels != channels) {
                    track?.release()
                    track = makeTrack(rate, channels)
                    trackRate = rate
                    trackChannels = channels
                    track.play()
                }
                val chunk = jb.pull()
                if (chunk == null) {
                    Thread.sleep(2)
                } else {
                    track.write(chunk, 0, chunk.size)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 500) {
                    lastReport = now
                    val trackMs = track.bufferSizeInFrames * 1000 / maxOf(1, trackRate)
                    LiveReceiverState.delayMs.value = jb.bufferedMs() + trackMs + 6 // + one packet
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        }
    }

    private fun makeTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}

/** Keeps "Listen to a friend" running with the screen off. */
class LiveReceiverService : Service() {
    private var receiver: LiveReceiver? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (receiver != null) return START_STICKY
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundbeam-live").apply { acquire() }
        multicastLock = wifi.createMulticastLock("soundbeam-live").apply { setReferenceCounted(false); acquire() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundbeam:live").apply { acquire(6 * 60 * 60 * 1000L) }
        try {
            Engine.init(applicationContext)
            receiver = LiveReceiver(Engine.phoneName).also { it.start() }
            LiveReceiverState.error.value = null
        } catch (e: Exception) {
            LiveReceiverState.error.value = "Couldn't start listening: ${e.message}"
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.stop()
        receiver = null
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        LiveReceiverState.listening.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("listen", "Listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "listen")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Listening with SoundBeam")
            .setContentText("Playing your friend's sound on this phone")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.soundbeam.live.STOP"
    }
}
