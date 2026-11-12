package dev.soundbeam.audio

/**
 * Produces ALAC frames in "escape" (uncompressed/verbatim) mode, which every ALAC decoder accepts.
 * This keeps the stream lossless without needing a full ALAC encoder.
 */
object Alac {
    const val FRAMES_PER_PACKET = 352

    /** Encodes [frames] stereo 16-bit frames from [pcm] (interleaved, starting at [offset]). */
    fun encodeVerbatim(pcm: ShortArray, offset: Int, frames: Int): ByteArray {
        val w = BitWriter(3 + frames * 4 + 2)
        w.write(1, 3)        // element type: CPE (channel pair)
        w.write(0, 4)        // element instance tag
        w.write(0, 12)       // unused
        val partial = frames != FRAMES_PER_PACKET
        w.write(if (partial) 1 else 0, 1) // has explicit sample count
        w.write(0, 2)        // bytes shifted
        w.write(1, 1)        // escape flag: uncompressed
        if (partial) w.write(frames, 32)
        for (i in 0 until frames * 2) {
            w.write(pcm[offset + i].toInt() and 0xFFFF, 16)
        }
        w.write(7, 3)        // ID_END
        return w.toByteArray()
    }

    private class BitWriter(capacity: Int) {
        private val buf = ByteArray(capacity + 8)
        private var bitPos = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if ((value ushr i) and 1 == 1) {
                    val byteIdx = bitPos ushr 3
                    buf[byteIdx] = (buf[byteIdx].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf((bitPos + 7) ushr 3)
    }
}
