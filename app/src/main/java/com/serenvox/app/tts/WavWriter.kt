package com.serenvox.app.tts

import java.io.File
import java.io.RandomAccessFile

/** Streams mono 16-bit PCM WAV to disk; header sizes are patched on close. */
class WavWriter(file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L

    init { writeHeader() }

    fun write(samples: FloatArray) {
        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[2 * i] = (v and 0xff).toByte()
            buf[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        raf.write(buf); dataBytes += buf.size
    }

    fun writeSilence(ms: Int) = write(FloatArray(sampleRate * ms / 1000))

    private fun writeHeader() {
        raf.seek(0)
        raf.writeBytes("RIFF"); le32(36 + dataBytes); raf.writeBytes("WAVE")
        raf.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sampleRate.toLong())
        le32(sampleRate * 2L); le16(2); le16(16)
        raf.writeBytes("data"); le32(dataBytes)
    }

    private fun le32(v: Long) { for (i in 0 until 4) raf.write(((v shr (8 * i)) and 0xff).toInt()) }
    private fun le16(v: Int) { raf.write(v and 0xff); raf.write((v shr 8) and 0xff) }

    override fun close() { writeHeader(); raf.close() }
}
