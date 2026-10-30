# Serenvox — offline AI text-to-speech for Android

Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compile SDK 36.
Neural TTS: sherpa-onnx 2.0.1 (`app/libs/`) with Piper VITS voices downloaded on demand from the
sherpa-onnx `tts-models` GitHub release. Fallback: Android system TextToSpeech.
Monetisation: AdMob banner + capped interstitial (after every 3rd export, ≥5 min apart, never during playback),
UMP consent, Play Billing 8 one-time product `remove_ads`.

## Build
```
export JAVA_HOME=~/jdk/jdk-17.0.21+9 ANDROID_HOME=~/android-sdk
./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease   # universal APK + AAB
./gradlew -PsplitApks assembleRelease                                     # per-ABI APKs
```
Signing reads `keystore.properties` (git-ignored). Ad IDs: `ads.properties`. App name: `res/values/strings.xml`.
Store docs: `store/`.
