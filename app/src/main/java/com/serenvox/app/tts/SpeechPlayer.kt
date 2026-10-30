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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Status { IDLE, PREPARING, PLAYING, PAUSED }

data class PlayerState(
    val status: Status = Status.IDLE,
    val chunks: List<String> = emptyList(),
    val index: Int = 0,
    val engine: String = "",
    val error: String? = null,
) {
    val progress: Float get() = if (chunks.isEmpty()) 0f else (index.toFloat() / chunks.size)
}

/**
 * Plays text sentence by sentence. Uses an on-device neural voice (sherpa-onnx) when one is
 * installed and selected, otherwise Android's system TextToSpeech.
 * Synthesis of the next sentence runs ahead of playback so audio stays gapless.
 */
class SpeechPlayer(private val context: Context, private val voices: VoiceManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var job: Job? = null
    @Volatile private var paused = false
    private var track: AudioTrack? = null

    // System TTS fallback
    private var systemTts: TextToSpeech? = null
    private var systemReady = false
    private var systemParams: Triple<Float, Float, Int>? = null

    private data class Args(val voiceId: String, val speed: Float, val pitch: Float, val speaker: Int)
    private var lastArgs: Args? = null

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    /** Wall-clock millis when the sleep timer pauses playback, or null when off. */
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt
    private var sleepJob: Job? = null

    fun play(text: String, voiceId: String, speed: Float, pitch: Float, speaker: Int, from: Int = 0) =
        playChunks(Chunker.split(text), voiceId, speed, pitch, speaker, from)

    private fun playChunks(chunks: List<String>, voiceId: String, speed: Float, pitch: Float, speaker: Int, from: Int) {
        if (chunks.isEmpty()) return
        lastArgs = Args(voiceId, speed, pitch, speaker)
        val start = from.coerceIn(0, chunks.lastIndex)
        scope.launch {
            stopInternal()
            paused = false
            if (voices.isInstalled(voiceId)) playNeural(chunks, voiceId, speed, pitch, speaker, start)
            else playSystem(chunks, speed, pitch, start)
        }
    }

    /** Jumps [delta] sentences from the current one, keeping playing/paused state. */
    fun skip(delta: Int) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.chunks.isEmpty() || s.status == Status.IDLE) return
        playChunks(s.chunks, a.voiceId, a.speed, a.pitch, a.speaker, s.index + delta)
    }

    /** Starts speaking from sentence [index] of the current text. */
    fun seekTo(index: Int) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.chunks.isEmpty() || s.status == Status.IDLE) return
        playChunks(s.chunks, a.voiceId, a.speed, a.pitch, a.speaker, index)
    }

    /** Applies new speed/pitch immediately by restarting at the current sentence. */
    fun updateParams(speed: Float, pitch: Float) {
        val s = _state.value; val a = lastArgs ?: return
        if (s.status == Status.IDLE) return
        if (a.speed == speed && a.pitch == pitch) return
        playChunks(s.chunks, a.voiceId, speed, pitch, a.speaker, s.index)
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

    private fun playNeural(chunks: List<String>, voiceId: String, speed: Float, pitch: Float, speaker: Int, from: Int) {
        _state.value = PlayerState(Status.PREPARING, chunks, from, VoiceCatalog.byId(voiceId)?.name ?: voiceId)
        job = scope.launch {
            try {
                val tts = voices.load(voiceId)
                val sid = speaker.coerceIn(0, (tts.numSpeakers() - 1).coerceAtLeast(0))
                val rate = tts.sampleRate()
                val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                if (pitch != 1f) t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f)
                t.play()

                // Producer: synthesise up to 2 sentences ahead.
                val queue = Channel<Pair<Int, FloatArray>>(capacity = 2)
                val producer = launch(Dispatchers.Default) {
                    for (i in from until chunks.size) {
                        val c = chunks[i]
                        val audio = tts.generate(text = c, sid = sid, speed = speed)
                        queue.send(i to audio.samples)
                    }
                    queue.close()
                }
                var written = 0L
                for ((i, samples) in queue) {
                    _state.update { it.copy(status = if (paused) Status.PAUSED else Status.PLAYING, index = i) }
                    var off = 0
                    while (off < samples.size) {
                        if (!isActive) return@launch
                        if (paused) { delay(50); continue }
                        val n = minOf(2048, samples.size - off)
                        val w = t.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) break
                        off += w; written += w
                    }
                    // short natural pause between sentences
                    val gap = FloatArray(rate / 8); t.write(gap, 0, gap.size, AudioTrack.WRITE_BLOCKING); written += gap.size
                }
                producer.join()
                // Let the buffer drain.
                while (isActive && t.playbackHeadPosition < written) delay(50)
                finish()
            } catch (e: Exception) {
                if (isActive) _state.update { it.copy(status = Status.IDLE, error = e.message) }
                releaseTrack()
            }
        }
    }

    private suspend fun playSystem(chunks: List<String>, speed: Float, pitch: Float, from: Int) {
        _state.value = PlayerState(Status.PREPARING, chunks, from, "System voice")
        systemParams = Triple(speed, pitch, from)
        val tts = ensureSystemTts() ?: run {
            _state.update { it.copy(status = Status.IDLE, error = "No system text-to-speech engine available") }
            return
        }
        tts.setSpeechRate(speed); tts.setPitch(pitch)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { _state.update { it.copy(status = Status.PLAYING, index = id.toInt()) } }
            override fun onDone(id: String) { if (id.toInt() == chunks.lastIndex) finish() }
            @Deprecated("Deprecated in Java") override fun onError(id: String) { _state.update { it.copy(status = Status.IDLE, error = "Speech error") } }
        })
        for (i in from until chunks.size) {
            tts.speak(chunks[i], if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, i.toString())
        }
    }

    private suspend fun ensureSystemTts(): TextToSpeech? {
        systemTts?.let { if (systemReady) return it }
        val done = Channel<Boolean>(1)
        withContext(Dispatchers.Main) {
            systemTts = TextToSpeech(context) { status -> done.trySend(status == TextToSpeech.SUCCESS) }
        }
        systemReady = done.receive()
        if (systemReady) systemTts?.language = Locale.getDefault()
        return if (systemReady) systemTts else null
    }

    fun pause() {
        val s = _state.value
        if (s.status != Status.PLAYING && s.status != Status.PREPARING) return
        paused = true
        if (track != null) track?.pause() else systemTts?.stop()
        _state.update { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        val s = _state.value
        if (s.status != Status.PAUSED) return
        paused = false
        val t = track
        if (t != null) { t.play(); _state.update { it.copy(status = Status.PLAYING) } }
        else {
            val (speed, pitch, _) = systemParams ?: return
            scope.launch { playSystem(s.chunks, speed, pitch, s.index) }
        }
    }

    fun stop() { scope.launch { stopInternal(); _state.value = PlayerState() } }

    private suspend fun stopInternal() {
        paused = false
        track?.let { runCatching { it.pause(); it.flush() } }
        job?.cancelAndJoin(); job = null
        releaseTrack()
        systemTts?.stop()
    }

    private fun releaseTrack() { track?.let { runCatching { it.stop() }; it.release() }; track = null }

    private fun finish() {
        releaseTrack()
        _state.update { it.copy(status = Status.IDLE, index = it.chunks.size) }
    }

    /** Renders text to a WAV file with the neural voice. Calls [onProgress] with 0..1. */
    suspend fun exportWav(text: String, voiceId: String, speed: Float, speaker: Int, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            val chunks = Chunker.split(text)
            require(chunks.isNotEmpty()) { "Nothing to export" }
            val tts = voices.load(voiceId)
            val sid = speaker.coerceIn(0, (tts.numSpeakers() - 1).coerceAtLeast(0))
            WavWriter(out, tts.sampleRate()).use { w ->
                chunks.forEachIndexed { i, c ->
                    w.write(tts.generate(text = c, sid = sid, speed = speed).samples)
                    w.writeSilence(125)
                    onProgress((i + 1f) / chunks.size)
                }
            }
        }
}
