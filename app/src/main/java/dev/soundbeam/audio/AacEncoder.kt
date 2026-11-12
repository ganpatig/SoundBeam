package dev.soundbeam.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Encodes PCM to AAC-LC with the platform MediaCodec and emits ADTS frames
 * (the format Chromecast, Sonos and HEOS accept for live HTTP streams).
 */
class AacEncoder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = CHANNELS,
    bitrate: Int = 256_000,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds little-endian PCM bytes. Called from the capture thread. */
    fun encode(pcm: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) break
            val buf = codec.getInputBuffer(idx) ?: break
            buf.clear()
            val n = minOf(buf.remaining(), len - off)
            buf.put(pcm, off, n)
            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
            ptsUs += n * 1_000_000L / (sampleRate * channels * 2)
            off += n
            drain()
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val out = codec.getOutputBuffer(idx)
                if (out != null) {
                    val frame = ByteArray(info.size + 7)
                    writeAdtsHeader(frame, info.size + 7)
                    out.position(info.offset)
                    out.get(frame, 7, info.size)
                    onFrame(frame)
                }
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    private fun writeAdtsHeader(h: ByteArray, frameLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // MPEG-4, no CRC
        h[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (channels shr 2)).toByte()
        h[3] = (((channels and 3) shl 6) or (frameLen shr 11)).toByte()
        h[4] = ((frameLen and 0x7FF) shr 3).toByte()
        h[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }
}
