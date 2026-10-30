// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Guards the 1.2.0 "missing words" bug: sherpa-onnx >= 2.0 dropped its built-in eSpeak NG
 * phonemizer, so Kokoro silently skipped any word not in the lexicon (e.g. "belongs") and
 * Piper voices produced no audio. The bundled AAR must still contain the eSpeak frontend.
 * The full per-word check runs on desktop: tools/phoneme_regression.py.
 */
class SherpaFrontendTest {
    @Test fun bundledSherpaHasEspeakFrontend() {
        val aar = File("libs").listFiles { f -> f.name.startsWith("sherpa-onnx-") && f.name.endsWith(".aar") }!!.single()
        ZipFile(aar).use { z ->
            val so = z.getEntry("jni/arm64-v8a/libsherpa-onnx-c-api.so")
            val text = String(z.getInputStream(so).readBytes(), Charsets.ISO_8859_1)
            assertTrue("$aar lacks the eSpeak NG phonemizer (sherpa-onnx >= 2.0?)", text.contains("piper-phonemize-lexicon"))
            assertTrue("$aar drops out-of-lexicon words", !text.contains("OOV word skipped"))
        }
    }

    @Test fun commonWordListPresent() {
        val words = File("../tools/common_words.txt").readLines().filter { it.isNotBlank() }
        assertTrue(words.size > 500 && "belongs" in words)
    }
}
