// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class Status { IDLE, PREPARING, PLAYING, PAUSED }

/** Repeat behaviour when a sentence or the document finishes. */
enum class RepeatMode {
    OFF, ALL, ONE;

    /** Next mode in the UI cycle: Off -> Repeat document -> Repeat paragraph -> Off. */
    fun next(): RepeatMode = when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }

    /** Index to speak after [i] of [size] chunks, or null when playback should end. */
    fun nextIndex(i: Int, size: Int): Int? = when (this) {
        ONE -> i
        ALL -> if (i + 1 < size) i + 1 else 0
        OFF -> if (i + 1 < size) i + 1 else null
    }

    companion object {
        fun parse(v: String?): RepeatMode = entries.firstOrNull { it.name == v } ?: OFF
    }
}

data class PlayerState(
    val status: Status = Status.IDLE,
    val chunks: List<String> = emptyList(),
    val index: Int = 0,
    val engine: String = "",
    val error: String? = null,
) {
    val progress: Float get() = if (chunks.isEmpty()) 0f else (index.toFloat() / chunks.size)
}

/** A loaded neural voice. [generate] may block; it is never called concurrently by the player. */
interface Synth {
    val sampleRate: Int
    fun generate(text: String, speed: Float): FloatArray
}

/** A PCM float output. [write] must not block: it returns how many frames were accepted (0 when full). */
interface AudioSink {
    fun play()
    fun pause()
    fun write(buf: FloatArray, off: Int, len: Int): Int
    /** Frames actually played since creation. */
    val headFrames: Long
    /** Pause, flush queued audio, stop and free. Idempotent. */
    fun release()
}

interface NeuralEngine {
    fun isAvailable(voiceId: String): Boolean
    fun displayName(voiceId: String): String
    suspend fun load(voiceId: String, speaker: Int): Synth
    fun sink(sampleRate: Int, pitch: Float): AudioSink
}

interface SystemEngine {
    suspend fun ensure(): Boolean
    fun configure(speed: Float, pitch: Float, onStart: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit)
    fun speak(text: String, utteranceId: String, flush: Boolean)
    fun stop()
}

/**
 * Plays text sentence by sentence.
 *
 * Exactly one playback session exists at a time. Every user action (play, seek, skip, speed or
 * voice change, pause of system TTS, stop) bumps a generation token synchronously, then a single
 * command loop cancels and joins the previous session's producer/consumer, flushes and releases its
 * audio output, drops queued buffers, and only then starts the new session. Every state update and
 * engine callback carries its generation and is ignored when stale, so an old session can neither
 * be heard nor move the highlight.
 */
class SpeechPlayer internal constructor(
    private val neural: NeuralEngine,
    private val system: SystemEngine?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val synthDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val exportLoader: (suspend (String) -> com.k2fsa.sherpa.onnx.OfflineTts)? = null,
) {
    constructor(context: Context, voices: VoiceManager) :
        this(AndroidNeuralEngine(voices), AndroidSystemEngine(context), exportLoader = { voices.load(it) })

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val gen = AtomicLong()
    private sealed class Cmd { abstract val gen: Long
        data class Start(override val gen: Long, val chunks: List<String>, val args: Args, val from: Int) : Cmd()
        data class Halt(override val gen: Long) : Cmd()
    }
    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private var job: Job? = null
    @Volatile private var sink: AudioSink? = null
    @Volatile private var paused = false
    @Volatile private var systemActive = false

    data class Args(val voiceId: String, val speed: Float, val pitch: Float, val speaker: Int)
    @Volatile private var lastArgs: Args? = null

    @Volatile private var repeat = RepeatMode.OFF
    /** Set when the repeat mode changed while paused: resume restarts so the new mode applies. */
    @Volatile private var restartOnResume = false
    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    /** Changes the repeat mode; an active session restarts at the current sentence so queued audio follows it. */
    fun setRepeatMode(mode: RepeatMode) {
        if (repeat == mode) return
        repeat = mode; _repeatMode.value = mode
        val s = _state.value; val a = lastArgs ?: return
        when (s.status) {
            Status.PLAYING, Status.PREPARING -> start(s.chunks, a, s.index)
            Status.PAUSED -> restartOnResume = true
            Status.IDLE -> {}
        }
    }

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    /** Wall-clock millis when the sleep timer pauses playback, or null when off. */
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt
    private var sleepJob: Job? = null

    init {
        scope.launch {
            for (cmd in commands) {
                stopCurrent()
                if (cmd.gen != gen.get()) continue // superseded already: coalesce rapid seeks
                if (cmd is Cmd.Start) {
                    paused = false
                    job = scope.launch {
                        if (neural.isAvailable(cmd.args.voiceId)) runNeural(cmd.gen, cmd.chunks, cmd.args, cmd.from)
                        else runSystem(cmd.gen, cmd.chunks, cmd.args, cmd.from)
                    }
                }
            }
        }
    }

    /** Current generation, for tests. */
    internal val generation: Long get() = gen.get()

    fun play(text: String, voiceId: String, speed: Float, pitch: Float, speaker: Int, from: Int = 0) =
        start(Chunker.split(text), Args(voiceId, speed, pitch, speaker), from)

    internal fun playChunks(chunks: List<String>, args: Args, from: Int = 0) = start(chunks, args, from)

    private fun start(chunks: List<String>, args: Args, from: Int) {
        if (chunks.isEmpty()) return
        lastArgs = args
        restartOnResume = false
        val start = from.coerceIn(0, chunks.lastIndex)
        val g = gen.incrementAndGet()
        val name = if (neural.isAvailable(args.voiceId)) neural.displayName(args.voiceId) else "System voice"
        // Highlight moves to the requested sentence immediately and stays there.
        _state.value = PlayerState(Status.PREPARING, chunks, start, name)
        commands.trySend(Cmd.Start(g, chunks, args, start))
    }

    /** Jumps [delta] sentences from the current one. */
    fun skip(delta: Int) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.chunks.isEmpty() || s.status == Status.IDLE) return
        start(s.chunks, a, s.index + delta)
    }

    /** Starts speaking from sentence [index] of the current text. */
    fun seekTo(index: Int) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.chunks.isEmpty() || s.status == Status.IDLE) return
        start(s.chunks, a, index)
    }

    /** Applies new speed/pitch immediately by restarting at the current sentence. */
    fun updateParams(speed: Float, pitch: Float) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.status == Status.IDLE) return
        if (a.speed == speed && a.pitch == pitch) return
        start(s.chunks, a.copy(speed = speed, pitch = pitch), s.index)
    }

    /** Pauses playback after [minutes]; null cancels. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel(); sleepJob = null
        if (minutes == null) { _sleepEndsAt.value = null; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _sleepEndsAt.value = end
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause(); _sleepEndsAt.value = null
        }
    }

    fun pause() {
        val s = _state.value
        if (s.status != Status.PLAYING && s.status != Status.PREPARING) return
        if (systemActive) {
            // System TTS cannot pause: end this session; resume re-speaks from the current sentence.
            val g = gen.incrementAndGet()
            _state.value = s.copy(status = Status.PAUSED)
            commands.trySend(Cmd.Halt(g))
            return
        }
        paused = true
        sink?.pause()
        _state.update { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        val s = _state.value
        if (s.status != Status.PAUSED) return
        val a = lastArgs ?: return
        val k = sink
        if (k != null && !systemActive && job?.isActive == true && !restartOnResume) {
            paused = false; k.play()
            _state.update { it.copy(status = Status.PLAYING) }
        } else start(s.chunks, a, s.index)
    }

    fun stop() {
        val g = gen.incrementAndGet()
        _state.value = PlayerState()
        commands.trySend(Cmd.Halt(g))
    }

    /** Runs on the command loop only. */
    private suspend fun stopCurrent() {
        paused = false
        sink?.let { runCatching { it.pause() } }
        if (systemActive) runCatching { system?.stop() }
        job?.cancelAndJoin(); job = null
        sink?.release(); sink = null
        systemActive = false
    }

    private inline fun upd(g: Long, crossinline f: (PlayerState) -> PlayerState) =
        _state.update { if (gen.get() == g) f(it) else it }

    private suspend fun runNeural(g: Long, chunks: List<String>, a: Args, from: Int) = withContext(Dispatchers.Default) {
        var out: AudioSink? = null
        try {
            val synth = neural.load(a.voiceId, a.speaker)
            coroutineContext.ensureActive()
            val rate = synth.sampleRate
            val t = neural.sink(rate, a.pitch); out = t; sink = t
            if (!paused) t.play()
            val queue = Channel<Pair<Int, FloatArray>>(capacity = 2)
            val producer = launch(synthDispatcher) {
                var i: Int? = from
                while (i != null) {
                    ensureActive()
                    val samples = synth.generate(chunks[i], a.speed)
                    ensureActive()
                    queue.send(i to samples)
                    i = repeat.nextIndex(i, chunks.size)
                }
                queue.close()
            }
            // Sentence start positions, in frames; the highlight follows what is actually audible.
            val marks = ArrayList<Pair<Long, Int>>()
            var written = 0L
            val monitor = launch {
                while (true) {
                    val head = t.headFrames
                    val idx = synchronized(marks) { marks.lastOrNull { it.first <= head }?.second }
                    if (idx != null) upd(g) { it.copy(index = idx, status = if (paused) Status.PAUSED else Status.PLAYING) }
                    delay(25)
                }
            }
            suspend fun writeAll(buf: FloatArray) {
                var off = 0
                while (off < buf.size) {
                    coroutineContext.ensureActive()
                    if (paused) { delay(20); continue }
                    val w = t.write(buf, off, minOf(4096, buf.size - off))
                    if (w < 0) throw IllegalStateException("Audio output error $w")
                    if (w == 0) delay(5) else { off += w; written += w }
                }
            }
            val gap = FloatArray(rate / 8)
            for ((i, samples) in queue) {
                synchronized(marks) { marks.add(written to i) }
                writeAll(samples)
                writeAll(gap)
            }
            producer.join()
            while (t.headFrames < written) { coroutineContext.ensureActive(); delay(20) }
            monitor.cancel()
            upd(g) { it.copy(status = Status.IDLE, index = it.chunks.size) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w("SpeechPlayer", "playback failed", e) }
            upd(g) { it.copy(status = Status.IDLE, error = e.message) }
        } finally {
            out?.release()
        }
    }

    private suspend fun runSystem(g: Long, chunks: List<String>, a: Args, from: Int) {
        val tts = system
        if (tts == null || !tts.ensure()) {
            upd(g) { it.copy(status = Status.IDLE, error = "No system text-to-speech engine available") }
            return
        }
        coroutineContext.ensureActive()
        systemActive = true
        tts.configure(a.speed, a.pitch,
            onStart = { id -> UtteranceId.parse(id)?.let { (ug, i) -> if (ug == g) upd(g) { it.copy(status = Status.PLAYING, index = i) } } },
            onDone = { id -> UtteranceId.parse(id)?.let { (ug, i) ->
                if (ug != g || gen.get() != g) return@let
                when (repeat) {
                    // Queue is spoken one paragraph (ONE) or one pass (ALL/OFF) at a time; refill when it drains.
                    RepeatMode.ONE -> tts.speak(chunks[i], UtteranceId.of(g, i), flush = false)
                    RepeatMode.ALL -> if (i == chunks.lastIndex) for (j in chunks.indices) tts.speak(chunks[j], UtteranceId.of(g, j), flush = false)
                    RepeatMode.OFF -> if (i == chunks.lastIndex) upd(g) { it.copy(status = Status.IDLE, index = it.chunks.size) }
                }
            } },
            onError = { id -> UtteranceId.parse(id)?.let { (ug, _) -> if (ug == g) upd(g) { it.copy(status = Status.IDLE, error = "Speech error") } } },
        )
        if (repeat == RepeatMode.ONE) tts.speak(chunks[from], UtteranceId.of(g, from), flush = true)
        else for (i in from until chunks.size) tts.speak(chunks[i], UtteranceId.of(g, i), flush = i == from)
    }

    /** Renders text to a WAV file with the neural voice. Calls [onProgress] with 0..1. */
    suspend fun exportWav(text: String, voiceId: String, speed: Float, speaker: Int, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            val chunks = Chunker.split(text)
            require(chunks.isNotEmpty()) { "Nothing to export" }
            val tts = requireNotNull(exportLoader) { "Export unavailable" }(voiceId)
            val sid = VoiceCatalog.byId(voiceId)?.speakerId ?: speaker.coerceIn(0, (tts.numSpeakers() - 1).coerceAtLeast(0))
            WavWriter(out, tts.sampleRate()).use { w ->
                chunks.forEachIndexed { i, c ->
                    w.write(tts.generate(text = c, sid = sid, speed = speed).samples)
                    w.writeSilence(125)
                    onProgress((i + 1f) / chunks.size)
                }
            }
        }
}

/** System TTS utterance IDs carry the session generation so stale callbacks can be dropped. */
internal object UtteranceId {
    fun of(gen: Long, index: Int) = "$gen:$index"
    fun parse(id: String): Pair<Long, Int>? {
        val p = id.split(':'); if (p.size != 2) return null
        val g = p[0].toLongOrNull() ?: return null; val i = p[1].toIntOrNull() ?: return null
        return g to i
    }
}

private class AndroidNeuralEngine(private val voices: VoiceManager) : NeuralEngine {
    override fun isAvailable(voiceId: String) = voices.isInstalled(voiceId)
    override fun displayName(voiceId: String) = VoiceCatalog.byId(voiceId)?.name ?: voiceId
    override suspend fun load(voiceId: String, speaker: Int): Synth {
        val tts = voices.load(voiceId)
        val sid = VoiceCatalog.byId(voiceId)?.speakerId ?: speaker.coerceIn(0, (tts.numSpeakers() - 1).coerceAtLeast(0))
        return object : Synth {
            override val sampleRate = tts.sampleRate()
            override fun generate(text: String, speed: Float) = tts.generate(text = text, sid = sid, speed = speed).samples
        }
    }
    override fun sink(sampleRate: Int, pitch: Float): AudioSink {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (pitch != 1f) t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f)
        return object : AudioSink {
            @Volatile var released = false
            override fun play() { if (!released) t.play() }
            override fun pause() { if (!released) runCatching { t.pause() } }
            override fun write(buf: FloatArray, off: Int, len: Int) =
                if (released) -1 else t.write(buf, off, len, AudioTrack.WRITE_NON_BLOCKING)
            override val headFrames: Long get() = if (released) 0 else t.playbackHeadPosition.toLong() and 0xffffffffL
            @Synchronized override fun release() {
                if (released) return
                released = true
                runCatching { t.pause(); t.flush(); t.stop() }
                t.release()
            }
        }
    }
}

private class AndroidSystemEngine(private val context: Context) : SystemEngine {
    private var tts: TextToSpeech? = null
    private var ready = false
    override suspend fun ensure(): Boolean {
        if (tts != null && ready) return true
        val done = Channel<Boolean>(1)
        withContext(Dispatchers.Main) { tts = TextToSpeech(context) { s -> done.trySend(s == TextToSpeech.SUCCESS) } }
        ready = done.receive()
        if (ready) tts?.language = Locale.getDefault()
        return ready
    }
    override fun configure(speed: Float, pitch: Float, onStart: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val t = tts ?: return
        t.setSpeechRate(speed); t.setPitch(pitch)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = onStart(id)
            override fun onDone(id: String) = onDone(id)
            @Deprecated("Deprecated in Java") override fun onError(id: String) = onError(id)
        })
    }
    override fun speak(text: String, utteranceId: String, flush: Boolean) {
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
    }
    override fun stop() { tts?.stop() }
}
