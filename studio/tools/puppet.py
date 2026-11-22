"""Loutkový systém: pomocné funkce pro kreslení dílů a skládání postavy.

Díly postavy jsou samostatné SVG soubory se stejným viewBoxem (souřadnice postavy).
Postava se skládá vrstvením dílů v pořadí z character.json:
  póza = ruce + nohy, výraz = oči + obočí + ústa, oblečení = trup + rukávy + obuv.

Tento modul se neimportuje v Čtečce, je jen pro Studio.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

OUTLINE = "#2B2B2B"
STROKE = 2.5

KIT_DIR = Path(__file__).resolve().parent.parent / "kit"


# ---------------------------------------------------------------- kreslení

def fmt(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".")


def pts(points) -> str:
    return " ".join(f"{fmt(x)},{fmt(y)}" for x, y in points)


def shape(d: str, fill: str, stroke: str = OUTLINE, width: float = STROKE) -> str:
    """Plochý tvar s obrysem."""
    s = f'stroke="{stroke}" stroke-width="{fmt(width)}" stroke-linejoin="round"' if stroke else 'stroke="none"'
    return f'<path d="{d}" fill="{fill}" {s}/>'


def ellipse(cx, cy, rx, ry, fill, stroke=OUTLINE, width=STROKE) -> str:
    s = f'stroke="{stroke}" stroke-width="{fmt(width)}"' if stroke else 'stroke="none"'
    return f'<ellipse cx="{fmt(cx)}" cy="{fmt(cy)}" rx="{fmt(rx)}" ry="{fmt(ry)}" fill="{fill}" {s}/>'


def line(d: str, color: str = OUTLINE, width: float = STROKE) -> str:
    """Čára bez výplně (ústa, obočí, řasy)."""
    return (f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{fmt(width)}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')


def polyline(points, color, width, cap="round") -> str:
    return (f'<polyline points="{pts(points)}" fill="none" stroke="{color}" stroke-width="{fmt(width)}" '
            f'stroke-linecap="{cap}" stroke-linejoin="round"/>')


def limb(points, fill, width, outline=OUTLINE, stroke=STROKE) -> list[str]:
    """Končetina jako silná čára s obrysem: [obrys, výplň]."""
    return [polyline(points, outline, width + 2 * stroke), polyline(points, fill, width)]


def partial(points, fraction):
    """Prvních `fraction` délky lomené čáry (pro rukáv nebo ponožku)."""
    import math
    segs = list(zip(points, points[1:]))
    total = sum(math.dist(a, b) for a, b in segs)
    want = total * fraction
    out = [points[0]]
    for a, b in segs:
        d = math.dist(a, b)
        if want <= d:
            t = want / d if d else 0
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            return out
        out.append(b)
        want -= d
    return out


def tail(points, fraction):
    """Posledních `fraction` délky lomené čáry."""
    rev = list(reversed(points))
    return list(reversed(partial(rev, fraction)))


def svg_doc(body: str, w: float, h: float, part_id: str = "") -> str:
    gid = f' id="{part_id}"' if part_id else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}" '
            f'width="{fmt(w)}" height="{fmt(h)}">\n<g{gid}>\n{body}\n</g>\n</svg>\n')


# ---------------------------------------------------------------- skládání

_INNER = re.compile(r"<svg[^>]*>(.*)</svg>", re.S)


def load_character(char_id: str, kit_dir: Path = KIT_DIR) -> dict:
    d = kit_dir / "characters" / char_id
    spec = json.loads((d / "character.json").read_text(encoding="utf-8"))
    spec["_dir"] = d
    return spec


def part_inner(spec: dict, name: str) -> str:
    path = spec["_dir"] / f"{name}.svg"
    m = _INNER.search(path.read_text(encoding="utf-8"))
    if not m:
        raise ValueError(f"{path} není platný díl")
    return m.group(1).strip()


def resolve_parts(spec: dict, pose: str, expression: str, outfit: str,
                  accessories: list[str] | None = None) -> list[str]:
    """Vrátí jména souborů dílů ve správném pořadí vrstev."""
    p = spec["poses"][pose]
    e = spec["expressions"][expression]
    o = spec["outfits"][outfit]
    chosen = {
        "hair_back": "hair_back",
        "legs": f"legs_{p['legs']}__{o['footwear']}",
        "torso": f"torso_{o['torso']}",
        "head": "head",
        "eyes": f"eyes_{e['eyes']}",
        "brows": f"brows_{e['brows']}",
        "mouth": f"mouth_{e['mouth']}",
        "hair_front": "hair_front",
        "arms": f"arms_{p['arms']}__{o['sleeve']}",
        "hat": None,
    }
    for acc in accessories or []:
        a = spec["accessories"][acc]
        chosen[a["slot"]] = a["part"]
    return [chosen[layer] for layer in spec["layerOrder"] if chosen.get(layer)]


def compose_character(spec: dict, pose: str = "stat", expression: str = "radost",
                      outfit: str = "vychozi", accessories: list[str] | None = None,
                      flip: bool = False) -> str:
    """Složí postavu do jednoho <g> v souřadnicích postavy (viewBox z character.json)."""
    w, _ = spec["viewBox"]
    body = "\n".join(part_inner(spec, n) for n in resolve_parts(spec, pose, expression, outfit, accessories))
    tr = f' transform="translate({fmt(w)} 0) scale(-1 1)"' if flip else ""
    return f'<g data-character="{spec["id"]}"{tr}>\n{body}\n</g>'


def character_svg(spec: dict, **kw) -> str:
    w, h = spec["viewBox"]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}">'
            f'{compose_character(spec, **kw)}</svg>')
