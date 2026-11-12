package dev.soundbeam.util

import java.io.ByteArrayOutputStream

/** HomeKit-style TLV8 encoding (values over 255 bytes are split into consecutive fragments). */
object Tlv8 {
    const val METHOD = 0
    const val IDENTIFIER = 1
    const val SALT = 2
    const val PUBLIC_KEY = 3
    const val PROOF = 4
    const val ENCRYPTED_DATA = 5
    const val STATE = 6
    const val ERROR = 7
    const val SIGNATURE = 10
    const val FLAGS = 19

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            if (value.isEmpty()) {
                out.write(type); out.write(0)
                continue
            }
            var off = 0
            while (off < value.size) {
                val len = minOf(255, value.size - off)
                out.write(type)
                out.write(len)
                out.write(value, off, len)
                off += len
            }
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        var lastType = -1
        while (pos + 2 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            pos += 2
            if (pos + len > data.size) break
            val value = data.copyOfRange(pos, pos + len)
            pos += len
            result[type] = if (type == lastType && result.containsKey(type)) result[type]!! + value else value
            lastType = type
        }
        return result
    }

    fun byte(v: Int) = byteArrayOf(v.toByte())
}
