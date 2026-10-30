// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui

import com.serenvox.app.tts.VoiceCatalog

object Licences {
    val text: String get() = buildString {
        appendLine("ABOUT SERENVOX")
        appendLine()
        appendLine("Serenvox is free and open-source software: Copyright 2026 Ryan Lu, licensed under the GNU General Public License v3.0 or later, with an additional permission (GPL section 7) for linking with Google Play services, AdMob, UMP, Play Billing and ML Kit libraries.")
        appendLine("Complete source code: $SOURCE_URL")
        appendLine("This program comes with ABSOLUTELY NO WARRANTY. See https://www.gnu.org/licenses/gpl-3.0.html")
        appendLine()
        appendLine("VOICE MODELS (downloaded on demand)")
        appendLine()
        appendLine("• Natural voices (${VoiceCatalog.natural.joinToString { it.name }}) — Kokoro-82M v1.0")
        appendLine("  Licence: Apache License 2.0 (model weights)")
        appendLine("  ${VoiceCatalog.natural.first().attribution}")
        appendLine("  Model card: https://huggingface.co/hexgrad/Kokoro-82M · Training data includes SIWIS (CC BY 4.0) and Koniwa (CC BY 3.0) audio.")
        appendLine()
        VoiceCatalog.compact.forEach {
            appendLine("• ${it.name} — ${it.id}")
            appendLine("  Licence: ${it.licence}")
            appendLine("  ${it.attribution}")
            appendLine()
        }
        appendLine("CC BY 4.0: https://creativecommons.org/licenses/by/4.0/")
        appendLine("Compact voices trained with Piper (https://github.com/rhasspy/piper, MIT) and packaged by the sherpa-onnx project.")
        appendLine()
        appendLine("SOFTWARE")
        appendLine()
        appendLine("• sherpa-onnx — Apache License 2.0 — https://github.com/k2-fsa/sherpa-onnx")
        appendLine("• PdfBox-Android (PDF text extraction) — Apache License 2.0 — https://github.com/TomRoush/PdfBox-Android")
        appendLine("• Inter typeface by Rasmus Andersson — SIL Open Font License 1.1 — https://rsms.me/inter/")
        appendLine("• ONNX Runtime — MIT License — https://github.com/microsoft/onnxruntime")
        appendLine("• eSpeak NG (phonemizer, linked inside the sherpa-onnx native library) — GNU GPL v3 or later — https://github.com/espeak-ng/espeak-ng")
        appendLine("  The complete corresponding source of this app, including build files and the exact third-party versions used, is at $SOURCE_URL")
        appendLine("• piper-phonemize — MIT License")
        appendLine("• Apache Commons Compress — Apache License 2.0")
        appendLine("• AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines — Apache License 2.0")
        appendLine("• Google Mobile Ads SDK, User Messaging Platform SDK, Play Billing Library, ML Kit Text Recognition (on-device OCR) — Google terms (https://developers.google.com/terms)")
        appendLine()
        appendLine("Full licence texts: Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0 · MIT https://opensource.org/license/mit · GPL-3.0 https://www.gnu.org/licenses/gpl-3.0.html")
    }
}
