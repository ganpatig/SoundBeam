package dev.soundbeam.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val UNKNOWN_LENGTH = 0xFFFFFFFFL

    /** Builds a 44-byte canonical PCM WAV header. Use [UNKNOWN_LENGTH] for live streams. */
    fun header(sampleRate: Int, channels: Int, bits: Int, dataLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataLen).coerceAtMost(UNKNOWN_LENGTH).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * bits / 8)
        bb.putShort((channels * bits / 8).toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen.coerceAtMost(UNKNOWN_LENGTH).toInt())
        return bb.array()
    }
}
