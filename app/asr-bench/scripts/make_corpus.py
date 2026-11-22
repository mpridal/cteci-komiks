#!/usr/bin/env python3
"""Testovací nahrávky pro asr-bench (prototyp A): čtení, chybné čtení, šum a cizí hovor.

Použití (ze složky app/):
  python -I asr-bench/scripts/make_corpus.py --out %LOCALAPPDATA%/cteci-komiks-build/asr-corpus

Čtení generuje český systémový hlas Windows (Jakub) slovo po slově s pauzami jako začínající
čtenářka; časy konců slov jdou do manifest.json (pro přesné měření odezvy).
Scénáře: read (čtení stránky), syllables (víceslabičná slova po slabikách), misread (těžká slova
špatně), child_* (totéž vyšším hlasem, pomaleji, se zaváháním a „ehm“), read_babble (čtení +
anglický hovor v pozadí), silence, noise, hum, babble_en, talk_cs.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import struct
import subprocess
import sys
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
ASSETS = APP / "app" / "src" / "main" / "assets" / "proto"
RATE = 16000
PUNCT = ".,!?…:;\"„“”'()"

FOREIGN_CS = ("Dnes bude polojasno, odpoledne místy přeháňky. Teploty vystoupí na dvacet stupňů. "
              "Na horách může foukat silný vítr, v nížinách bude klid. Zítra se ochladí.")
FOREIGN_EN = ("The weather today will be mostly sunny with a light breeze from the west. "
              "In the afternoon, clouds will gather over the hills and there may be a short shower.")


def tts(text: str, voice: str, out: Path, rate: float = 1.0, pitch: float = 1.0) -> list[float]:
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "tts.ps1"),
                    "-Voice", voice, "-Text", text, "-Out", str(out), "-Rate", str(rate), "-Pitch", str(pitch)],
                   check=True, capture_output=True)
    with wave.open(str(out)) as w:
        if w.getframerate() != RATE or w.getnchannels() != 1:
            raise ValueError(f"{out}: čekám 16 kHz mono")
        data = w.readframes(w.getnframes())
    return [s / 32768 for s in struct.unpack(f"<{len(data) // 2}h", data)]


def trim(x: list[float], thr=0.01) -> list[float]:
    idx = [i for i, s in enumerate(x) if abs(s) > thr]
    return x[max(0, idx[0] - 160): idx[-1] + 320] if idx else []


def silence(sec: float) -> list[float]:
    return [0.0] * int(sec * RATE)


def write(path: Path, x: list[float], noise_floor=0.0015, seed=7) -> None:
    rnd = random.Random(seed)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s + rnd.gauss(0, noise_floor))) * 32767))
                               for s in x))


def mix(a: list[float], b: list[float], gain_b: float) -> list[float]:
    n = max(len(a), len(b))
    return [(a[i] if i < len(a) else 0) + (b[i % len(b)] * gain_b if b else 0) for i in range(n)]


def misread(word: str) -> str | None:
    """Typická chyba začínající čtenářky: vynechá souhlásku ze shluku, jednopísmenné slovo přeskočí."""
    core = word.strip(PUNCT)
    if len(core) <= 1:
        return None
    vowels = set("AÁEÉĚIÍOÓUÚŮYÝ")
    for i in range(len(core) - 1):
        if core[i] not in vowels and core[i + 1] not in vowels:
            return core[:i + 1] + core[i + 2:]
    return core[:-1] if core[-1] not in vowels else core[1:]


def load_pages() -> list[dict]:
    index = json.loads((ASSETS / "pages.json").read_text(encoding="utf-8"))
    pages = []
    for ref in index["pages"]:
        ch = json.loads((ASSETS / ref["chapter"] / "chapter.json").read_text(encoding="utf-8"))
        panel = next(p for pg in ch["pages"] for p in pg["panels"] if p["id"] == ref["panel"])
        texts = sorted(panel["texts"], key=lambda t: 0 if t["kind"] == "narration" else 1)
        words = []
        for t in texts:
            for w in t["words"]:
                asr = w.get("asr") or {}
                words.append({"w": w["w"], "syl": w["syl"], "practice": w.get("practice", []),
                              "asr": asr.get("alt", []), "oov": bool(asr.get("oov"))})
        pages.append({"id": f"{ref['chapter']}/{ref['panel']}", "words": words})
    return pages


ADULT = {"rate": 0.85, "pitch": 1.0, "pause": 0.45, "syl_pause": 0.25, "hesitate": 0.0, "filler": 0.0}
# přiblížení dítěti: vyšší hlas, pomaleji, delší pauzy, občas zaváhá („MO… MOŘI“) nebo řekne „ehm“
CHILD = {"rate": 0.7, "pitch": 1.6, "pause": 0.75, "syl_pause": 0.35, "hesitate": 0.3, "filler": 0.12}


def say(text: str, voice: dict, tmp: Path, cache: dict) -> list[float]:
    key = (text.lower(), voice["rate"], voice["pitch"])
    if key not in cache:
        cache[key] = trim(tts(text.lower(), "Jakub", tmp / "w.wav", voice["rate"], voice["pitch"]))
    return cache[key]


def reading(words: list[dict], tmp: Path, mode: str, cache: dict,
            voice: dict = ADULT, seed: int = 1) -> tuple[list[float], list[int], list[int]]:
    """Slovo po slově s pauzou. Vrátí audio, konce slov (ms) a indexy čtených slov."""
    rnd = random.Random(seed)
    audio = silence(0.8)
    ends, idxs = [], []
    for i, w in enumerate(words):
        text = w["w"].strip(PUNCT)
        if mode == "misread" and w["practice"]:
            text = misread(w["w"])
            if text is None:
                continue
        if rnd.random() < voice["filler"]:
            audio += say("ehm", voice, tmp, cache) + silence(0.4)
        parts = w["syl"] if (mode == "syllables" and len(w["syl"]) > 1) else [text]
        if mode != "syllables" and len(w["syl"]) > 1 and rnd.random() < voice["hesitate"]:
            audio += say(w["syl"][0], voice, tmp, cache) + silence(0.35)  # zaváhání
        for k, part in enumerate(parts):
            audio += say(part, voice, tmp, cache)
            if k < len(parts) - 1:
                audio += silence(voice["syl_pause"])
        ends.append(int(len(audio) / RATE * 1000))
        idxs.append(i)
        audio += silence(voice["pause"] * rnd.uniform(0.7, 1.4))
    return audio + silence(1.5), ends, idxs


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args(argv)
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / "tmp"
    tmp.mkdir(exist_ok=True)
    pages = load_pages()
    cache: dict = {}
    scenarios = []

    babble = trim(tts(FOREIGN_EN, "Zira", tmp / "en.wav")) + silence(0.3) + trim(tts(FOREIGN_EN, "David", tmp / "en2.wav"))
    talk_cs = trim(tts(FOREIGN_CS, "Jakub", tmp / "cs.wav"))
    rnd = random.Random(3)
    white = [rnd.gauss(0, 0.05) for _ in range(20 * RATE)]
    hum = [0.04 * math.sin(2 * math.pi * 100 * i / RATE) + rnd.gauss(0, 0.01) for i in range(20 * RATE)]

    for pi, page in enumerate(pages):
        for mode in ("read", "syllables", "misread"):
            audio, ends, idxs = reading(page["words"], tmp, mode, cache)
            name = f"p{pi + 1}_{mode}"
            write(out / f"{name}.wav", audio)
            scenarios.append({"name": name, "kind": mode, "page": pi, "wav": f"{name}.wav",
                              "wordEndsMs": ends, "spokenWords": idxs})
        for mode in ("read", "syllables", "misread"):
            audio, ends, idxs = reading(page["words"], tmp, mode, cache, CHILD, seed=pi + 11)
            name = f"p{pi + 1}_child_{mode}"
            write(out / f"{name}.wav", audio)
            scenarios.append({"name": name, "kind": f"child_{mode}", "page": pi, "wav": f"{name}.wav",
                              "wordEndsMs": ends, "spokenWords": idxs})
        audio, ends, idxs = reading(page["words"], tmp, "read", cache)
        name = f"p{pi + 1}_read_babble"
        write(out / f"{name}.wav", mix(audio, babble, 0.35))
        scenarios.append({"name": name, "kind": "read_babble", "page": pi, "wav": f"{name}.wav",
                          "wordEndsMs": ends, "spokenWords": idxs})
        for kind, sig in (("silence", silence(15)), ("noise", white), ("hum", hum),
                          ("babble_en", babble), ("talk_cs", talk_cs)):
            name = f"p{pi + 1}_{kind}"
            write(out / f"{name}.wav", sig)
            scenarios.append({"name": name, "kind": kind, "page": pi, "wav": f"{name}.wav"})

    (out / "manifest.json").write_text(json.dumps({"pages": pages, "scenarios": scenarios},
                                                  ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(scenarios)} scénářů → {out}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
