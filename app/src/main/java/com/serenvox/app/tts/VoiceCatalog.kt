package com.serenvox.app.tts

/**
 * Downloadable neural voices. Only voices whose training data licence permits
 * commercial use are listed (lessac/amy/ryan/alba etc. are intentionally excluded).
 * Models: Piper (MIT) VITS voices converted by the sherpa-onnx project.
 */
data class VoiceInfo(
    val id: String,
    val name: String,
    val locale: String,
    val description: String,
    val sizeMb: Int,
    val licence: String,
    val attribution: String,
) {
    val archiveName get() = "vits-piper-$id"
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName.tar.bz2"
}

object VoiceCatalog {
    val voices = listOf(
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

    fun byId(id: String) = voices.firstOrNull { it.id == id }
}
