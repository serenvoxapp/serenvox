package com.serenvox.app

import com.serenvox.app.tts.Chunker
import com.serenvox.app.tts.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChunkerTest {
    @Test fun splitsSentences() {
        val c = Chunker.split("Hello there. How are you?  \"Fine!\" she said.\n\nNew para")
        assertEquals(listOf("Hello there.", "How are you?", "\"Fine!\"", "she said.", "New para"), c)
    }

    @Test fun emptyText() = assertTrue(Chunker.split("   \n ").isEmpty())

    @Test fun longSentenceIsBounded() {
        val long = (1..200).joinToString(" ") { "word$it," }
        val c = Chunker.split(long, maxLen = 100)
        assertTrue(c.size > 1)
        assertTrue(c.all { it.length <= 100 })
        assertEquals(long, c.joinToString(" "))
    }

    @Test fun wavHeader() {
        val f = File.createTempFile("wavtest", ".wav")
        WavWriter(f, 22050).use { it.write(FloatArray(100) { 0.5f }) }
        val b = f.readBytes()
        assertEquals(44 + 200, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(200, (b[40].toInt() and 0xff) or ((b[41].toInt() and 0xff) shl 8))
        f.delete()
    }
}
