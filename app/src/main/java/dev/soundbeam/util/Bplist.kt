package dev.soundbeam.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal Apple binary property list (bplist00) encoder/decoder.
 * Supports dict (String keys), array, string, integer, real, boolean, data and null.
 */
object Bplist {

    // ---------------------------------------------------------------- encode

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val refs = ArrayList<IntArray?>()
        fun add(o: Any?): Int {
            val idx = objects.size
            objects.add(o)
            refs.add(null)
            when (o) {
                is Map<*, *> -> {
                    val keyRefs = o.keys.map { add(it.toString()) }
                    val valRefs = o.values.map { add(it) }
                    refs[idx] = (keyRefs + valRefs).toIntArray()
                }
                is List<*> -> refs[idx] = o.map { add(it) }.toIntArray()
            }
            return idx
        }
        add(root)

        val refSize = when {
            objects.size < 256 -> 1
            objects.size < 65536 -> 2
            else -> 4
        }
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = LongArray(objects.size)
        for ((i, o) in objects.withIndex()) {
            offsets[i] = out.size().toLong()
            writeObject(out, o, refs[i], refSize)
        }
        val tableOffset = out.size().toLong()
        val maxOffset = offsets.maxOrNull() ?: 0
        val offSize = when {
            maxOffset < 256 -> 1
            maxOffset < 65536 -> 2
            maxOffset < 4294967296L -> 4
            else -> 8
        }
        for (off in offsets) writeSized(out, off, offSize)
        // trailer
        repeat(6) { out.write(0) }
        out.write(offSize)
        out.write(refSize)
        writeSized(out, objects.size.toLong(), 8)
        writeSized(out, 0, 8)
        writeSized(out, tableOffset, 8)
        return out.toByteArray()
    }

    private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, count: Int) {
        if (count < 15) {
            out.write((type shl 4) or count)
        } else {
            out.write((type shl 4) or 0xF)
            writeInt(out, count.toLong())
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..0xFF -> { out.write(0x10); writeSized(out, v, 1) }
            v in 0..0xFFFF -> { out.write(0x11); writeSized(out, v, 2) }
            v in 0..0xFFFFFFFFL -> { out.write(0x12); writeSized(out, v, 4) }
            else -> { out.write(0x13); writeSized(out, v, 8) }
        }
    }

    private fun writeObject(out: ByteArrayOutputStream, o: Any?, refs: IntArray?, refSize: Int) {
        when (o) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (o) 0x09 else 0x08)
            is Int -> writeInt(out, o.toLong())
            is Long -> writeInt(out, o)
            is Short -> writeInt(out, o.toLong())
            is Double -> { out.write(0x23); writeSized(out, java.lang.Double.doubleToRawLongBits(o), 8) }
            is Float -> { out.write(0x23); writeSized(out, java.lang.Double.doubleToRawLongBits(o.toDouble()), 8) }
            is ByteArray -> { writeMarker(out, 0x4, o.size); out.write(o) }
            is String -> {
                if (o.all { it.code < 128 }) {
                    writeMarker(out, 0x5, o.length)
                    out.write(o.toByteArray(Charsets.US_ASCII))
                } else {
                    writeMarker(out, 0x6, o.length)
                    out.write(o.toByteArray(Charsets.UTF_16BE))
                }
            }
            is List<*> -> {
                writeMarker(out, 0xA, o.size)
                refs!!.forEach { writeSized(out, it.toLong(), refSize) }
            }
            is Map<*, *> -> {
                writeMarker(out, 0xD, o.size)
                refs!!.forEach { writeSized(out, it.toLong(), refSize) }
            }
            else -> throw IllegalArgumentException("Unsupported plist type: ${o.javaClass}")
        }
    }

    // ---------------------------------------------------------------- decode

    fun decode(data: ByteArray): Any? {
        if (data.size < 40 || String(data, 0, 8, Charsets.US_ASCII) != "bplist00") {
            throw IllegalArgumentException("Not a binary plist")
        }
        val t = data.size - 32
        val offSize = data[t + 6].toInt() and 0xFF
        val refSize = data[t + 7].toInt() and 0xFF
        val numObjects = readSized(data, t + 8, 8).toInt()
        val top = readSized(data, t + 16, 8).toInt()
        val tableOffset = readSized(data, t + 24, 8).toInt()
        val offsets = IntArray(numObjects) { readSized(data, tableOffset + it * offSize, offSize).toInt() }
        return Decoder(data, offsets, refSize).parse(top, 0)
    }

    private fun readSized(d: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (d[pos + i].toLong() and 0xFF)
        return v
    }

    private class Decoder(val d: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun parse(idx: Int, depth: Int): Any? {
            require(depth < 64) { "plist too deep" }
            var pos = offsets[idx]
            val marker = d[pos].toInt() and 0xFF
            val type = marker shr 4
            val info = marker and 0xF
            pos++
            fun count(): Int {
                if (info != 0xF) return info
                val intMarker = d[pos].toInt() and 0xFF
                val n = 1 shl (intMarker and 0xF)
                val c = readSized(d, pos + 1, n).toInt()
                pos += 1 + n
                return c
            }
            return when (type) {
                0x0 -> when (info) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> readSized(d, pos, 1 shl info)
                0x2 -> if (info == 2) {
                    java.lang.Float.intBitsToFloat(readSized(d, pos, 4).toInt()).toDouble()
                } else {
                    java.lang.Double.longBitsToDouble(readSized(d, pos, 8))
                }
                0x3 -> java.lang.Double.longBitsToDouble(readSized(d, pos, 8))
                0x4 -> { val c = count(); d.copyOfRange(pos, pos + c) }
                0x5 -> { val c = count(); String(d, pos, c, Charsets.US_ASCII) }
                0x6 -> { val c = count(); String(d, pos, c * 2, Charsets.UTF_16BE) }
                0x8 -> readSized(d, pos, info + 1)
                0xA -> {
                    val c = count()
                    List(c) { parse(readSized(d, pos + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val c = count()
                    val m = LinkedHashMap<String, Any?>()
                    for (i in 0 until c) {
                        val k = parse(readSized(d, pos + i * refSize, refSize).toInt(), depth + 1)
                        val v = parse(readSized(d, pos + (c + i) * refSize, refSize).toInt(), depth + 1)
                        m[k.toString()] = v
                    }
                    m
                }
                else -> null
            }
        }
    }

    @Suppress("unused")
    private fun ByteBuffer.unused() = Unit
}
