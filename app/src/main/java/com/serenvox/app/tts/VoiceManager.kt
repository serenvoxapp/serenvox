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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

sealed interface VoiceState {
    data object NotInstalled : VoiceState
    /** [waiting] = paused (no network / app in background / queued) and will resume by itself. */
    data class Downloading(val progress: Float, val waiting: Boolean = false, val doneBytes: Long = 0, val totalBytes: Long = 0) : VoiceState
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

    init {
        // Re-attach to background downloads (also after the app was reopened or the process restarted).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate).launch {
            androidx.work.WorkManager.getInstance(context).getWorkInfosByTagFlow(VoiceDownloadWorker.TAG_ALL).collect { infos ->
                for (info in infos) {
                    val v = VoiceCatalog.byId(VoiceDownloadWorker.idOf(info) ?: continue) ?: continue
                    val cur = _states.value[v.id]
                    when (info.state) {
                        androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED ->
                            if (!(cur is VoiceState.Downloading && cur.waiting)) set(v.id, VoiceState.Downloading(partialProgress(v), waiting = true))
                        androidx.work.WorkInfo.State.RUNNING ->
                            if (cur !is VoiceState.Downloading && cur != VoiceState.Installing) set(v.id, VoiceState.Downloading(partialProgress(v)))
                        androidx.work.WorkInfo.State.CANCELLED -> if (cur is VoiceState.Downloading) discardPartial(v)
                        androidx.work.WorkInfo.State.FAILED -> if (cur is VoiceState.Downloading || cur == VoiceState.Installing)
                            set(v.id, VoiceState.Failed(info.outputData.getString(VoiceDownloadWorker.KEY_ERROR) ?: "Download failed. Tap Download to try again."))
                        androidx.work.WorkInfo.State.SUCCEEDED -> if (installed(v)) set(v.id, VoiceState.Installed)
                    }
                }
            }
        }
    }

    fun isInstalled(id: String) = VoiceCatalog.byId(id)?.let { installed(it) } == true
    private fun set(id: String, s: VoiceState) { _states.value = _states.value + (id to s) }

    private val downloader = Downloader()
    private val kokoroLock = kotlinx.coroutines.sync.Mutex()

    /** Starts (or re-attaches to) the background download; returns when it finishes, fails or is cancelled. */
    suspend fun download(v: VoiceInfo) {
        VoiceDownloadWorker.enqueue(context, v.id)
        if (_states.value[v.id] !is VoiceState.Downloading) set(v.id, VoiceState.Downloading(partialProgress(v), waiting = true))
        _states.first { val s = it[v.id]; s is VoiceState.Installed || s is VoiceState.Failed || s is VoiceState.NotInstalled }
    }

    fun cancelDownload(v: VoiceInfo) = VoiceDownloadWorker.cancel(context, v.id)

    internal fun setState(id: String, s: VoiceState) = set(id, s)

    /** Temp files live in noBackupFilesDir so a stopped download resumes from its .part file. */
    private val tmpDir get() = File(context.noBackupFilesDir, "downloads").apply { mkdirs() }
    private fun tmpFor(v: VoiceInfo) = if (v.isKokoro) File(tmpDir, KokoroPack.ENGINE_FILE) else File(tmpDir, "${v.archiveName}.tar.bz2")
    private fun part(f: File) = File(f.path + ".part")

    /** Progress implied by a leftover .part file (used while queued/paused, e.g. after the app was reopened). */
    fun partialProgress(v: VoiceInfo): Float {
        if (v.isKokoro && engineMarker().exists()) return 0f
        val p = part(tmpFor(v)).length()
        val total = if (v.isKokoro) KokoroPack.ENGINE_BYTES else v.sizeMb * 1_000_000L
        return if (total > 0) (p.toFloat() / total).coerceIn(0f, .99f) else 0f
    }

    /** User cancelled: drop partial files (unless another voice is still using the shared engine download). */
    fun discardPartial(v: VoiceInfo) {
        if (!v.isKokoro) part(tmpFor(v)).delete()
        else {
            part(File(tmpDir, v.voiceFile)).delete()
            val othersActive = VoiceCatalog.natural.any { it.id != v.id && _states.value[it.id] is VoiceState.Downloading }
            if (!othersActive) part(tmpFor(v)).delete()
        }
        set(v.id, if (installed(v)) VoiceState.Installed else VoiceState.NotInstalled)
    }

    /** Runs the actual download+install (called by [VoiceDownloadWorker]); throws on failure. */
    internal suspend fun runDownload(v: VoiceInfo, progress: (Float, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (v.isKokoro) kokoroLock.withLock { downloadKokoro(v, progress) } else downloadPiper(v, progress)
    }

    private suspend fun fetch(urls: List<String>, dest: File, sha256: String?, from: Float, to: Float, progress: (Float, Long, Long) -> Unit) {
        val ctx = coroutineContext
        var lastPct = -1
        kotlinx.coroutines.runInterruptible {
            downloader.downloadAny(urls, dest, sha256) { done, total ->
                ctx.ensureActive()
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; progress(from + (to - from) * pct / 100f, done, total) }
                }
            }
        }
    }

    private suspend fun downloadPiper(v: VoiceInfo, progress: (Float, Long, Long) -> Unit) {
        val tmp = tmpFor(v)
        try {
            fetch(listOf(v.url), tmp, null, 0f, 1f, progress)
            set(v.id, VoiceState.Installing)
            dir(v).deleteRecursively()
            extract(tmp, root)
            check(dir(v).listFiles()?.any { it.name.endsWith(".onnx") } == true) { "Model file missing" }
            marker(v).writeText("ok")
            set(v.id, VoiceState.Installed)
        } catch (e: Exception) {
            dir(v).deleteRecursively(); throw e
        } finally { tmp.delete() }
    }

    /** Natural voice: engine once (if missing), then this speaker's style file written into voices.bin at its id. */
    private suspend fun downloadKokoro(v: VoiceInfo, progress: (Float, Long, Long) -> Unit) {
        if (installed(v)) { set(v.id, VoiceState.Installed); return }
        val tmp = tmpFor(v)
        val vtmp = File(tmpDir, v.voiceFile)
        try {
            val needEngine = !engineMarker().exists()
            if (needEngine) {
                fetch(voicePackUrls(KokoroPack.ENGINE_FILE), tmp, KokoroPack.ENGINE_SHA256, 0f, .99f, progress)
                set(v.id, VoiceState.Installing)
                synchronized(this) { releaseKokoro() }
                engineDir().deleteRecursively()
                extract(tmp, root)
                check(File(engineDir(), "model.onnx").exists()) { "Model file missing" }
                engineMarker().writeText("ok")
                _engine.value = true
                tmp.delete()
            }
            fetch(voicePackUrls(v.voiceFile), vtmp, KokoroPack.SHA256[v.voiceFile], if (needEngine) .99f else 0f, 1f, progress)
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
            throw e
        } finally { tmp.delete(); vtmp.delete() }
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
