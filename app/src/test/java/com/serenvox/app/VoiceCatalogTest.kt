// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import com.serenvox.app.tts.KOKORO_PACK
import com.serenvox.app.tts.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceCatalogTest {
    // Speaker ids from kokoro-multi-lang-v1_0 model metadata (id2speaker).
    private val expected = mapOf("kokoro-heart" to 3, "kokoro-bella" to 2, "kokoro-michael" to 16,
        "kokoro-fenrir" to 14, "kokoro-emma" to 21, "kokoro-george" to 26)

    @Test fun kokoroSpeakersMapToModelIds() {
        assertEquals(expected, VoiceCatalog.natural.associate { it.id to it.speakerId })
        VoiceCatalog.natural.forEach { assertEquals(KOKORO_PACK, it.archiveName) }
    }

    @Test fun everyVoiceHasBundledPreview() {
        val dir = File("src/main/assets/samples")
        VoiceCatalog.voices.forEach { v ->
            val key = VoiceCatalog.sampleKey(v.id)!!
            assertTrue("missing preview $key", File(dir, "$key.m4a").isFile)
        }
        VoiceCatalog.featuredSpeakers.forEach { assertTrue(File(dir, "${VoiceCatalog.LIBRITTS}_$it.m4a").isFile) }
    }

    @Test fun onboardingDefaultsToNaturalVoice() {
        assertEquals("kokoro-heart", VoiceCatalog.voices.first().id)
        VoiceCatalog.onboarding.forEach { assertTrue(VoiceCatalog.byId(it) != null) }
    }
}
