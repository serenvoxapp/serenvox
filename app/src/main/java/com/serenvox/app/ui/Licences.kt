package com.serenvox.app.ui

import com.serenvox.app.tts.VoiceCatalog

object Licences {
    val text: String get() = buildString {
        appendLine("VOICE MODELS (downloaded on demand)")
        appendLine()
        VoiceCatalog.voices.forEach {
            appendLine("• ${it.name} — ${it.id}")
            appendLine("  Licence: ${it.licence}")
            appendLine("  ${it.attribution}")
            appendLine()
        }
        appendLine("CC BY 4.0: https://creativecommons.org/licenses/by/4.0/")
        appendLine("Voices trained with Piper (https://github.com/rhasspy/piper, MIT) and packaged by the sherpa-onnx project.")
        appendLine()
        appendLine("SOFTWARE")
        appendLine()
        appendLine("• sherpa-onnx — Apache License 2.0 — https://github.com/k2-fsa/sherpa-onnx")
        appendLine("• ONNX Runtime — MIT License — https://github.com/microsoft/onnxruntime")
        appendLine("• eSpeak NG (phonemizer, linked inside the sherpa-onnx native library) — GNU GPL v3 or later — https://github.com/espeak-ng/espeak-ng")
        appendLine("  Source code for eSpeak NG and sherpa-onnx is available at the URLs above. On request we will provide the corresponding source for the exact versions used in this app for at least three years. Contact the developer via the Play Store listing.")
        appendLine("• piper-phonemize — MIT License")
        appendLine("• Apache Commons Compress — Apache License 2.0")
        appendLine("• AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines — Apache License 2.0")
        appendLine("• Google Mobile Ads SDK, User Messaging Platform SDK, Play Billing Library — Google terms (https://developers.google.com/terms)")
        appendLine()
        appendLine("Full licence texts: Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0 · MIT https://opensource.org/license/mit · GPL-3.0 https://www.gnu.org/licenses/gpl-3.0.html")
    }
}
