package dev.soundbeam.audio

import java.io.File
import java.io.RandomAccessFile

const val SAMPLE_RATE = 44100
const val CHANNELS = 2
const val BITS = 16

/** Destination for captured 16-bit little-endian stereo PCM. */
interface AudioSink {
    fun write(pcm: ByteArray, len: Int)
    fun stop()
}

/** Writes the captured audio to a .wav file; the header is patched with the real size on stop. */
class WavFileSink(val file: File) : AudioSink {
    private val raf = RandomAccessFile(file, "rw")
    private var bytes = 0L

    init {
        raf.setLength(0)
        raf.write(Wav.header(SAMPLE_RATE, CHANNELS, BITS, 0))
    }

    @Synchronized
    override fun write(pcm: ByteArray, len: Int) {
        raf.write(pcm, 0, len)
        bytes += len
    }

    @Synchronized
    override fun stop() {
        try {
            raf.seek(0)
            raf.write(Wav.header(SAMPLE_RATE, CHANNELS, BITS, bytes))
        } finally {
            raf.close()
        }
    }
}
