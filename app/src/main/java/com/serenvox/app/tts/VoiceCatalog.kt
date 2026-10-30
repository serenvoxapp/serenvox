// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

/**
 * Downloadable neural voices. Only voices whose training data licence permits
 * commercial use are listed (lessac/amy/ryan/alba etc. are intentionally excluded).
 * Natural: Kokoro-82M (Apache-2.0). Compact: Piper (MIT) VITS voices. Both converted by sherpa-onnx.
 */
data class VoiceInfo(
    val id: String,
    val name: String,
    val locale: String,
    val description: String,
    val sizeMb: Int,
    val licence: String,
    val attribution: String,
    val engine: Engine = Engine.PIPER,
    /** Kokoro: fixed speaker id inside the shared model pack. */
    val speakerId: Int? = null,
) {
    val archiveName get() = if (engine == Engine.KOKORO) KOKORO_PACK else "vits-piper-$id"
    val isKokoro get() = engine == Engine.KOKORO
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName.tar.bz2"
}

enum class Engine { PIPER, KOKORO }

const val KOKORO_PACK = "kokoro-multi-lang-v1_0"
private const val KOKORO_ATTR = "Kokoro-82M v1.0 by hexgrad (Apache-2.0 weights; trained on public-domain, permissively licensed and synthetic audio), StyleTTS 2 architecture by Li et al.; ONNX export by the sherpa-onnx project (Apache-2.0)."

object VoiceCatalog {
    /** Natural voices: one ~330 MB Kokoro pack shared by all speakers below. */
    val natural = listOf(
        VoiceInfo("kokoro-heart", "Heart (US)", "en-US", "Warm, expressive US female", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 3),
        VoiceInfo("kokoro-bella", "Bella (US)", "en-US", "Bright, lively US female", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 2),
        VoiceInfo("kokoro-michael", "Michael (US)", "en-US", "Calm US male", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 16),
        VoiceInfo("kokoro-fenrir", "Fenrir (US)", "en-US", "Deep US male", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 14),
        VoiceInfo("kokoro-emma", "Emma (UK)", "en-GB", "Clear British female", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 21),
        VoiceInfo("kokoro-george", "George (UK)", "en-GB", "Measured British male", 333, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 26),
    )

    /** Compact (lighter, faster) Piper voices. */
    val compact = listOf(
        VoiceInfo("en_US-ljspeech-medium", "Linda (US)", "en-US", "Clear US female narrator", 64,
            "Public domain", "LJ Speech dataset by Keith Ito (public domain); Piper voice by Michael Hansen."),
        VoiceInfo("en_US-libritts-high", "LibriTTS (US, 904 speakers)", "en-US", "Many US voices, high quality; choose a speaker number", 125,
            "CC BY 4.0", "LibriTTS corpus by Heiga Zen et al. (Google), CC BY 4.0, derived from LibriVox; Piper voice by Michael Hansen."),
        VoiceInfo("en_US-kristin-medium", "Kristin (US)", "en-US", "Warm US female", 64,
            "Public domain", "Kristin voice from LibriVox recordings (public domain); Piper voice."),
        VoiceInfo("en_US-norman-medium", "Norman (US)", "en-US", "US male narrator", 64,
            "Public domain", "Norman voice from LibriVox recordings (public domain); Piper voice."),
        VoiceInfo("en_GB-cori-medium", "Cori (UK)", "en-GB", "British female narrator", 64,
            "Public domain", "Cori voice from LibriVox recordings (public domain); Piper voice."),
    )

    val voices = natural + compact

    /** Voices offered on the first-run screen (natural first, then the lightest compact one). */
    val onboarding = listOf("kokoro-heart", "kokoro-michael", "kokoro-emma", "kokoro-george", "en_US-ljspeech-medium", "en_US-kristin-medium")

    /** LibriTTS speakers with a bundled preview clip. */
    val featuredSpeakers = listOf(0, 20, 100, 300, 600)
    const val LIBRITTS = "en_US-libritts-high"

    /** Asset key (assets/samples/<key>.m4a) of the offline preview clip for a voice. */
    fun sampleKey(id: String, speaker: Int = 0): String? = when {
        id.startsWith("kokoro-") && voices.any { it.id == id } -> id
        id == LIBRITTS -> "${LIBRITTS}_${if (speaker in featuredSpeakers) speaker else 0}"
        voices.any { it.id == id } -> id
        else -> null
    }

    fun byId(id: String) = voices.firstOrNull { it.id == id }
}
