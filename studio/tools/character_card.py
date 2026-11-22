#!/usr/bin/env python3
"""Vyrenderuje kartu postavy (PNG) ke schválení rodičem.

Použití:
  character_card.py viky [-o studio/kit/characters/viky/card.png]

Karta ukazuje výrazy, pózy a oblečení složené jen z dílů sady.
Popisky na kartě jsou jen pro rodiče (panely komiksu text nikdy neobsahují).
Vyžaduje balíček resvg-py (pip install -r studio/requirements.txt).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from puppet import compose_character, fmt, load_character  # noqa: E402

LABELS = {
    "radost": "radost", "smich": "smích", "prekvapeni": "překvapení", "smutek": "smutek",
    "premysleni": "přemýšlení", "strach": "lehký strach", "mluvi": "mluví", "spi": "spí",
    "stat": "stát", "jit": "jít", "sedet": "sedět", "skakat": "skákat", "ukazovat": "ukazovat",
    "mavat": "mávat", "drzet": "držet",
}
FONT = "Segoe UI, Arial, sans-serif"


def cell(spec, x, y, w, h, label, crop=None, **kw) -> str:
    vw, vh = spec["viewBox"]
    vb = crop or (0, 0, vw, vh)
    g = compose_character(spec, **kw)
    return (f'<svg x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" '
            f'viewBox="{" ".join(fmt(v) for v in vb)}" preserveAspectRatio="xMidYMax meet">{g}</svg>'
            f'<text x="{fmt(x + w / 2)}" y="{fmt(y + h + 26)}" font-family="{FONT}" font-size="20" '
            f'text-anchor="middle" fill="#444">{label}</text>')


def build_card(spec) -> tuple[str, int, int]:
    W = 1700
    out = []
    y = 20
    out.append(f'<text x="40" y="{y + 40}" font-family="{FONT}" font-size="40" font-weight="bold" fill="#2B2B2B">'
               f'{spec["name"]} – karta postavy</text>')
    y += 70

    def row(title, items, cw, ch, crop=None):
        nonlocal y
        out.append(f'<text x="40" y="{y + 26}" font-family="{FONT}" font-size="24" fill="#2B2B2B">{title}</text>')
        y += 40
        gap = (W - 80 - cw * len(items)) / max(len(items) - 1, 1)
        for i, (label, kw) in enumerate(items):
            out.append(cell(spec, 40 + i * (cw + gap), y, cw, ch, label, crop=crop, **kw))
        y += ch + 50

    row("Výrazy", [(LABELS[e], {"expression": e}) for e in spec["expressions"]], 180, 180, crop=(40, 0, 160, 160))
    row("Pózy", [(LABELS[p], {"pose": p, "expression": "smich" if p == "skakat" else "radost"})
                 for p in spec["poses"]], 190, 320)
    outfits = [("výchozí", {}), ("červená čepice", {"accessories": ["cepice_cervena"]}),
               ("modrá čepice", {"accessories": ["cepice_modra"], "pose": "drzet"}),
               ("plavky", {"outfit": "plavky", "pose": "skakat", "expression": "smich"}),
               ("zrcadlově", {"flip": True, "pose": "mavat", "accessories": ["cepice_cervena"]})]
    row("Oblečení a doplňky", outfits, 190, 320)
    marks = ", ".join(m["popis"] for m in spec["requiredMarks"])
    y += 10
    out.append(f'<text x="40" y="{y}" font-family="{FONT}" font-size="20" fill="#444">'
               f'Poznávací znaky (v každé póze): {marks}</text>')
    H = int(y + 30)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
           f'<rect width="{W}" height="{H}" fill="#FFFDF7"/>' + "".join(out) + "</svg>")
    return svg, W, H


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("character", help="id postavy (složka v studio/kit/characters/)")
    ap.add_argument("-o", "--output", type=Path, help="výstupní PNG (výchozí: card.png ve složce postavy)")
    ap.add_argument("--svg", action="store_true", help="uložit i SVG karty")
    args = ap.parse_args(argv)
    try:
        import resvg_py
    except ImportError:
        print("Chybí resvg-py: pip install -r studio/requirements.txt", file=sys.stderr)
        return 2
    try:
        spec = load_character(args.character)
    except (OSError, KeyError) as e:
        print(f"Postava nenalezena: {e}", file=sys.stderr)
        return 2
    svg, _, _ = build_card(spec)
    out = args.output or spec["_dir"] / "card.png"
    out.write_bytes(bytes(resvg_py.svg_to_bytes(svg_string=svg)))
    if args.svg:
        out.with_suffix(".svg").write_text(svg, encoding="utf-8")
    print(f"Karta: {out}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
