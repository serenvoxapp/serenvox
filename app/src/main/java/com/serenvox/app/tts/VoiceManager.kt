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
    private fun marker(v: VoiceInfo) = File(dir(v), ".installed")
    private fun initialState(v: VoiceInfo): VoiceState =
        if (marker(v).exists()) VoiceState.Installed else VoiceState.NotInstalled

    fun isInstalled(id: String) = VoiceCatalog.byId(id)?.let { marker(it).exists() } == true
    /** Voices sharing a model pack (all Kokoro speakers) share one install state. */
    private fun set(id: String, s: VoiceState) {
        val pack = VoiceCatalog.byId(id)?.archiveName
        val ids = VoiceCatalog.voices.filter { it.archiveName == pack }.map { it.id }.ifEmpty { listOf(id) }
        _states.value = _states.value + ids.associateWith { s }
    }

    suspend fun download(v: VoiceInfo) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "${v.archiveName}.tar.bz2")
        try {
            set(v.id, VoiceState.Downloading(0f))
            val conn = (URL(v.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; set(v.id, VoiceState.Downloading(pct / 100f)) }
                        }
                    }
                }
            }
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

    @Synchronized
    fun delete(v: VoiceInfo) {
        if (loadedId == v.id || loadedId?.startsWith(v.archiveName + "|") == true) { loaded?.release(); loaded = null; loadedId = null }
        dir(v).deleteRecursively()
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
