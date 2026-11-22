#!/usr/bin/env python3
"""Připraví obsah prototypu A do assets Čtečky.

Použití (venv kvůli resvg):
  studio/.venv/Scripts/python studio/tools/export_proto_a.py

Výstup do app/app/src/main/assets/:
  proto/pages.json                  5 čtecích stránek = prvních 5 panelů kapitol viky/01 a viky/02
  proto/<serie>-<NN>/chapter.json   kapitoly ve formátu balíčku (i s polem "asr")
  proto/<serie>-<NN>/<panel>.png    obrázek panelu bez bublin (text kreslí aplikace)
  sfx/*.wav                         syntetické zvuky (sfx_synth.py)
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import render_previews as rp  # noqa: E402
import sfx_synth  # noqa: E402

ROOT = TOOLS.parents[1]
ASSETS = ROOT / "app" / "app" / "src" / "main" / "assets"
CHAPTERS = [("viky", "01"), ("viky", "02")]
PAGES = 5


def main() -> int:
    import resvg_py
    proto = ASSETS / "proto"
    proto.mkdir(parents=True, exist_ok=True)
    pages = []
    for series, num in CHAPTERS:
        src = ROOT / "studio" / "chapters" / series / num
        chapter = json.loads((src / "chapter.json").read_text(encoding="utf-8"))
        dst = proto / f"{series}-{num}"
        dst.mkdir(exist_ok=True)
        shutil.copy(src / "chapter.json", dst / "chapter.json")
        chars = rp.load_chars(chapter)
        for page in chapter["pages"]:
            for panel in page["panels"]:
                svg = rp.panel_svg({**panel, "texts": []}, src, chars, False)
                (dst / f"{panel['id']}.png").write_bytes(bytes(resvg_py.svg_to_bytes(svg_string=svg, width=1200)))
                if len(pages) < PAGES:
                    pages.append({"chapter": f"{series}-{num}", "panel": panel["id"]})
    # rušivá slova pro režim "Stránka + 1000" (asr-bench: méně falešných posunů od cizí řeči)
    vocab = ROOT / "studio" / "asr" / "vosk-cs-words.txt"
    if vocab.exists():
        import random
        pool = [w for w in vocab.read_text(encoding="utf-8").split("\n") if 2 <= len(w) <= 7 and w.isalpha()]
        random.Random(42).shuffle(pool)
        (proto / "distractors.txt").write_text("\n".join(pool[:1000]) + "\n", encoding="utf-8")
    (proto / "pages.json").write_text(json.dumps({"pages": pages}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sfx_synth.main(["--out", str(ASSETS / "sfx")])
    print(f"{len(pages)} stránek → {proto}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
