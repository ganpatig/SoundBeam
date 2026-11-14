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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.soundbeam.audio.CHANNELS
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.StreamSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service that captures the device's playback audio (Android 10+ AudioPlaybackCapture),
 * runs it through the equalizer and hands it to the [StreamSession].
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

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
                ?: throw IllegalStateException("Android did not grant audio capture")
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

            val session = StreamSession(applicationContext)
            session.start()
            Engine.session = session
            running = true
            Engine.running.value = true
            if (Engine.selected.value.isEmpty() && !Engine.httpEnabled.value && !Engine.saveEnabled.value) {
                Engine.status.value = "Capturing - tick a receiver to start playing on it"
            }
            rec.startRecording()
            thread = Thread({ captureLoop(rec, session) }, "soundbeam-capture").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        } catch (e: Exception) {
            fail("Could not start capture: ${e.message}")
        }
    }

    private fun captureLoop(rec: AudioRecord, session: StreamSession) {
        // 256 frames (~6 ms) per read: the smallest useful block, so Watch Together adds no wait here
        val shorts = ShortArray(256 * CHANNELS)
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
            try {
                session.onPcm(shorts, n, bytes)
            } catch (_: Exception) {
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

        Engine.session?.let { s ->
            Engine.session = null
            runCatching { s.stop() }
        }
        Engine.running.value = false
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
