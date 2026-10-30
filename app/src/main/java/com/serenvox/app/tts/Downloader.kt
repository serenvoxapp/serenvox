// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Plain-JVM resumable HTTPS downloader (no Android deps, so it is unit-testable).
 * - Follows redirects manually (GitHub release URLs 302 to a signed githubusercontent.com URL;
 *   each retry starts again from the original URL because the signed URL expires).
 * - Writes to `<dest>.part`; on a dropped connection it retries with `Range: bytes=<have>-`
 *   and appends if the server answers 206, restarting from zero on 200.
 * - A partial file left by a failed/cancelled attempt is resumed by the next call.
 * - Verifies the SHA-256 of the complete file before renaming it to [dest].
 */
class Downloader(
    private val maxAttempts: Int = 6,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val allowHttp: Boolean = false, // tests only
) {
    class HttpException(val code: Int) : IOException("Download failed (HTTP $code)")

    /**
     * Tries each URL in order (same file, same [sha256]); a partial file from one source is resumed from
     * the next. Returns the URL that worked. If all fail, throws the first source's error (a mirror 404
     * just means the mirror isn't populated yet) with the others attached as suppressed.
     */
    fun downloadAny(urls: List<String>, dest: File, sha256: String?, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val errors = mutableListOf<IOException>()
        for (u in urls) {
            try { download(u, dest, sha256, onProgress); return u } catch (e: IOException) { errors += e }
        }
        val first = errors.first()
        // Prefer a more useful error than "mirror 404" or a checksum failure from a stale mirror.
        errors.drop(1).forEach { first.addSuppressed(it) }
        throw first
    }

    /** [onProgress] gets (bytesDone, totalOrMinus1) and may throw (e.g. CancellationException) to abort. */
    fun download(url: String, dest: File, sha256: String?, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            try {
                fetchOnce(url, part, onProgress)
                break
            } catch (e: HttpException) {
                if (e.code == 416) part.delete() // range past end: our partial is bad
                else if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (++attempt >= maxAttempts) throw e
            } catch (e: java.net.UnknownHostException) {
                if (++attempt >= minOf(2, maxAttempts)) throw e // DNS failure: don't spin, try the mirror
            } catch (e: IOException) {
                if (++attempt >= maxAttempts) throw e
            }
            sleep(minOf(30_000L, 1_000L shl (attempt - 1)))
        }
        if (sha256 != null) {
            val got = sha256Of(part)
            if (!got.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw ChecksumException()
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        var u = URL(url)
        if (u.protocol != "https" && !allowHttp) throw IOException("Insecure URL")
        repeat(10) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "Serenvox")
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw HttpException(code)
                c.disconnect()
                u = URL(u, loc)
                if (u.protocol != "https" && !allowHttp) throw IOException("Insecure redirect")
            } else return c
        }
        throw IOException("Too many redirects")
    }

    private fun fetchOnce(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val c = open(url, have)
        try {
            val code = c.responseCode
            val append = when (code) {
                206 -> true
                200 -> false
                else -> throw HttpException(code)
            }
            val start = if (append) have else 0L
            val len = c.contentLengthLong
            val total = if (len >= 0) start + len else -1L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(start); out.seek(start)
                var done = start
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    onProgress(done, total)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        onProgress(done, total)
                    }
                }
                if (total >= 0 && done != total) throw IOException("Connection closed early ($done/$total)")
            }
        } finally { c.disconnect() }
    }

    class ChecksumException : IOException("Download corrupted (checksum mismatch)")

    companion object {
        private fun noSpace(e: Throwable) = generateSequence(e) { it.cause }.any {
            val m = it.message ?: ""; m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
        }

        /**
         * True for failures that should pause and resume later (no network, DNS, timeouts, reset
         * connections — e.g. Android cutting network when the app goes to the background), false for
         * hard errors that retrying won't fix (bad checksum, 4xx, no storage, broken archive).
         */
        fun isRetryable(e: Throwable): Boolean = when {
            e is ChecksumException -> false
            noSpace(e) -> false
            e is HttpException -> e.code >= 500 || e.code == 408 || e.code == 429 || e.suppressed.any { it is IOException && isRetryable(it) && it !is HttpException }
            e is IOException -> true
            else -> false
        }

        /** User-facing text for a download failure (the raw error is logged separately). */
        fun friendlyMessage(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList() + e.suppressed.toList()
            val raw = chain.joinToString(" ") { (it.message ?: "") }
            return when {
                chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException } ->
                    "Can't reach the download server. Check your connection, VPN or Private DNS, then tap Download to resume."
                raw.contains("ENOSPC") || raw.contains("No space left", ignoreCase = true) ->
                    "Not enough storage. Free up about 350 MB, then tap Download to resume."
                chain.any { it is java.net.SocketTimeoutException } ->
                    "The download timed out. Check your connection, then tap Download to resume."
                chain.any { it is javax.net.ssl.SSLException } ->
                    "Couldn't make a secure connection to the download server. Check the phone's date and time, or try another network (some public Wi-Fi and filters block it)."
                chain.any { it is ChecksumException } ->
                    "The downloaded file was damaged. Tap Download to try again."
                e is HttpException -> "The download server returned an error (HTTP ${e.code}). Please try again later."
                e is IOException -> "The download was interrupted. Tap Download to resume."
                else -> "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
