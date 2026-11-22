#!/usr/bin/env python3
"""Kontrola textu pro začínající čtenářku podle studio/reader-profile.json.

Použití:
  check_text.py --text "VIKY MÁ KOČKU. JE V LESE."   rychlá kontrola volného textu
  check_text.py studio/chapters/viky/01/chapter.json [--keywords VIKY KOLO]
  check_text.py studio/chapters/viky/01/chapter.json --annotate

Těžká (tréninková) slova se nezakazují, ale označují a dávkují:
  long-syllable  slabika delší než practice.easySyllableMaxLetters (KO-ČKA, DŮM)
  no-vowel       slabika bez samohlásky (VLK, předložky V, S, K, Z)
  focus          slovo nebo slabika ze seznamu practice.focus (potíže z tabletu)

Kontroluje:
  - jen písmena z knownLetters, nejvýš maxSyllablesPerWord slabik ve slově,
  - nejvýš maxWordsPerSentence slov a practice.maxPerSentence těžkých slov ve větě,
  - aspoň practice.minPerChapter těžkých slov v kapitole,
  - v kapitole: nejvýš maxBubblesPerPanel bublin a maxWordsPerPanel slov na panel,
    pole "syl" po spojení dává slovo, slabika má nejvýš jedno samohláskové jádro,
    pole "practice" u slova odpovídá výpočtu (--annotate ho doplní),
  - klíčová slova (--keywords nebo "keywords" ve scene.json vedle kapitoly) aspoň 3×,
  - položky practice.focus, které v kapitole chybí (varování).

Dělení slabik je jako ve slabikáři: souhlásky mezi samohláskami patří
k následující slabice (MA-MI-NKA), koncové souhlásky k poslední slabice.
CH je jedno písmeno.

Návratový kód: 0 = bez chyb (varování nevadí), 1 = chyby v textu, 2 = chybný vstup.
"""
from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_PROFILE = Path(__file__).resolve().parent.parent / "reader-profile.json"

VOWELS = set("AÁEÉĚIÍOÓUÚŮYÝ")
DIPHTHONGS = {("A", "U"), ("O", "U"), ("E", "U")}
PUNCTUATION = ".,!?…:;\"„“”‚‘’'()[]–—-«»"
SENTENCE_END = (".", "!", "?", "…")
KEYWORD_MIN_COUNT = 3
CATEGORY_ORDER = ["long-syllable", "no-vowel", "focus"]


# ---------------------------------------------------------------- písmena a slabiky

def strip_punct(token: str) -> str:
    return token.strip(PUNCTUATION)


def letters(word: str) -> list[str]:
    """Rozdělí slovo na písmena české abecedy (CH je jedno písmeno)."""
    up = unicodedata.normalize("NFC", word.upper())
    out: list[str] = []
    i = 0
    while i < len(up):
        if up[i] == "C" and i + 1 < len(up) and up[i + 1] == "H":
            out.append("CH")
            i += 2
        else:
            out.append(up[i])
            i += 1
    return out


def nuclei(ls: list[str]) -> list[tuple[int, int]]:
    """Vrátí rozsahy [start, end) samohláskových jader (dvojhláska AU/OU/EU = jedno jádro)."""
    res: list[tuple[int, int]] = []
    i = 0
    while i < len(ls):
        if ls[i] in VOWELS:
            if i + 1 < len(ls) and (ls[i], ls[i + 1]) in DIPHTHONGS:
                res.append((i, i + 2))
                i += 2
                continue
            res.append((i, i + 1))
        i += 1
    return res


def syllabify(word: str) -> list[str]:
    """Rozdělí slovo na slabiky jako ve slabikáři (otevřené slabiky)."""
    ls = letters(strip_punct(word))
    if not ls:
        return []
    nuc = nuclei(ls)
    if not nuc:
        return ["".join(ls)]
    # souhlásky mezi jádry jdou k následující slabice -> řez hned za předchozím jádrem
    cuts = [nuc[k][1] for k in range(len(nuc) - 1)]
    sylls: list[str] = []
    prev = 0
    for c in cuts:
        sylls.append("".join(ls[prev:c]))
        prev = c
    sylls.append("".join(ls[prev:]))
    return sylls


def practice_categories(word: str, syl: list[str], profile: dict) -> list[str]:
    """Proč je slovo tréninkové (prázdný seznam = snadné slovo)."""
    pr = profile.get("practice", {})
    max_easy = pr.get("easySyllableMaxLetters", 2)
    focus = {strip_punct(f).upper() for f in pr.get("focus", [])}
    cats: set[str] = set()
    for s in syl:
        sl = letters(s)
        if len(sl) > max_easy:
            cats.add("long-syllable")
        if not nuclei(sl):
            cats.add("no-vowel")
    up_syl = {s.upper() for s in syl}
    if strip_punct(word).upper() in focus or up_syl & focus:
        cats.add("focus")
    return [c for c in CATEGORY_ORDER if c in cats]


# ---------------------------------------------------------------- výsledky

@dataclass
class Issue:
    level: str  # "error" | "warning"
    where: str
    message: str

    def __str__(self) -> str:
        tag = "CHYBA" if self.level == "error" else "varování"
        return f"[{tag}] {self.where}: {self.message}"


@dataclass
class Report:
    issues: list[Issue] = field(default_factory=list)
    words: list[dict] = field(default_factory=list)

    def error(self, where: str, msg: str) -> None:
        self.issues.append(Issue("error", where, msg))

    def warn(self, where: str, msg: str) -> None:
        self.issues.append(Issue("warning", where, msg))

    @property
    def errors(self) -> list[Issue]:
        return [i for i in self.issues if i.level == "error"]

    @property
    def practice_words(self) -> list[dict]:
        return [w for w in self.words if w["practice"]]


# ---------------------------------------------------------------- kontroly

def load_profile(path: Path) -> dict:
    with open(path, encoding="utf-8") as f:
        p = json.load(f)
    p["_known"] = set(letters(p["knownLetters"]))
    return p


def check_word(token: str, syl: list[str] | None, profile: dict, where: str, rep: Report) -> dict | None:
    """Zkontroluje slovo, přidá ho do rep.words a vrátí jeho záznam."""
    word = strip_punct(token)
    if not word:
        rep.error(where, f"„{token}“ neobsahuje žádné písmeno")
        return None
    ls = letters(word)
    unknown = sorted({l for l in ls if l not in profile["_known"]})
    if unknown:
        rep.error(where, f"„{word}“ obsahuje neznámá písmena: {' '.join(unknown)}")

    auto = syllabify(word)
    if syl is None:
        syl = auto
    else:
        if letters("".join(syl)) != ls:
            rep.error(where, f"slabiky {'-'.join(syl)} nedávají slovo „{word}“")
        elif [s.upper() for s in syl] != auto:
            rep.warn(where, f"„{word}“ je děleno {'-'.join(syl)}, slabikář dělí {'-'.join(auto)}")

    if len(syl) > profile["maxSyllablesPerWord"]:
        rep.error(where, f"„{word}“ má {len(syl)} slabik (max {profile['maxSyllablesPerWord']})")
    for s in syl:
        if len(nuclei(letters(s))) > 1:
            rep.error(where, f"„{word}“: slabika {s.upper()} má víc samohláskových jader – špatné dělení")

    entry = {"word": word, "syl": syl, "practice": practice_categories(word, syl, profile)}
    rep.words.append(entry)
    return entry


def split_sentences(items: list) -> list[list]:
    """Rozdělí posloupnost (token, cokoli) na věty podle koncové interpunkce tokenu."""
    sentences: list[list] = []
    cur: list = []
    for item in items:
        cur.append(item)
        if item[0].rstrip("\"“”’'»)").endswith(SENTENCE_END):
            sentences.append(cur)
            cur = []
    if cur:
        sentences.append(cur)
    return sentences


def check_sentences(items: list[tuple[str, dict | None]], profile: dict, where: str, rep: Report) -> None:
    max_words = profile["maxWordsPerSentence"]
    max_practice = profile.get("practice", {}).get("maxPerSentence")
    for s in split_sentences(items):
        text = " ".join(t for t, _ in s)
        if len(s) > max_words:
            rep.error(where, f"věta „{text}“ má {len(s)} slov (max {max_words})")
        hard = sum(1 for _, e in s if e and e["practice"])
        if max_practice is not None and hard > max_practice:
            rep.error(where, f"věta „{text}“ má {hard} těžká slova (max {max_practice})")


def check_totals(rep: Report, profile: dict, keywords: list[str], chapter_mode: bool) -> None:
    norm = [w["word"].upper() for w in rep.words]
    for k in keywords:
        n = norm.count(strip_punct(k).upper())
        if n < KEYWORD_MIN_COUNT:
            rep.error("klíčová slova", f"„{k.upper()}“ je v textu {n}× (min {KEYWORD_MIN_COUNT})")
    if not chapter_mode:
        return
    pr = profile.get("practice", {})
    n_hard = len(rep.practice_words)
    if n_hard < pr.get("minPerChapter", 0):
        rep.error("trénink", f"kapitola má {n_hard} těžkých slov (min {pr['minPerChapter']})")
    all_syl = {s.upper() for w in rep.words for s in w["syl"]}
    for f in pr.get("focus", []):
        f_up = strip_punct(f).upper()
        if f_up not in norm and f_up not in all_syl:
            rep.warn("trénink", f"problémové „{f_up}“ z practice.focus se v kapitole neobjevuje")


def check_plain_text(text: str, profile: dict, keywords: list[str]) -> Report:
    rep = Report()
    tokens = text.split()
    items = [(t, check_word(t, None, profile, f"slovo {i}", rep)) for i, t in enumerate(tokens, 1)]
    check_sentences(items, profile, "text", rep)
    check_totals(rep, profile, keywords, chapter_mode=False)
    return rep


def iter_text_blocks(chapter: dict):
    """Vrací (místo, seznam slov jako dicty) pro název a všechny texty kapitoly."""
    for pi, page in enumerate(chapter.get("pages", []), 1):
        for panel in page.get("panels", []):
            pid = f"str. {pi} / {panel.get('id', '?')}"
            for t in panel.get("texts", []):
                yield f"{pid} / {t.get('id', '?')}", t.get("words", [])


def check_chapter(chapter: dict, profile: dict, keywords: list[str], annotate: bool = False) -> Report:
    """Zkontroluje kapitolu. Při annotate=True doplní/opraví pole "practice" u slov přímo v dictu."""
    rep = Report()
    stage = chapter.get("readerLevel", {}).get("fontStage")
    if stage and stage != profile.get("fontStage"):
        rep.warn("kapitola", f"fontStage kapitoly {stage} ≠ profil {profile.get('fontStage')}")

    if chapter.get("title"):
        tokens = chapter["title"].split()
        title_items = [(t, check_word(t, None, profile, "název", rep)) for t in tokens]
        check_sentences(title_items, profile, "název", rep)

    for pi, page in enumerate(chapter.get("pages", []), 1):
        for panel in page.get("panels", []):
            pid = f"str. {pi} / {panel.get('id', '?')}"
            texts = panel.get("texts", [])
            max_b = profile.get("maxBubblesPerPanel", 2)
            bubbles = sum(1 for t in texts if t.get("kind") == "bubble")
            if bubbles > max_b:
                rep.error(pid, f"{bubbles} bubliny (max {max_b})")
            n_words = sum(len(t.get("words", [])) for t in texts)
            if n_words > profile["maxWordsPerPanel"]:
                rep.error(pid, f"{n_words} slov (max {profile['maxWordsPerPanel']})")

    for where, words in iter_text_blocks(chapter):
        items = []
        for w in words:
            entry = check_word(w.get("w", ""), w.get("syl"), profile, where, rep)
            items.append((w.get("w", ""), entry))
            if entry is None:
                continue
            expected = entry["practice"]
            if annotate:
                if expected:
                    w["practice"] = expected
                else:
                    w.pop("practice", None)
            elif sorted(w.get("practice", [])) != sorted(expected):
                rep.error(where, f"„{entry['word']}“ má practice {w.get('practice', [])}, "
                                 f"má být {expected} (oprav přes --annotate)")
        check_sentences(items, profile, where, rep)

    check_totals(rep, profile, keywords, chapter_mode=True)
    return rep


# ---------------------------------------------------------------- CLI

def format_word(w: dict) -> str:
    s = "-".join(w["syl"])
    return f"{s}*" if w["practice"] else s


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("chapter", nargs="?", type=Path, help="chapter.json ke kontrole")
    ap.add_argument("--text", help="volný text ke kontrole (místo chapter.json)")
    ap.add_argument("--profile", type=Path, default=DEFAULT_PROFILE, help="reader-profile.json")
    ap.add_argument("--keywords", nargs="*", default=None, help="klíčová slova kapitoly (každé aspoň 3×)")
    ap.add_argument("--annotate", action="store_true",
                    help="doplnit pole practice u slov přímo do chapter.json")
    ap.add_argument("--json", action="store_true", help="výstup jako JSON")
    args = ap.parse_args(argv)

    if (args.chapter is None) == (args.text is None):
        ap.error("zadej buď cestu k chapter.json, nebo --text")
    if args.annotate and args.chapter is None:
        ap.error("--annotate jde jen s chapter.json")
    try:
        profile = load_profile(args.profile)
        keywords = args.keywords or []
        if args.text is not None:
            rep = check_plain_text(args.text, profile, keywords)
        else:
            chapter = json.loads(args.chapter.read_text(encoding="utf-8"))
            if args.keywords is None:
                scene = args.chapter.with_name("scene.json")
                if scene.exists():
                    keywords = json.loads(scene.read_text(encoding="utf-8")).get("keywords", [])
            rep = check_chapter(chapter, profile, keywords, annotate=args.annotate)
            if args.annotate:
                args.chapter.write_text(json.dumps(chapter, ensure_ascii=False, indent=2) + "\n",
                                        encoding="utf-8")
    except (OSError, json.JSONDecodeError, KeyError) as e:
        print(f"Chybný vstup: {e}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps({"ok": not rep.errors,
                          "words": rep.words,
                          "practiceCount": len(rep.practice_words),
                          "issues": [i.__dict__ for i in rep.issues]}, ensure_ascii=False, indent=2))
    else:
        if args.text is not None:
            print(" ".join(format_word(w) for w in rep.words))
        hard = rep.practice_words
        if hard:
            print("Těžká slova: " + ", ".join(f"{w['word']} ({'/'.join(w['practice'])})" for w in hard))
        for i in rep.issues:
            print(i)
        n_err, n_warn = len(rep.errors), len(rep.issues) - len(rep.errors)
        print(f"{'OK' if not n_err else 'NEPROŠLO'}: {len(rep.words)} slov, {len(hard)} těžkých, "
              f"{n_err} chyb, {n_warn} varování")
    return 1 if rep.errors else 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
