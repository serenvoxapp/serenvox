// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.doc

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** Pure-Kotlin extractors for DOCX, EPUB and legacy Word 97-2003 .doc (no third-party libraries). */
object OfficeExtractors {

    private fun unzip(bytes: ByteArray, maxEntryBytes: Int = 50 shl 20): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = z.readNBytesCompat(maxEntryBytes)
            }
        }
        return out
    }

    private fun ZipInputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(16384)
        while (buf.size() < max) { val n = read(b); if (n < 0) break; buf.write(b, 0, n) }
        return buf.toByteArray()
    }

    fun docx(bytes: ByteArray): String {
        val files = unzip(bytes)
        val doc = files["word/document.xml"]?.toString(Charsets.UTF_8) ?: error("Not a Word document")
        // Drop deleted revisions and field instructions.
        val cleaned = doc.replace(Regex("""<w:del\b[\s\S]*?</w:del>"""), "")
            .replace(Regex("""<w:instrText[\s\S]*?</w:instrText>"""), "")
        return TextCleanup.xmlToText(cleaned)
    }

    fun epub(bytes: ByteArray): String {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val opfPath = container?.let { Regex("""full-path="([^"]+)"""").find(it)?.groupValues?.get(1) }
        val opf = opfPath?.let { files[it]?.toString(Charsets.UTF_8) }
        val base = opfPath?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        val ordered: List<String> = if (opf != null) {
            val manifest = Regex("""<item\b[^>]*>""").findAll(opf).associate { m ->
                val tag = m.value
                (Regex("""id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: "") to
                    (Regex("""href="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: "")
            }
            Regex("""<itemref\b[^>]*idref="([^"]+)"""").findAll(opf).mapNotNull { manifest[it.groupValues[1]] }
                .map { base + java.net.URLDecoder.decode(it, "UTF-8") }.toList()
        } else files.keys.filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted()
        return ordered.mapNotNull { files[it]?.toString(Charsets.UTF_8) }
            .joinToString("\n\n") { TextCleanup.xmlToText(it) }
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    // ---------------- Legacy .doc (OLE2 compound file + Word piece table) ----------------

    private class Cfb(private val d: ByteArray) {
        private val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize = 1 shl bb.getShort(0x1E).toInt()
        private val miniSize = 1 shl bb.getShort(0x20).toInt()
        private val cutoff = bb.getInt(0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries = mutableListOf<Triple<String, Int, Long>>() // name, start, size
        private var miniStream = ByteArray(0)

        init {
            require(d.size >= 512 && bb.getLong(0) == 0xE11AB1A1E011CFD0uL.toLong()) { "Not a Word 97-2003 file" }
            val difat = mutableListOf<Int>()
            for (i in 0 until 109) difat += bb.getInt(0x4C + 4 * i)
            var dif = bb.getInt(0x44); var guard = 0
            while (dif >= 0 && guard++ < 10000) {
                val off = secOff(dif)
                for (i in 0 until sectorSize / 4 - 1) difat += bb.getInt(off + 4 * i)
                dif = bb.getInt(off + sectorSize - 4)
            }
            val numFat = bb.getInt(0x2C)
            val fatList = ArrayList<Int>()
            difat.filter { it >= 0 }.take(numFat).forEach { s ->
                val off = secOff(s); for (i in 0 until sectorSize / 4) fatList += bb.getInt(off + 4 * i)
            }
            fat = fatList.toIntArray()
            miniFat = chain(bb.getInt(0x3C)).let { bytes ->
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); IntArray(bytes.size / 4) { b.getInt(it * 4) }
            }
            val dir = chain(bb.getInt(0x30))
            val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until dir.size / 128) {
                val o = i * 128
                val nameLen = db.getShort(o + 0x40).toInt()
                if (nameLen < 2) continue
                val name = String(dir, o, nameLen - 2, Charsets.UTF_16LE)
                val start = db.getInt(o + 0x74); val size = db.getInt(o + 0x78).toLong() and 0xffffffffL
                if (db.get(o + 0x42).toInt() == 5) miniStream = chain(start).copyOf(size.toInt())
                entries += Triple(name, start, size)
            }
        }

        private fun secOff(s: Int) = 512 + s * sectorSize

        private fun chain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(); var s = start; var guard = 0
            while (s >= 0 && guard++ < 2_000_000) {
                val off = secOff(s); if (off + sectorSize > d.size) break
                out.write(d, off, sectorSize)
                s = fat.getOrElse(s) { -2 }
            }
            return out.toByteArray()
        }

        fun stream(name: String): ByteArray? {
            val (_, start, size) = entries.firstOrNull { it.first == name } ?: return null
            if (size < cutoff) {
                val out = java.io.ByteArrayOutputStream(); var s = start; var guard = 0
                while (s >= 0 && guard++ < 1_000_000 && out.size() < size) {
                    val off = s * miniSize; if (off + miniSize > miniStream.size) break
                    out.write(miniStream, off, miniSize); s = miniFat.getOrElse(s) { -2 }
                }
                return out.toByteArray().copyOf(size.toInt())
            }
            return chain(start).copyOf(size.toInt())
        }
    }

    fun doc(bytes: ByteArray): String {
        val cfb = Cfb(bytes)
        val wd = cfb.stream("WordDocument") ?: error("Not a Word document")
        val w = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        require(w.getShort(0).toInt() and 0xffff == 0xA5EC) { "Unsupported Word format" }
        val flags = w.getShort(0x0A).toInt()
        require(flags and 0x0100 == 0) { "This document is encrypted" }
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: error("Word table stream missing")
        val fcClx = w.getInt(0x01A2); val lcbClx = w.getInt(0x01A6)
        val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        while (p < fcClx + lcbClx && table[p].toInt() == 1) p += 3 + (t.getShort(p + 1).toInt() and 0xffff)
        require(table[p].toInt() == 2) { "Unsupported Word piece table" }
        val lcb = t.getInt(p + 1); val plc = p + 5
        val n = (lcb - 4) / 12
        val cp1252 = Charset.forName("windows-1252")
        val sb = StringBuilder()
        for (i in 0 until n) {
            val cpStart = t.getInt(plc + 4 * i); val cpEnd = t.getInt(plc + 4 * (i + 1))
            val fc = t.getInt(plc + 4 * (n + 1) + 8 * i + 2)
            val len = cpEnd - cpStart
            if (len <= 0) continue
            if (fc and 0x40000000 != 0) {
                val off = (fc and 0x3FFFFFFF) / 2
                if (off + len <= wd.size) sb.append(String(wd, off, len, cp1252))
            } else if (fc + 2 * len <= wd.size) sb.append(String(wd, fc, 2 * len, Charsets.UTF_16LE))
        }
        return cleanWordControl(sb.toString())
    }

    /** Removes Word field codes and control characters. */
    fun cleanWordControl(raw: String): String {
        val out = StringBuilder(); val inCode = ArrayDeque<Boolean>()
        for (c in raw) {
            when (c) {
                '\u0013' -> { inCode.addLast(true) }
                '\u0014' -> { if (inCode.isNotEmpty()) { inCode.removeLast(); inCode.addLast(false) } }
                '\u0015' -> { if (inCode.isNotEmpty()) inCode.removeLast() }
                else -> if (inCode.lastOrNull() != true) out.append(
                    when (c) { '\r', '\u000B', '\u000C' -> '\n'; '\u0007' -> '\t'; else -> if (c < ' ' && c != '\n' && c != '\t') ' ' else c }
                )
            }
        }
        return out.toString().lines().joinToString("\n") { it.trim() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}
