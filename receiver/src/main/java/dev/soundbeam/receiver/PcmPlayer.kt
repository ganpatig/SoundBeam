package dev.soundbeam.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Plays 48 kHz stereo 16-bit frames through an AudioTrack with a small jitter buffer:
 * waits for [PREBUFFER] frames before starting, inserts silence on underrun and drops the
 * oldest frame when the buffer overflows (which also absorbs clock drift between devices).
 */
class PcmPlayer {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_FRAMES)
    @Volatile private var running = false
    private var thread: Thread? = null
    val received = AtomicLong()
    val underruns = AtomicLong()
    val overruns = AtomicLong()

    fun start() {
        if (running) return
        running = true
        queue.clear()
        thread = thread(name = "pcm-player") { loop() }
    }

    fun enqueue(frame: ByteArray) {
        received.incrementAndGet()
        if (!queue.offer(frame)) {
            queue.poll()
            overruns.incrementAndGet()
            queue.offer(frame)
        }
    }

    fun queued() = queue.size

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    @Suppress("DEPRECATION")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, FRAME_BYTES * 6)
        val track = if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM,
            )
        }
        val silence = ByteArray(FRAME_BYTES)
        try {
            // Prebuffer
            while (running && queue.size < PREBUFFER) Thread.sleep(5)
            track.play()
            while (running) {
                val f = queue.poll(30, TimeUnit.MILLISECONDS)
                if (f == null) {
                    underruns.incrementAndGet()
                    track.write(silence, 0, silence.size)
                } else {
                    track.write(f, 0, f.size)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    companion object {
        const val RATE = 48000
        const val FRAME_BYTES = 3840
        const val PREBUFFER = 25   // 500 ms
        const val MAX_FRAMES = 100 // 2 s
    }
}
