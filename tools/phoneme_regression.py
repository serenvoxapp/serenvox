#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regression test: every common word must produce phoneme tokens with the app's exact
Kokoro config (US and UK), and every Piper voice must produce audio for it.

Run with the sherpa-onnx wheel matching app/libs (1.13.8):
  python tools/phoneme_regression.py <kokoro-engine-dir> [piper-dir ...]
Exits 1 listing any word that yields no tokens (the 1.2.0 "belongs" bug: sherpa-onnx 2.x
silently drops words missing from the lexicon and has no eSpeak fallback)."""
import os, re, subprocess, sys, json
HERE = os.path.dirname(os.path.abspath(__file__))
WORDS = [w for w in open(os.path.join(HERE, "common_words.txt")).read().split() if w][:int(os.environ.get("WORDS_LIMIT", "100000"))]

def child(mode, d, lang, lex):
    import sherpa_onnx as so, glob
    if mode == "kokoro":
        cfg = so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(model=d+"/model.onnx", voices=d+"/voices.bin",
            tokens=d+"/tokens.txt", data_dir=d+"/espeak-ng-data", lexicon=d+"/"+lex, lang=lang), num_threads=4, debug=True)
    else:
        cfg = so.OfflineTtsModelConfig(vits=so.OfflineTtsVitsModelConfig(model=glob.glob(d+"/*.onnx")[0], tokens=d+"/tokens.txt",
            data_dir=d+"/espeak-ng-data"), num_threads=4, debug=False)
    t = so.OfflineTts(so.OfflineTtsConfig(model=cfg))
    # Piper exposes no token log; espeak always phonemizes, so the failure mode is no audio.
    # Isolated function words can be very short on some voices, so take the longer of 2 runs.
    for w in WORDS:
        sys.stderr.write(f"\n@@WORD {w}\n"); sys.stderr.flush()
        a = t.generate(w, sid=0)
        if mode != "kokoro":
            b = t.generate(w, sid=0)
            if len(b.samples) > len(a.samples): a = b
        dur = len(a.samples) / max(a.sample_rate, 1)
        sys.stderr.write(f"\n@@DUR {dur:.3f}\n"); sys.stderr.flush()

def run(mode, d, lang="", lex=""):
    p = subprocess.run([sys.executable, __file__, "--child", mode, d, lang, lex], capture_output=True, text=True)
    bad, cur, toks = [], None, 0
    for line in p.stderr.splitlines():
        if line.startswith("@@WORD "): cur, toks = line[7:], 0
        elif re.fullmatch(r"\s*\d+( \d+)+\s*", line):  # token id line, PAD=0 space=16 excluded
            toks = sum(1 for x in line.split() if x not in ("0", "16"))
        elif "tokens: [" in line:  # sherpa-onnx >= 2.0 debug format
            toks = len([x for x in re.findall(r"\((\d+)\)", line) if x not in ("0", "16")])
        elif line.startswith("@@DUR "):
            dur = float(line[6:])
            if (mode == "kokoro" and toks == 0) or dur < 0.03: bad.append(cur)
    if p.returncode: bad.append(f"<crash rc={p.returncode}>")
    return bad

if __name__ == "__main__":
    if sys.argv[1] == "--child": child(*sys.argv[2:6]); sys.exit(0)
    fails = {}
    k = sys.argv[1]
    for lang, lex in (("en-us", "lexicon-us-en.txt"), ("en", "lexicon-gb-en.txt")):
        fails[f"kokoro {lang}"] = run("kokoro", k, lang, lex)
    for d in sys.argv[2:]: fails[os.path.basename(d)] = run("piper", d)
    for k2, v in fails.items(): print(f"{k2}: {len(WORDS)-len(v)}/{len(WORDS)} ok" + (f"  FAILED: {v}" if v else ""))
    sys.exit(1 if any(fails.values()) else 0)
