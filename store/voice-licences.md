# Serenvox: on-device voice licence review (1.2.0)

Reviewed 30 Oct 2026 for commercial use in an ad-supported app (GPL-3.0-or-later + Google-libraries exception).
Rule: both the model weights and the training data must allow commercial use. Never lessac-derived, VCTK, or non-commercial voices.

## Summary

| Candidate (sherpa-onnx package) | Weights | Training data | Verdict |
|---|---|---|---|
| **Kokoro-82M v1.0** (`kokoro-multi-lang-v1_0`, fp32 / int8) | Apache-2.0 | Model card: "trained exclusively on permissive/non-copyrighted audio": public domain, Apache/MIT audio, synthetic audio from closed TTS providers (no open-model or voice-clone output), SIWIS (CC BY 4.0), Koniwa (CC BY 3.0). Base model: StyleTTS2-LJSpeech (LJ Speech is public domain). | **PASS, with a caveat** (see below). **Chosen.** |
| Kokoro v0.19 (`kokoro-en-v0_19`, int8) | Apache-2.0 | Same statement, earlier and smaller (<100 h). | Pass. Superseded by v1.0. |
| Kokoro v1.1 (`kokoro-multi-lang-v1_1`) | Apache-2.0 | v1.0 data plus Chinese data. The English voices are the same as v1.0. | Pass. No English benefit, and it is 14 MB larger. |
| Kitten TTS nano/micro/mini (v0.1–v0.8) | Apache-2.0 | **Not disclosed.** The model cards give no dataset information. | **FAIL** (provenance can't be verified). Re-check if KittenML publishes its data. |
| Matcha-TTS `matcha-icefall-en_US-ljspeech` | Apache-2.0 (icefall) | LJ Speech (public domain). Vocos vocoder is MIT. | Pass, but it is the same LJ Speech voice as Piper "Linda", so it brings no naturalness gain. |
| Matcha `fa_en-*`, `zh-*` | n/a | Not English, or different datasets | Not relevant. |
| Piper lessac/amy/ryan/alba/VCTK/etc. | MIT code | Research-only or non-commercial data | FAIL (still excluded). |

## Kokoro caveat
- The authors don't publish provenance for each voice. VOICES.md gives only quality and duration grades.
- Part of the data is synthetic output from "closed TTS models from large providers". The author relies on US Copyright Office guidance that AI output isn't copyrightable.
- Some providers' terms forbid using their output to train competing models. That is a contract risk between the trainer and the provider. It is not a licence restriction on the Apache-2.0 weights, and Kokoro is used commercially at scale (several commercial APIs).
- To reduce reputational risk, Serenvox **does not ship** the voices that are named after, or widely linked to, specific commercial voices: af_alloy, af_nova, am_echo, am_onyx, bm_fable, af_sky. It also skips the low-grade voices (D/F).
- Attribution for the CC BY training data (SIWIS, Koniwa) is given in the app and the README.

## Shipped Kokoro speakers (sherpa-onnx speaker id, VOICES.md grade)
- Heart (US F): `af_heart`, id 3, grade A
- Bella (US F): `af_bella`, id 2, grade A-
- Michael (US M): `am_michael`, id 16, grade C+
- Fenrir (US M): `am_fenrir`, id 14, grade C+
- Emma (UK F): `bf_emma`, id 21, grade B-
- George (UK M): `bm_george`, id 26, grade C

## Primary sources
- https://huggingface.co/hexgrad/Kokoro-82M (README: licence, Training Details, CC attribution) and VOICES.md
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models (package contents; LICENSE in the tarball is Apache-2.0; speaker ids come from the model metadata)
- https://huggingface.co/KittenML/kitten-tts-nano-0.2, kitten-tts-nano-0.8 (Apache-2.0, no data section)

## Performance (measured on the box: Intel Xeon, x86-64, sherpa-onnx 1.13.8 Python)
Test: a 6.9 s sentence, after warm-up. Real-time factor (RTF) = synthesis time ÷ audio time.

| Model | Download (tar.bz2) | On disk | RTF, 2 threads | RTF, 4 threads | Peak RSS |
|---|---|---|---|---|---|
| Piper ljspeech-medium | 64 MB | ~65 MB | 0.04 | — | — |
| Kokoro v1.0 fp32 | 333 MB | ~380 MB | 0.29 | 0.19 | ~750 MB (process) |
| Kokoro v1.0 int8 | 126 MB | 183 MB | 0.86 | 0.70 | ~585 MB (process) |
| **Kokoro v1.0 fp16 weights (1.2.1 engine, trimmed)** | **149 MB + 0.5 MB/voice** | **~170 MB** | **0.30** | **0.18** | as fp32 (weights cast to fp32 at load) |
| Kokoro v1.0 full fp16 (onnxconverter-common keep_io_types) | — | 164 MB | fails to load | — | — |

- On this CPU, the dynamically quantised int8 model is about 4× **slower** than fp32 (ConvInteger kernels), even though it is smaller.
- Phone estimate (rule of thumb: a mid-range Cortex-A78 core is about 2–3× slower than this Xeon core):
  - fp32, 4 threads: RTF about 0.4–0.6 on a mid-range phone (Snapdragon 7-series or Tensor). That is faster than real time. Sentence-ahead buffering (2 sentences) hides the synthesis time after the first sentence. Expect about 1–2 s before the first sentence starts.
  - Low-end phones (Cortex-A55 only): RTF over 1, so audio would stall. Use a Compact voice there.
  - int8 on ARM: unmeasured. It is likely around 1.0 or slower, so it is not chosen despite the smaller download.
  - RAM: about 350–450 MB for the model while speaking.
- **Decision:** ship fp32 Kokoro v1.0 as the "Natural" pack and keep Piper as "Compact". This is still to be confirmed on Ryan's phone.

## 1.2.1 engine
- fp16 *weights*, fp32 compute: every float initializer ≥1024 elements stored as fp16 with a Cast to fp32 (ORT folds these at session start). Output vs fp32: long-term spectrum within 0.12 dB, utterance length within 1 ms (int8: 2.5 dB, 140 ms), same sample durations for the comparison paragraph. Speed equals fp32. Full fp16 conversion (onnxconverter-common / onnxruntime.transformers, keep_io_types) produced invalid graphs (Cast type mismatch, duplicate node names), and fp16 compute on ARM CPU EP has partial kernel coverage anyway.
- Trimmed: zh lexicon, jieba dict, zh FSTs, voices for all 54 speakers, all non-English eSpeak data (19 MB → 0.85 MB: phontab/phonindex/phondata/intonations/en_dict + lang/gmw/en*).
- Voices: each speaker is a 522,240-byte style file; the app writes it into a zero-filled 54-speaker voices.bin at its speaker id (tested in sherpa-onnx 1.13.8 with only 2 speakers present).
- Runtime: sherpa-onnx **1.13.8** AAR. 2.0.x removed the built-in eSpeak NG phonemizer: Kokoro silently skipped any word not in the lexicon ("belongs" on US voices) and Piper voices produced no audio. Regression check: tools/phoneme_regression.py.
