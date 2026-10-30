// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the playback session with a fake synthesiser and a fake real-time audio sink.
 * Each sample encodes (voice, sentence) so the test knows exactly what is audible.
 */
class SpeechPlayerSessionTest {
    private val rate = 8000
    private val sentenceFrames = 1200 // 150 ms per sentence
    private val chunks = List(40) { "Sentence $it." }

    private val activeSinks = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val concurrentSynth = AtomicInteger()
    private val maxSynth = AtomicInteger()
    /** Everything audible, in order: voice*1000 + sentence. Only non-silent samples. */
    private val heard = Collections.synchronizedList(ArrayList<Int>())
    @Volatile private var current: FakeSink? = null

    inner class FakeSink : AudioSink {
        private val data = ArrayList<Float>()
        private var playing = false; private var released = false
        private var playedBefore = 0L; private var since = 0L
        private val capacity = 400
        @Synchronized private fun head(): Long {
            val h = if (playing) playedBefore + (System.nanoTime() - since) * rate / 1_000_000_000L else playedBefore
            return minOf(h, data.size.toLong())
        }
        override val headFrames: Long get() = head()
        @Synchronized override fun play() {
            check(!released)
            if (playing) return
            playing = true; since = System.nanoTime()
            val n = activeSinks.incrementAndGet(); maxActive.accumulateAndGet(n, ::maxOf)
            current = this
        }
        @Synchronized override fun pause() {
            if (!playing) return
            playedBefore = head(); playing = false; activeSinks.decrementAndGet()
        }
        @Synchronized override fun write(buf: FloatArray, off: Int, len: Int): Int {
            if (released) return -1
            val free = (capacity - (data.size - head())).toInt().coerceAtLeast(0)
            val n = minOf(free, len)
            for (k in 0 until n) data.add(buf[off + k])
            return n
        }
        /** Voice*1000+sentence currently audible, or null in a gap. */
        @Synchronized fun audible(): Int? {
            if (!playing) return null
            val h = head().toInt(); if (h <= 0 || h > data.size) return null
            val v = data[h - 1]; return if (v == 0f) null else v.toInt()
        }
        @Synchronized override fun release() {
            if (released) return
            pause(); released = true
        }
    }

    private val engine = object : NeuralEngine {
        override fun isAvailable(voiceId: String) = true
        override fun displayName(voiceId: String) = voiceId
        override suspend fun load(voiceId: String, speaker: Int): Synth {
            val voice = voiceId.removePrefix("v").toInt()
            return object : Synth {
                override val sampleRate = rate
                override fun generate(text: String, speed: Float): FloatArray {
                    val n = concurrentSynth.incrementAndGet(); maxSynth.accumulateAndGet(n, ::maxOf)
                    try {
                        Thread.sleep(40) // blocking, non-cancellable, like the native call
                        val i = text.removePrefix("Sentence ").removeSuffix(".").toInt()
                        return FloatArray(sentenceFrames) { (voice * 1000 + i).toFloat() }
                    } finally { concurrentSynth.decrementAndGet() }
                }
            }
        }
        override fun sink(sampleRate: Int, pitch: Float): AudioSink = FakeSink()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = SpeechPlayer(engine, null, scope)

    @After fun tearDown() { player.stop(); Thread.sleep(100); scope.cancel() }

    private fun args(v: Int) = SpeechPlayer.Args("v$v", 1f, 1f, 0)

    /** Samples for [ms]; returns (ok, total) where ok means highlight == audible sentence. */
    private fun watch(ms: Long, voice: Int, allowed: (Int) -> Boolean = { true }): Pair<Int, Int> {
        var ok = 0; var total = 0
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val a = current?.audible()
            if (a != null) {
                assertEquals("wrong voice audible", voice, a / 1000)
                assertTrue("unexpected sentence ${a % 1000}", allowed(a % 1000))
                total++
                if (player.state.value.index == a % 1000) ok++
            }
            assertTrue("more than one output playing", activeSinks.get() <= 1)
            Thread.sleep(5)
        }
        return ok to total
    }

    private fun assertInvariants() {
        assertTrue("two outputs played at once (max=${maxActive.get()})", maxActive.get() <= 1)
        assertTrue("synth ran concurrently (max=${maxSynth.get()})", maxSynth.get() <= 1)
    }

    @Test fun rapidRepeatedSeeksLandOnLastTarget() {
        player.playChunks(chunks, args(1), 0)
        Thread.sleep(250)
        for (t in listOf(20, 3, 15, 7, 30, 2, 25, 9)) { player.seekTo(t); Thread.sleep(7) }
        assertEquals(9, player.state.value.index) // highlight stays on the tapped sentence
        Thread.sleep(120)
        val (ok, total) = watch(400, 1) { it in 9..12 }
        assertTrue("nothing played", total > 20)
        assertTrue("highlight out of sync $ok/$total", ok >= total * 85 / 100)
        assertInvariants()
    }

    @Test fun seekBackwardDuringPrefetch() {
        player.playChunks(chunks, args(1), 10)
        Thread.sleep(120) // producer is prefetching 11, 12
        player.seekTo(2)
        assertEquals(2, player.state.value.index)
        Thread.sleep(100)
        assertEquals(2, player.state.value.index) // no jump back to the old line
        val (ok, total) = watch(350, 1) { it in 2..5 }
        assertTrue(total > 20); assertTrue("highlight out of sync $ok/$total", ok >= total * 85 / 100)
        assertInvariants()
    }

    @Test fun seekForwardDuringPrefetch() {
        player.playChunks(chunks, args(1), 0)
        Thread.sleep(120)
        player.seekTo(30)
        assertEquals(30, player.state.value.index)
        Thread.sleep(100)
        val (ok, total) = watch(350, 1) { it in 30..33 }
        assertTrue(total > 20); assertTrue("highlight out of sync $ok/$total", ok >= total * 85 / 100)
        assertInvariants()
    }

    @Test fun voiceChangeMidPlayStopsOldVoice() {
        player.playChunks(chunks, args(1), 0)
        Thread.sleep(250)
        val at = player.state.value.index
        player.playChunks(chunks, args(2), at) // what the UI does on voice change + play
        Thread.sleep(100)
        val (ok, total) = watch(400, 2)
        assertTrue(total > 20); assertTrue("highlight out of sync $ok/$total", ok >= total * 85 / 100)
        assertInvariants()
    }

    @Test fun stopSilencesAndIgnoresLateUpdates() {
        player.playChunks(chunks, args(1), 0)
        Thread.sleep(200)
        player.stop()
        Thread.sleep(150)
        assertEquals(Status.IDLE, player.state.value.status)
        assertEquals(0, activeSinks.get())
        Thread.sleep(150)
        assertEquals(PlayerState(), player.state.value)
        assertInvariants()
    }

    @Test fun utteranceIdsRoundTrip() {
        assertEquals(7L to 12, UtteranceId.parse(UtteranceId.of(7, 12)))
        assertEquals(null, UtteranceId.parse("12"))
    }

    @Test fun repeatModeCyclesAndPicksNextIndex() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
        assertEquals(null, RepeatMode.OFF.nextIndex(4, 5)); assertEquals(3, RepeatMode.OFF.nextIndex(2, 5))
        assertEquals(0, RepeatMode.ALL.nextIndex(4, 5)); assertEquals(3, RepeatMode.ALL.nextIndex(2, 5))
        assertEquals(2, RepeatMode.ONE.nextIndex(2, 5))
        assertEquals(RepeatMode.OFF, RepeatMode.parse(null)); assertEquals(RepeatMode.ONE, RepeatMode.parse("ONE"))
    }

    @Test fun repeatDocumentRestartsFromBeginning() {
        player.setRepeatMode(RepeatMode.ALL)
        player.playChunks(chunks.take(3), args(1), 1)
        val seen = HashSet<Int>()
        val end = System.currentTimeMillis() + 1200 // > 2 passes of 3 sentences
        while (System.currentTimeMillis() < end) { current?.audible()?.let { seen.add(it % 1000) }; Thread.sleep(5) }
        assertTrue("stopped instead of looping", player.state.value.status != Status.IDLE)
        assertTrue("never wrapped to sentence 0: $seen", 0 in seen)
        assertInvariants()
    }

    @Test fun repeatParagraphLoopsCurrentSentence() {
        player.setRepeatMode(RepeatMode.ONE)
        player.playChunks(chunks, args(1), 5)
        Thread.sleep(150)
        val (_, total) = watch(500, 1) { it == 5 }
        assertTrue("nothing played", total > 20)
        assertEquals(5, player.state.value.index)
        assertInvariants()
    }

    @Test fun sleepTimerStillPausesWhileRepeating() {
        player.setRepeatMode(RepeatMode.ONE)
        player.playChunks(chunks, args(1), 0)
        Thread.sleep(200)
        player.pause() // what the sleep timer does when it fires
        Thread.sleep(100)
        assertEquals(Status.PAUSED, player.state.value.status)
        assertEquals(0, activeSinks.get())
    }
}
