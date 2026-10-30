// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface VoiceState {
    data object NotInstalled : VoiceState
    data class Downloading(val progress: Float) : VoiceState
    data object Installing : VoiceState
    data object Installed : VoiceState
    data class Failed(val message: String) : VoiceState
}

/** Downloads, installs, deletes and loads on-device neural voices. */
class VoiceManager(private val context: Context) {
    private val root = File(context.filesDir, "voices").apply { mkdirs() }
    private val _states = MutableStateFlow(VoiceCatalog.voices.associate { it.id to initialState(it) })
    val states: StateFlow<Map<String, VoiceState>> = _states

    private var loadedId: String? = null
    private var loaded: OfflineTts? = null

    private fun dir(v: VoiceInfo) = File(root, v.archiveName)
    private fun engineDir() = File(root, KOKORO_PACK)
    private fun engineMarker() = File(engineDir(), ".installed")
    /** Kokoro: per-speaker marker inside the engine dir; Piper: marker in its own pack. */
    private fun marker(v: VoiceInfo) = if (v.isKokoro) File(engineDir(), "speakers/${v.id}") else File(dir(v), ".installed")
    private fun initialState(v: VoiceInfo): VoiceState =
        if (installed(v)) VoiceState.Installed else VoiceState.NotInstalled
    private fun installed(v: VoiceInfo) = marker(v).exists() && (!v.isKokoro || engineMarker().exists())

    private val _engine = MutableStateFlow(engineMarker().exists())
    /** True once the shared natural-voice engine is on the device (later natural voices are ~0.5 MB). */
    val engineInstalled: StateFlow<Boolean> = _engine

    fun isInstalled(id: String) = VoiceCatalog.byId(id)?.let { installed(it) } == true
    private fun set(id: String, s: VoiceState) { _states.value = _states.value + (id to s) }

    /** Downloads [url] to [dest] reporting progress in [from]..[to]; verifies SHA-256 when given. */
    private suspend fun fetch(url: String, dest: File, sha256: String?, id: String, from: Float, to: Float) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
        }
        check(conn.responseCode in 200..299) { "Download failed (HTTP ${conn.responseCode})" }
        val total = conn.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; set(id, VoiceState.Downloading(from + (to - from) * pct / 100f)) }
                    }
                }
            }
        }
        if (sha256 != null) check(md.digest().toHex() == sha256) { "Download corrupted (checksum mismatch)" }
    }

    suspend fun download(v: VoiceInfo) = withContext(Dispatchers.IO) {
        if (v.isKokoro) downloadKokoro(v) else downloadPiper(v)
    }

    private suspend fun downloadPiper(v: VoiceInfo) {
        val tmp = File(context.cacheDir, "${v.archiveName}.tar.bz2")
        try {
            set(v.id, VoiceState.Downloading(0f))
            fetch(v.url, tmp, null, v.id, 0f, 1f)
            set(v.id, VoiceState.Installing)
            dir(v).deleteRecursively()
            extract(tmp, root)
            check(dir(v).listFiles()?.any { it.name.endsWith(".onnx") } == true) { "Model file missing" }
            marker(v).writeText("ok")
            set(v.id, VoiceState.Installed)
        } catch (e: Exception) {
            dir(v).deleteRecursively()
            set(v.id, VoiceState.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            tmp.delete()
        }
    }

    /** Natural voice: engine once (if missing), then this speaker's style file written into voices.bin at its id. */
    private suspend fun downloadKokoro(v: VoiceInfo) {
        val tmp = File(context.cacheDir, KokoroPack.ENGINE_FILE)
        val vtmp = File(context.cacheDir, v.voiceFile)
        try {
            set(v.id, VoiceState.Downloading(0f))
            val needEngine = !engineMarker().exists()
            if (needEngine) {
                fetch(KokoroPack.ENGINE_URL, tmp, KokoroPack.ENGINE_SHA256, v.id, 0f, .99f)
                set(v.id, VoiceState.Installing)
                synchronized(this) { releaseKokoro() }
                engineDir().deleteRecursively()
                extract(tmp, root)
                check(File(engineDir(), "model.onnx").exists()) { "Model file missing" }
                engineMarker().writeText("ok")
                _engine.value = true
                tmp.delete()
            }
            fetch(v.url, vtmp, KokoroPack.SHA256[v.voiceFile], v.id, if (needEngine) .99f else 0f, 1f)
            check(vtmp.length() == KokoroPack.SPEAKER_BYTES.toLong()) { "Voice file has the wrong size" }
            set(v.id, VoiceState.Installing)
            synchronized(this) {
                releaseKokoro()
                writeSpeaker(requireNotNull(v.speakerId), vtmp.readBytes())
                marker(v).apply { parentFile?.mkdirs() }.writeText("ok")
            }
            set(v.id, VoiceState.Installed)
        } catch (e: Exception) {
            if (!engineMarker().exists()) engineDir().deleteRecursively()
            set(v.id, VoiceState.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            tmp.delete(); vtmp.delete()
        }
    }

    /** voices.bin keeps sherpa's full 54-speaker layout; speakers not downloaded stay zero-filled. */
    private fun writeSpeaker(sid: Int, data: ByteArray?) {
        RandomAccessFile(File(engineDir(), "voices.bin"), "rw").use { f ->
            val size = KokoroPack.SPEAKER_BYTES.toLong() * KokoroPack.N_SPEAKERS
            if (f.length() != size) f.setLength(size)
            f.seek(sid.toLong() * KokoroPack.SPEAKER_BYTES)
            f.write(data ?: ByteArray(KokoroPack.SPEAKER_BYTES))
        }
    }

    private fun releaseKokoro() {
        if (loadedId?.startsWith("$KOKORO_PACK|") == true) { loaded?.release(); loaded = null; loadedId = null }
    }

    private fun extract(archive: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(dest, e.name)
                require(out.canonicalPath.startsWith(destPath)) { "Bad archive entry" } // zip-slip guard
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    /** Deleting the last natural voice also removes the shared engine. */
    @Synchronized
    fun delete(v: VoiceInfo) {
        if (v.isKokoro) {
            releaseKokoro()
            marker(v).delete()
            if (VoiceCatalog.natural.none { marker(it).exists() }) {
                engineDir().deleteRecursively(); _engine.value = false
            } else writeSpeaker(requireNotNull(v.speakerId), null)
        } else {
            if (loadedId == v.id) { loaded?.release(); loaded = null; loadedId = null }
            dir(v).deleteRecursively()
        }
        set(v.id, VoiceState.NotInstalled)
    }

    /** Returns a ready engine for the voice (cached; only one model kept in memory). */
    @Synchronized
    fun load(id: String): OfflineTts {
        val v = requireNotNull(VoiceCatalog.byId(id)) { "Unknown voice" }
        // Kokoro speakers share one model; only the accent's lexicon differs.
        val key = if (v.isKokoro) "${v.archiveName}|${v.locale}" else v.id
        if (loadedId == key) loaded?.let { return it }
        loaded?.release(); loaded = null; loadedId = null
        val d = dir(v)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val model = if (v.isKokoro) OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(d, "model.onnx").absolutePath,
                    voices = File(d, "voices.bin").absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                    lexicon = File(d, if (v.locale == "en-GB") "lexicon-gb-en.txt" else "lexicon-us-en.txt").absolutePath,
                    lang = if (v.locale == "en-GB") "en" else "en-us",
                ),
                numThreads = threads, debug = false, provider = "cpu",
            ) else OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = d.listFiles()!!.first { it.name.endsWith(".onnx") }.absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                debug = false,
                provider = "cpu",
            )
        val cfg = OfflineTtsConfig(model = model, maxNumSentences = 1)
        val tts = OfflineTts(assetManager = null, config = cfg)
        loaded = tts; loadedId = key
        return tts
    }
}

internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
