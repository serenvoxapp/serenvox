<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Serenvox — offline AI text-to-speech for Android

Serenvox reads any text aloud with natural neural voices that run **entirely on the device** —
no cloud, no account, works offline after a one-time voice download.

Features: type/paste/import .txt, "Share to" / text-selection receiver, play/pause/stop, speed & pitch,
voice manager (download/preview/delete), sentence highlighting, long-text chunking, background playback
with media notification, WAV export & share, dark mode / Material You. Falls back to Android's system TTS.
Free with ads (AdMob banner + rare capped interstitial, UMP consent); one-time `remove_ads` purchase.

## Build
Requirements: JDK 17, Android SDK (platform 36, build-tools 36).
```sh
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintRelease
./gradlew assembleDebug                      # debug APK, test ad IDs
./gradlew bundleRelease                      # Play AAB
./gradlew -PsplitApks assembleRelease        # per-ABI release APKs
```
- Release signing: create `keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
  Without it, release builds are unsigned.
- Ads: copy `ads.properties.example` to `ads.properties` (git-ignored) with your AdMob IDs. If absent, Google's test IDs are used.
- App name: `app/src/main/res/values/strings.xml`. Source/privacy URLs: `ui/AppRoot.kt` (`SOURCE_URL`, `PRIVACY_URL`).

## Licence
Copyright 2026 Ryan Lu. Serenvox is licensed under the **GNU General Public License v3.0 or later** — see [LICENSE](LICENSE).

**Additional permission under GNU GPL version 3 section 7:** If you modify this Program, or any covered work, by
linking or combining it with the Google Mobile Ads SDK (and its Google Play services dependencies), the Google
User Messaging Platform SDK, the Google Play Billing Library, or Google ML Kit (including ML Kit Text Recognition) (or a modified version of those libraries),
containing parts covered by the terms of their respective Google licence agreements, the licensors of this
Program grant you additional permission to convey the resulting work. Corresponding Source for a non-source form
of such a combination shall not include the source code for the parts of those libraries used as well as that of
the covered work. This permission covers only code copyrighted by Ryan Lu; it does not extend to third-party GPL
components such as eSpeak NG.

## Third-party components
| Component | Licence |
|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1 (`app/libs/`, prebuilt AAR) | Apache-2.0 |
| [eSpeak NG](https://github.com/espeak-ng/espeak-ng) (statically linked in sherpa-onnx native lib; `espeak-ng-data` shipped with voices) | GPL-3.0-or-later |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT |
| [Piper](https://github.com/rhasspy/piper) / piper-phonemize | MIT |
| Apache Commons Compress | Apache-2.0 |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | Apache-2.0 |
| Google Mobile Ads SDK, UMP SDK, Play Billing Library, ML Kit Text Recognition (on-device OCR) | Proprietary (Google terms) |

## Voice credits (downloaded at runtime, not in this repo)
| Voice | Source data | Licence |
|---|---|---|
| **Natural:** Kokoro-82M v1.0 (`kokoro-multi-lang-v1_0`, speakers af_heart, af_bella, am_michael, am_fenrir, bf_emma, bm_george) by hexgrad, StyleTTS 2 architecture (Li et al.) | Per the model card: public-domain audio, Apache/MIT-licensed audio, synthetic audio from closed commercial TTS, SIWIS (CC BY 4.0), Koniwa (CC BY 3.0); base model StyleTTS2-LJSpeech (public domain) | **Apache-2.0** weights — https://huggingface.co/hexgrad/Kokoro-82M |
| en_US-ljspeech-medium | LJ Speech (Keith Ito), LibriVox | Public domain |
| en_US-libritts-high | **LibriTTS** corpus — Heiga Zen, Viet Dang, Rob Clark, Yu Zhang, Ron J. Weiss, Ye Jia, Zhifeng Chen, Yonghui Wu (2019), derived from LibriVox | **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/) |
| en_US-kristin-medium | LibriVox | Public domain |
| en_US-norman-medium | LibriVox | Public domain |
| en_GB-cori-medium | LibriVox | Public domain |

See `store/voice-licences.md` for the licence review. Compact voices are Piper VITS models (trained by Michael Hansen / rhasspy) converted by the sherpa-onnx project.
Voices trained on the lessac (research-only) or non-commercial datasets are deliberately excluded.

### Additional third-party components (1.1.0)
| Component | Licence |
|---|---|
| PdfBox-Android (com.tom-roush) | Apache-2.0 |
| Inter typeface | SIL OFL 1.1 (third_party/Inter-OFL.txt) |

Import formats: PDF (text-based), Word .docx and legacy .doc, EPUB, TXT, HTML.
