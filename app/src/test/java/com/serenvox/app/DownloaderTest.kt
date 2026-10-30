// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import com.serenvox.app.tts.Downloader
import com.serenvox.app.tts.KokoroPack
import com.serenvox.app.tts.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class DownloaderTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val noSleep: (Long) -> Unit = {}

    /** Minimal HTTP/1.1 server on a raw socket; [handle] gets (path, rangeStart) and writes the response. */
    private fun server(handle: (String, Long?, java.io.OutputStream) -> Unit): java.net.ServerSocket {
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    s.use {
                        val r = it.getInputStream().bufferedReader()
                        val path = r.readLine().split(" ")[1]
                        var range: Long? = null
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break
                            if (l.lowercase().startsWith("range:")) range = l.substringAfter("=").substringBefore("-").toLong() }
                        handle(path, range, it.getOutputStream()); it.getOutputStream().flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return ss
    }

    /** Local server: 302 redirect, first two transfers drop mid-way, then Range requests resume. */
    @Test fun redirectsResumesAndVerifies() {
        val data = ByteArray(3_000_000) { (it * 31 % 251).toByte() }
        val hits = AtomicInteger(); val ranges = java.util.Collections.synchronizedList(mutableListOf<Long?>())
        val ss = server { path, from, out ->
            if (path == "/start") { out.write("HTTP/1.1 302 Found\r\nLocation: /blob\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@server }
            val n = hits.incrementAndGet(); ranges += from
            val f = (from ?: 0L).toInt()
            val head = if (f > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $f-${data.size - 1}/${data.size}\r\n" else "HTTP/1.1 200 OK\r\n"
            out.write((head + "Content-Length: ${data.size - f}\r\nConnection: close\r\n\r\n").toByteArray())
            val upto = if (n <= 2) f + 700_000 else data.size
            out.write(data, f, upto - f)
        }
        try {
            val dir = Files.createTempDirectory("dl").toFile(); val dest = File(dir, "x.bin")
            Downloader(maxAttempts = 5, sleep = noSleep, allowHttp = true)
                .download("http://127.0.0.1:${ss.localPort}/start", dest, sha(data))
            assertTrue(dest.readBytes().contentEquals(data))
            assertEquals(3, hits.get())
            assertEquals(listOf(null, 700_000L, 1_400_000L), ranges.toList())
            assertTrue(!File(dest.path + ".part").exists())
        } finally { ss.close() }
    }

    @Test fun checksumMismatchDeletesPartial() {
        val ss = server { _, _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".toByteArray()); out.write(byteArrayOf(1, 2, 3, 4)) }
        try {
            val dest = File(Files.createTempDirectory("dl").toFile(), "y")
            val e = runCatching { Downloader(sleep = noSleep, allowHttp = true).download("http://127.0.0.1:${ss.localPort}/b", dest, "00") }.exceptionOrNull()
            assertTrue(e?.message?.contains("checksum") == true)
            assertTrue(!dest.exists() && !File(dest.path + ".part").exists())
        } finally { ss.close() }
    }

    /** Primary (GitHub) is tried first; on failure the mirror serves the same verified file. */
    @Test fun fallsBackToMirrorInOrder() {
        val data = ByteArray(1000) { it.toByte() }
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ss = server { path, _, out ->
            order += path
            if (path.startsWith("/github")) out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            else { out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(data) }
        }
        try {
            val dest = File(Files.createTempDirectory("dl").toFile(), "m")
            val base = "http://127.0.0.1:${ss.localPort}"
            val used = Downloader(maxAttempts = 2, sleep = noSleep, allowHttp = true)
                .downloadAny(listOf("$base/github/f", "$base/mirror/f"), dest, sha(data))
            assertEquals("$base/mirror/f", used)
            assertEquals("/github/f", order.first())
            assertEquals("/mirror/f", order.last())
            assertTrue(dest.readBytes().contentEquals(data))
        } finally { ss.close() }
    }

    /** Mirror 404 (not uploaded yet) must not hide the primary's real error. */
    @Test fun mirror404SurfacesPrimaryError() {
        val ss = server { path, _, out ->
            val code = if (path.startsWith("/github")) "500 Server Error" else "404 Not Found"
            out.write("HTTP/1.1 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        try {
            val dest = File(Files.createTempDirectory("dl").toFile(), "n")
            val base = "http://127.0.0.1:${ss.localPort}"
            val e = runCatching { Downloader(maxAttempts = 1, sleep = noSleep, allowHttp = true)
                .downloadAny(listOf("$base/github/f", "$base/mirror/f"), dest, "00") }.exceptionOrNull()
            assertEquals(500, (e as Downloader.HttpException).code)
            assertEquals(404, (e.suppressed.single() as Downloader.HttpException).code)
        } finally { ss.close() }
    }

    @Test fun mirrorUrlsOrder() {
        val u = com.serenvox.app.tts.voicePackUrls("kokoro-voice-heart.bin")
        assertEquals(listOf(com.serenvox.app.tts.VOICE_PACK_BASE_URL + "kokoro-voice-heart.bin",
            "https://serenvox.com/voice-packs/v1/kokoro-voice-heart.bin"), u)
    }

    @Test fun errorClassification() {
        val uh = java.net.UnknownHostException("github.com")
        assertTrue(Downloader.isRetryable(uh))
        assertTrue(Downloader.friendlyMessage(uh).startsWith("Can't reach the download server"))
        assertTrue(Downloader.isRetryable(java.net.SocketException("Software caused connection abort")))
        assertTrue(Downloader.isRetryable(java.net.SocketTimeoutException()))
        assertTrue(!Downloader.isRetryable(Downloader.ChecksumException()))
        assertTrue(!Downloader.isRetryable(Downloader.HttpException(404)))
        assertTrue(Downloader.isRetryable(Downloader.HttpException(503)))
        assertTrue(Downloader.friendlyMessage(IOException("ENOSPC (No space left on device)")).startsWith("Not enough storage"))
        assertTrue(Downloader.friendlyMessage(javax.net.ssl.SSLHandshakeException("x")).isNotBlank())
    }

    /** Real GitHub release URLs. Run with SERENVOX_NET_TESTS=1 (engine too with =full). */
    @Test fun realReleaseUrls() {
        val mode = System.getenv("SERENVOX_NET_TESTS")
        assumeTrue(mode != null)
        val dir = Files.createTempDirectory("real").toFile()
        for (v in VoiceCatalog.voices.filter { it.isKokoro }) {
            val f = File(dir, v.voiceFile)
            Downloader().download(v.url, f, KokoroPack.SHA256[v.voiceFile])
            assertEquals(KokoroPack.SPEAKER_BYTES.toLong(), f.length())
        }
        if (mode == "full") {
            val f = File(dir, KokoroPack.ENGINE_FILE)
            // Simulate an interrupted download: seed the .part with the first 5 MB, then resume.
            runCatching { Downloader().download(KokoroPack.ENGINE_URL, File(dir, "seed"), null) { done, _ -> check(done <= 5_000_000) { "stop" } } }
            File(dir, "seed.part").takeIf { it.exists() }?.renameTo(File(f.path + ".part"))
            assertTrue(File(f.path + ".part").length() > 5_000_000)
            Downloader().download(KokoroPack.ENGINE_URL, f, KokoroPack.ENGINE_SHA256)
            assertEquals(KokoroPack.ENGINE_BYTES, f.length())
        }
        dir.deleteRecursively()
    }
}
