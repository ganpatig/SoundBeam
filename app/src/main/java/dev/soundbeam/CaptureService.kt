package dev.soundbeam

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
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.soundbeam.audio.AudioSink
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.audio.WavFileSink
import dev.soundbeam.net.HttpStreamServer
import dev.soundbeam.net.Renderer
import dev.soundbeam.net.Upnp
import dev.soundbeam.net.localIpAddress
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures the device's playback audio (Android 10+ AudioPlaybackCapture),
 * runs it through the equalizer and fans it out to the enabled outputs.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var http: HttpStreamServer? = null
    private var fileSink: WavFileSink? = null
    private var activeRenderers: List<Renderer> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        if (running) return
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            fail("Missing screen-capture grant")
            return
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    shutdown()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj

            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            @Suppress("MissingPermission") // RECORD_AUDIO is requested by the activity first
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, 16384) * 2)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            record = rec

            val sinks = mutableListOf<AudioSink>()
            val wantHttp = Engine.httpEnabled.value
            val chosen = Engine.renderers.value.filter { it.id in Engine.selected.value }
            if (wantHttp || chosen.isNotEmpty()) {
                val server = HttpStreamServer()
                val port = server.start()
                http = server
                sinks.add(server)
                val ip = localIpAddress(this) ?: "localhost"
                val url = "http://$ip:$port/stream.wav"
                Engine.streamUrl.value = if (wantHttp) url else null
                activeRenderers = chosen
                beginRenderers(chosen, url)
            }
            if (Engine.saveEnabled.value) {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val sink = WavFileSink(File(dir, "soundbeam-$stamp.wav"))
                fileSink = sink
                sinks.add(sink)
                Engine.status.value = "Saving to ${sink.file.absolutePath}"
            }

            running = true
            Engine.running.value = true
            if (chosen.isEmpty() && !wantHttp && !Engine.saveEnabled.value) {
                Engine.status.value = "Capturing, but no output is enabled"
            }
            rec.startRecording()
            thread = Thread({ captureLoop(rec, sinks) }, "soundbeam-capture").also { it.start() }
        } catch (e: Exception) {
            fail("Could not start capture: ${e.message}")
        }
    }

    private fun beginRenderers(renderers: List<Renderer>, url: String) {
        Engine.scope.launch {
            for (r in renderers) {
                try {
                    Upnp.setUriAndPlay(r, url, "SoundBeam")
                } catch (e: Exception) {
                    Engine.status.value = "${r.name}: ${e.message}"
                }
            }
        }
    }

    private fun captureLoop(rec: AudioRecord, sinks: List<AudioSink>) {
        val shorts = ShortArray(2048 * CHANNELS)
        val bytes = ByteArray(shorts.size * 2)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (running) {
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            Engine.equalizer.process(shorts, n)
            sb.clear()
            sb.put(shorts, 0, n)
            for (s in sinks) {
                try {
                    s.write(bytes, n * 2)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun fail(message: String) {
        Engine.status.value = message
        shutdown()
    }

    @Synchronized
    private fun shutdown() {
        val wasRunning = running
        running = false
        thread?.let { t ->
            try { t.join(1500) } catch (_: InterruptedException) {}
        }
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        val renderers = activeRenderers
        activeRenderers = emptyList()
        if (renderers.isNotEmpty()) {
            Engine.scope.launch {
                for (r in renderers) {
                    try { Upnp.stop(r) } catch (_: Exception) {}
                }
            }
        }
        http?.stop()
        http = null
        try { fileSink?.stop() } catch (_: Exception) {}
        fileSink = null

        Engine.running.value = false
        Engine.streamUrl.value = null
        if (wasRunning) Engine.status.value = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SoundBeam is streaming")
            .setContentText("Sending this device's audio to your receivers")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.soundbeam.START"
        const val ACTION_STOP = "dev.soundbeam.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIF_ID = 1
    }
}
