package com.serenvox.app.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
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
    private fun set(id: String, s: VoiceState) { _states.value = _states.value + (id to s) }

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
        if (loadedId == v.id) { loaded?.release(); loaded = null; loadedId = null }
        dir(v).deleteRecursively()
        set(v.id, VoiceState.NotInstalled)
    }

    /** Returns a ready engine for the voice (cached; only one model kept in memory). */
    @Synchronized
    fun load(id: String): OfflineTts {
        if (loadedId == id) loaded?.let { return it }
        loaded?.release(); loaded = null; loadedId = null
        val v = requireNotNull(VoiceCatalog.byId(id)) { "Unknown voice" }
        val d = dir(v)
        val model = d.listFiles()!!.first { it.name.endsWith(".onnx") }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val tts = OfflineTts(assetManager = null, config = cfg)
        loaded = tts; loadedId = id
        return tts
    }
}
