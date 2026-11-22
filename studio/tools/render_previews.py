#!/usr/bin/env python3
"""PNG náhledy kapitoly: celé stránky i s bublinami, jak je zhruba vykreslí Čtečka.

Použití:
  render_previews.py studio/chapters/viky/01/chapter.json [--practice]

Výstup:
  <kapitola>/previews/page-<N>.png   náhled stránky (v .gitignore)
  <kapitola>/previews/<panel>.png    jednotlivé panely
  <kapitola>/panels/cover.webp       obálka z prvního panelu (jde do balíčku)

Bubliny a písmo jsou jen přibližné: v Čtečce text kreslí aplikace (Andika).
--practice podtrhne těžká slova (pro kontrolu rodičem, Čtečka je nepodtrhává).
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from puppet import OUTLINE, fmt  # noqa: E402

PW, PH = 800, 600
FONT = "Andika, Comic Sans MS, Segoe UI, sans-serif"
FONT_SIZE = 30
CHAR_W = 0.64          # přibližná šířka znaku velkých písmen v poměru k velikosti písma
LINE_H = 1.25
MAX_TEXT_W = 330
PAD = 16
GUTTER = 24


def inner_of(path: Path) -> tuple[str, str]:
    text = path.read_text(encoding="utf-8")
    vb = re.search(r'viewBox="([^"]+)"', text).group(1)
    return re.search(r"<svg[^>]*>(.*)</svg>", text, re.S).group(1), vb


def wrap(words: list[dict]) -> list[list[dict]]:
    lines, cur, cur_w = [], [], 0.0
    for w in words:
        ww = len(w["w"]) * FONT_SIZE * CHAR_W
        space = FONT_SIZE * CHAR_W * 0.5 if cur else 0
        if cur and cur_w + space + ww > MAX_TEXT_W:
            lines.append(cur)
            cur, cur_w = [], 0.0
            space = 0
        cur.append(w)
        cur_w += space + ww
    if cur:
        lines.append(cur)
    return lines


def line_width(line: list[dict]) -> float:
    return sum(len(w["w"]) for w in line) * FONT_SIZE * CHAR_W + (len(line) - 1) * FONT_SIZE * CHAR_W * 0.5


def text_block(lines, x0, y0, practice: bool, center_x=None) -> str:
    out = []
    for i, ln in enumerate(lines):
        lw = line_width(ln)
        x = (center_x - lw / 2) if center_x is not None else x0
        y = y0 + (i + 0.8) * FONT_SIZE * LINE_H
        spans = []
        cx = x
        for w in ln:
            ww = len(w["w"]) * FONT_SIZE * CHAR_W
            spans.append(f'<text x="{fmt(cx)}" y="{fmt(y)}" font-family="{FONT}" font-size="{FONT_SIZE}" '
                         f'textLength="{fmt(ww)}" lengthAdjust="spacingAndGlyphs" fill="{OUTLINE}">'
                         f'{escape(w["w"])}</text>')
            if practice and w.get("practice"):
                spans.append(f'<line x1="{fmt(cx)}" y1="{fmt(y + 5)}" x2="{fmt(cx + ww)}" y2="{fmt(y + 5)}" '
                             f'stroke="#E0457B" stroke-width="3"/>')
            cx += ww + FONT_SIZE * CHAR_W * 0.5
        out += spans
    return "".join(out)


TAIL_GAP = 14      # mezera mezi špičkou ocasu a hlavou (px panelu)
TAIL_MAX = 95
TAIL_MIN = 32      # ocas je vždy (nikdy se nevynechá) a aspoň takhle dlouhý


def speaker_head(panel: dict, speaker: str, chars: dict) -> tuple[float, float, float] | None:
    """Střed hlavy mluvčího a její poloměr i s vlasy, v px panelu."""
    for layer in panel["image"]["layers"]:
        if layer["id"] == speaker and "box" in layer:
            x1, y1, x2, y2 = layer["box"]
            spec = chars.get(speaker)
            ax, ay = (spec["anchors"]["head"] if spec else (120, 100))
            vw, vh = spec["viewBox"] if spec else (240, 400)
            radius = spec.get("headRadius", 84) if spec else 84
            scale = (x2 - x1) * PW / vw
            return ((x1 + (x2 - x1) * ax / vw) * PW, (y1 + (y2 - y1) * ay / vh) * PH, radius * scale)
    return None


def bubble(t: dict, panel: dict, chars: dict, practice: bool) -> str:
    lines = wrap(t["words"])
    tw = max(line_width(l) for l in lines)
    th = len(lines) * FONT_SIZE * LINE_H
    bw, bh = tw + 2 * PAD + 10, th + 2 * PAD
    if t["kind"] == "narration":
        x, y = 14, 14
        return (f'<rect x="{x}" y="{y}" width="{fmt(bw)}" height="{fmt(bh)}" rx="6" fill="#FFF6D6" '
                f'stroke="{OUTLINE}" stroke-width="2.5"/>' + text_block(lines, x + PAD + 5, y + PAD, practice))
    cx, cy = t["anchor"][0] * PW, t["anchor"][1] * PH
    cx = min(max(cx, bw / 2 + 10), PW - bw / 2 - 10)
    cy = max(cy, bh / 2 + 10)
    rect = (f'<rect x="{{x}}" y="{{y}}" width="{fmt(bw)}" height="{fmt(bh)}" rx="{fmt(min(bh / 2, 30))}" '
            f'fill="#FFFFFF" stroke="{OUTLINE}" stroke-width="2.5"/>')
    head = speaker_head(panel, t.get("speaker", ""), chars)
    if not head:
        return rect.format(x=fmt(cx - bw / 2), y=fmt(cy - bh / 2)) + text_block(lines, 0, cy - bh / 2 + PAD, practice, center_x=cx)

    cx, cy = place_bubble(cx, cy, bw, bh, head)
    x, y = cx - bw / 2, cy - bh / 2
    bx, by, tipx, tipy = tail_geometry(x, y, bw, bh, head)
    base = 14
    tri = f"M{fmt(bx - base)} {fmt(by)} L{fmt(tipx)} {fmt(tipy)} L{fmt(bx + base)} {fmt(by)} Z"
    return "".join([
        f'<path d="{tri}" fill="#FFFFFF" stroke="{OUTLINE}" stroke-width="2.5" stroke-linejoin="round"/>',
        rect.format(x=fmt(x), y=fmt(y)),
        f'<path d="M{fmt(bx - base + 3)} {fmt(by - 3)} L{fmt(bx + base - 3)} {fmt(by - 3)} '
        f'L{fmt(bx)} {fmt(by + 6)} Z" fill="#FFFFFF"/>',
        text_block(lines, 0, y + PAD, practice, center_x=cx),
    ])


def tail_geometry(x, y, bw, bh, head):
    """Ocas vždy existuje: začíná na spodním okraji bubliny, míří ke středu hlavy, končí před ní."""
    hx, hy, hr = head
    bx, by = min(max(hx, x + 34), x + bw - 34), y + bh - 4
    dx, dy = hx - bx, hy - by
    dist = max((dx * dx + dy * dy) ** 0.5, 1)
    length = min(max(dist - hr - TAIL_GAP, 8), TAIL_MAX)
    return bx, by, bx + dx / dist * length, by + dy / dist * length


def bubble_fits(cx, cy, bw, bh, head) -> bool:
    """Bublina nezakrývá hlavu a mezi ní a hlavou je místo na ocas aspoň TAIL_MIN."""
    hx, hy, hr = head
    near_x = min(max(hx, cx - bw / 2), cx + bw / 2)
    near_y = min(max(hy, cy - bh / 2), cy + bh / 2)
    if ((near_x - hx) ** 2 + (near_y - hy) ** 2) ** 0.5 < hr + TAIL_GAP:
        return False
    bx, by, tipx, tipy = tail_geometry(cx - bw / 2, cy - bh / 2, bw, bh, head)
    return ((tipx - bx) ** 2 + (tipy - by) ** 2) ** 0.5 >= TAIL_MIN


def place_bubble(cx, cy, bw, bh, head) -> tuple[float, float]:
    """Najde nejbližší pozici bubliny, kde nezakrývá hlavu a vejde se ocas.

    Zkouší posun stranou od hlavy, pak nahoru, pak na druhou stranu.
    """
    if bubble_fits(cx, cy, bw, bh, head):
        return cx, cy
    hx = head[0]
    min_x, max_x = bw / 2 + 10, PW - bw / 2 - 10
    min_y = bh / 2 + 8
    away = 1 if cx >= hx else -1
    candidates = []
    for d in range(4, PW, 4):
        for sx, sy in ((away * d, 0), (0, -d), (away * d, -d), (-away * d, 0), (-away * d, -d)):
            nx, ny = cx + sx, cy + sy
            if min_x <= nx <= max_x and ny >= min_y:
                candidates.append((abs(sx) + abs(sy), nx, ny))
        if candidates:
            for _, nx, ny in sorted(candidates):
                if bubble_fits(nx, ny, bw, bh, head):
                    return nx, ny
            candidates.clear()
    return cx, cy


def panel_svg(panel: dict, chapter_dir: Path, chars: dict, practice: bool) -> str:
    out = []
    for layer in panel["image"]["layers"]:
        inner, vb = inner_of(chapter_dir / layer["file"])
        box = layer.get("box", [0, 0, 1, 1])
        x, y = box[0] * PW, box[1] * PH
        w, h = (box[2] - box[0]) * PW, (box[3] - box[1]) * PH
        out.append(f'<svg x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" viewBox="{vb}" '
                   f'preserveAspectRatio="none" overflow="visible">{inner}</svg>')
    for t in panel["texts"]:
        out.append(bubble(t, panel, chars, practice))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {PW} {PH}" width="{PW}" height="{PH}">'
            f'<defs><clipPath id="c"><rect width="{PW}" height="{PH}"/></clipPath></defs>'
            f'<g clip-path="url(#c)">{"".join(out)}</g>'
            f'<rect x="1.5" y="1.5" width="{PW - 3}" height="{PH - 3}" fill="none" stroke="{OUTLINE}" stroke-width="3"/></svg>')


def page_layout(n: int) -> list[tuple[float, float]]:
    if n <= 2:
        return [(GUTTER + i * (PW + GUTTER), GUTTER) for i in range(n)]
    pos = []
    for i in range(n):
        row, col = divmod(i, 2)
        in_row = min(2, n - row * 2)
        offset = (2 - in_row) * (PW + GUTTER) / 2
        pos.append((GUTTER + offset + col * (PW + GUTTER), GUTTER + row * (PH + GUTTER)))
    return pos


def load_chars(chapter: dict) -> dict:
    from puppet import load_character
    ids = {l["id"] for p in chapter["pages"] for pn in p["panels"] for l in pn["image"]["layers"]}
    chars = {}
    for i in ids:
        try:
            chars[i] = load_character(i)
        except OSError:
            pass
    return chars


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("chapter", type=Path, help="chapter.json")
    ap.add_argument("--practice", action="store_true", help="podtrhnout těžká slova")
    args = ap.parse_args(argv)
    try:
        import resvg_py
        from PIL import Image
    except ImportError as e:
        print(f"Chybí knihovna ({e.name}): pip install -r studio/requirements.txt", file=sys.stderr)
        return 2
    try:
        chapter = json.loads(args.chapter.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        print(f"Chybný vstup: {e}", file=sys.stderr)
        return 2
    cdir = args.chapter.parent
    prev = cdir / "previews"
    prev.mkdir(exist_ok=True)
    chars = load_chars(chapter)

    def render(svg: str) -> bytes:
        return bytes(resvg_py.svg_to_bytes(svg_string=svg))

    first = True
    for pi, page in enumerate(chapter["pages"], 1):
        svgs = []
        for panel in page["panels"]:
            s = panel_svg(panel, cdir, chars, args.practice)
            (prev / f"{panel['id']}.png").write_bytes(render(s))
            if first:
                clean = panel_svg({**panel, "texts": []}, cdir, chars, False)
                Image.open(io.BytesIO(render(clean))).convert("RGB").save(cdir / "panels" / "cover.webp", quality=85)
                first = False
            svgs.append(s)
        pos = page_layout(len(svgs))
        W = int(max(x for x, _ in pos) + PW + GUTTER)
        H = int(max(y for _, y in pos) + PH + GUTTER)
        body = "".join(f'<g transform="translate({fmt(x)} {fmt(y)})">{s}</g>' for (x, y), s in zip(pos, svgs))
        page_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
                    f'<rect width="{W}" height="{H}" fill="#FFFDF7"/>{body}</svg>')
        out = prev / f"page-{pi}.png"
        out.write_bytes(render(page_svg))
        print(f"Stránka {pi}: {out}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
