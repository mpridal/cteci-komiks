#!/usr/bin/env python3
"""Syntetické kreslené zvuky ve stylu sfxr (skok, šplouchnutí, cink, pop, třpyt, fanfára).

Použití:
  sfx_synth.py --out studio/sounds/library            všechny zvuky jako WAV (mono, 22 050 Hz)
  sfx_synth.py --out DIR --only boing cink

Jen standardní knihovna. Převod do OGG a normalizace hlasitosti řeší pozdější skript (pydub + ffmpeg).
Licence vytvořených zvuků: CC0 (vlastní syntéza).
"""
from __future__ import annotations

import argparse
import math
import random
import struct
import sys
import wave
from pathlib import Path

RATE = 22050


def env(t: float, dur: float, attack=0.005, release=None) -> float:
    release = release if release is not None else dur * 0.6
    if t < attack:
        return t / attack
    if t > dur - release:
        return max(0.0, (dur - t) / release)
    return 1.0


def tone(freq_fn, dur, wave_fn=math.sin, vol=0.6, release=None):
    out, phase = [], 0.0
    n = int(dur * RATE)
    for i in range(n):
        t = i / RATE
        phase += 2 * math.pi * freq_fn(t) / RATE
        out.append(wave_fn(phase) * env(t, dur, release=release) * vol)
    return out


def square(p):
    return 1.0 if math.sin(p) >= 0 else -1.0


def mix(*tracks):
    n = max(len(t) for t in tracks)
    return [sum(t[i] for t in tracks if i < len(t)) for i in range(n)]


def delay(track, seconds):
    return [0.0] * int(seconds * RATE) + track


def noise(dur, vol=0.5, lowpass=0.2, seed=1):
    rnd = random.Random(seed)
    out, y = [], 0.0
    n = int(dur * RATE)
    for i in range(n):
        t = i / RATE
        y += lowpass * (rnd.uniform(-1, 1) - y)
        out.append(y * vol * env(t, dur, release=dur * 0.8) * 3)
    return out


SOUNDS = {
    # skok: rychle stoupající tón
    "boing": lambda: tone(lambda t: 180 + 900 * t / 0.35, 0.35, square, 0.25),
    # šplouchnutí: šum s doznívající "kapkou"
    "splash": lambda: mix(noise(0.6, 0.6, 0.25, seed=3), tone(lambda t: 900 - 600 * t, 0.25, vol=0.2)),
    # pop: krátké lupnutí
    "pop": lambda: tone(lambda t: 600 - 400 * t / 0.12, 0.12, vol=0.7, release=0.1),
    # třpyt (mušle šumí): rychlé vysoké tóny
    "sparkle": lambda: mix(*[delay(tone(lambda t, f=f: f, 0.18, vol=0.25), i * 0.07)
                             for i, f in enumerate([1568, 2093, 1760, 2349, 2637])]),
    # cink: hvězdička za těžké slovo
    "cink": lambda: mix(tone(lambda t: 1760, 0.35, vol=0.35, release=0.3),
                        delay(tone(lambda t: 2637, 0.3, vol=0.25, release=0.28), 0.06)),
    # fanfára: konec stránky
    "fanfare": lambda: mix(*[delay(tone(lambda t, f=f: f, d, square, 0.18, release=d * 0.3), s)
                             for f, s, d in [(523, 0, 0.15), (659, 0.15, 0.15), (784, 0.3, 0.15), (1047, 0.45, 0.6)]]),
}


def write_wav(path: Path, samples: list[float]) -> None:
    peak = max(1e-9, max(abs(s) for s in samples))
    scale = 0.89 / peak  # sjednocená špička
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * scale)) * 32767)) for s in samples))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("--out", type=Path, required=True, help="výstupní složka")
    ap.add_argument("--only", nargs="*", help=f"jen vybrané: {', '.join(SOUNDS)}")
    args = ap.parse_args(argv)
    names = args.only or list(SOUNDS)
    unknown = [n for n in names if n not in SOUNDS]
    if unknown:
        print(f"Neznámé zvuky: {unknown}", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    for n in names:
        write_wav(args.out / f"{n}.wav", SOUNDS[n]())
        print(f"{args.out / (n + '.wav')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
