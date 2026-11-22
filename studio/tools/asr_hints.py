#!/usr/bin/env python3
"""Nápověda pro rozpoznávač: slova, která model Vosk nezná, dostanou náhradní tokeny.

Vosk v režimu gramatiky pozná jen slova ze svého slovníku (studio/asr/vosk-cs-words.txt).
Pro slovo mimo slovník se hledají podobně znějící slova ze slovníku (např. VIKY → vika, cviky)
a zapíšou se do chapter.json jako  "asr": {"oov": true, "alt": ["vika", "cviky"]}.
Čtečka pak náhradu bere jako přečtení slova; u "oov" přijme i token [unk].

Použití (obvykle volá compose.py automaticky, když slovník existuje):
  asr_hints.py studio/chapters/viky/01/chapter.json
"""
from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from functools import lru_cache
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_text  # noqa: E402

VOCAB = Path(__file__).resolve().parents[1] / "asr" / "vosk-cs-words.txt"
MAX_ALT = 3


def sound_key(s: str) -> str:
    """Zjednodušená výslovnost: bez diakritiky, y = i."""
    base = "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")
    return base.replace("y", "i")


VOWELS = set("aeiou")
SUB_VOWEL = 0.4      # záměna samohlásky za samohlásku zní podobně
INDEL = 0.7          # přidaná/chybějící hláska
MAX_COST = 0.8       # záměna souhlásky (1.0) už se nepřipouští


def lev(a: str, b: str) -> float:
    """Vážená editační vzdálenost podle podobnosti výslovnosti."""
    prev = [j * INDEL for j in range(len(b) + 1)]
    for i, ca in enumerate(a, 1):
        cur = [i * INDEL]
        for j, cb in enumerate(b, 1):
            if ca == cb:
                sub = 0.0
            elif ca in VOWELS and cb in VOWELS:
                sub = SUB_VOWEL
            else:
                sub = 1.0
            cur.append(min(prev[j] + INDEL, cur[j - 1] + INDEL, prev[j - 1] + sub))
        prev = cur
    return prev[-1]


@lru_cache(maxsize=1)
def load_vocab(path: Path = VOCAB) -> tuple[frozenset[str], dict[int, list[tuple[str, str]]]]:
    words = [w for w in path.read_text(encoding="utf-8").split("\n") if w and w[0].isalpha()]
    by_len: dict[int, list[tuple[str, str]]] = {}
    for w in words:
        by_len.setdefault(len(w), []).append((w, sound_key(w)))
    return frozenset(words), by_len


def alternatives(word: str, path: Path = VOCAB) -> list[str]:
    vocab, by_len = load_vocab(path)
    key = sound_key(word)
    scored = []
    for n in (len(word), len(word) - 1, len(word) + 1):
        for cand, ck in by_len.get(n, []):
            d = lev(key, ck)
            if d <= MAX_COST:
                # přednost: stejná výslovnost, stejná délka, stejné první písmeno
                scored.append((d, n != len(word), cand[0] != word[0], cand))
    return [c for *_, c in sorted(scored)[:MAX_ALT]]


def annotate(chapter: dict, path: Path = VOCAB) -> list[str]:
    """Doplní/odstraní pole "asr" u slov. Vrátí slova mimo slovník."""
    vocab, _ = load_vocab(path)
    oov = []
    for page in chapter["pages"]:
        for panel in page["panels"]:
            for t in panel["texts"]:
                for w in t["words"]:
                    low = check_text.strip_punct(w["w"]).lower()
                    if low in vocab:
                        w.pop("asr", None)
                        continue
                    oov.append(low)
                    w["asr"] = {"oov": True, "alt": alternatives(low, path)}
    return oov


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("chapter", type=Path)
    args = ap.parse_args(argv)
    if not VOCAB.exists():
        print(f"Chybí slovník {VOCAB} (vytvoří vosk_model.py vocab)", file=sys.stderr)
        return 2
    chapter = json.loads(args.chapter.read_text(encoding="utf-8"))
    oov = annotate(chapter)
    args.chapter.write_text(json.dumps(chapter, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Mimo slovník: {len(oov)} – {', '.join(sorted(set(oov)))}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
