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
    /** Piper: full archive. Kokoro: this speaker's small style-vector file (the engine downloads separately). */
    val url get() = if (isKokoro) "$VOICE_PACK_BASE_URL$voiceFile"
        else "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName.tar.bz2"
    /** Kokoro speaker file name on the voice-pack host. */
    val voiceFile get() = "kokoro-voice-${id.removePrefix("kokoro-")}.bin"
}

enum class Engine { PIPER, KOKORO }

/** Kokoro engine pack (fp16-weight model, tokens, en lexicons, trimmed espeak-ng-data); downloaded once. */
const val KOKORO_PACK = "kokoro-engine-v1_0-fp16"

/**
 * Single place for the voice-pack host. Every file below must be uploaded here unchanged
 * (see dist/voice-packs/SHA256SUMS); the app rejects any file whose SHA-256 differs.
 */
const val VOICE_PACK_BASE_URL = "https://serenvox.com/voice-packs/v1/"

object KokoroPack {
    const val ENGINE_FILE = "$KOKORO_PACK.tar.bz2"
    const val ENGINE_BYTES = 148_860_876L
    const val ENGINE_SHA256 = "64e923c8abc58bcc3676571d261dd7461fcee66c6ed83f00b7e7f674f131aac5"
    val ENGINE_URL get() = "$VOICE_PACK_BASE_URL$ENGINE_FILE"
    /** One speaker = 510 x 1 x 256 float32 style vectors. */
    const val SPEAKER_BYTES = 522_240
    const val N_SPEAKERS = 54
    const val ENGINE_MB = 149
    val SHA256 = mapOf(
        "kokoro-voice-bella.bin" to "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b",
        "kokoro-voice-emma.bin" to "669fe0647f9dd04fcab92f1439a40eeb4c8b4ab1f82e4996fe3d918ce4a63b73",
        "kokoro-voice-fenrir.bin" to "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43",
        "kokoro-voice-george.bin" to "c4b235a4c1f2cd3b939fed08b899ce9385638b763f7b73a59616c4fc9bd6c9bc",
        "kokoro-voice-heart.bin" to "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b",
        "kokoro-voice-michael.bin" to "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1",
    )

    /** Download size label: the first natural voice also brings the shared engine. */
    fun sizeLabel(engineInstalled: Boolean) = if (engineInstalled) "0.5 MB" else "$ENGINE_MB MB with engine"
}
private const val KOKORO_ATTR = "Kokoro-82M v1.0 by hexgrad (Apache-2.0 weights; trained on public-domain, permissively licensed and synthetic audio), StyleTTS 2 architecture by Li et al.; ONNX export by the sherpa-onnx project (Apache-2.0)."

object VoiceCatalog {
    /** Natural voices: a 149 MB shared engine plus a 0.5 MB file per speaker. sizeMb is the speaker file (rounded up). */
    val natural = listOf(
        VoiceInfo("kokoro-heart", "Heart (US)", "en-US", "Warm, expressive US female", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 3),
        VoiceInfo("kokoro-bella", "Bella (US)", "en-US", "Bright, lively US female", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 2),
        VoiceInfo("kokoro-michael", "Michael (US)", "en-US", "Calm US male", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 16),
        VoiceInfo("kokoro-fenrir", "Fenrir (US)", "en-US", "Deep US male", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 14),
        VoiceInfo("kokoro-emma", "Emma (UK)", "en-GB", "Clear British female", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 21),
        VoiceInfo("kokoro-george", "George (UK)", "en-GB", "Measured British male", 1, "Apache-2.0", KOKORO_ATTR, Engine.KOKORO, 26),
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
