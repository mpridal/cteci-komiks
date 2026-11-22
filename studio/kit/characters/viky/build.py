"""Generátor dílů postavy Viky. Spuštění: python studio/kit/characters/viky/build.py

Vytvoří SVG díly a character.json v této složce. Díly se needitují ručně:
změna = úprava tohoto skriptu a nové spuštění (ať jsou všechny díly konzistentní).

Souřadnice postavy: viewBox 0 0 240 400, chodidla na y ≈ 392, hlava ≈ třetina výšky.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "tools"))

from puppet import (OUTLINE, ellipse, limb, line, partial, polyline, shape,  # noqa: E402
                    svg_doc, tail)

W, H = 240, 400

PAL = {
    "outline": OUTLINE,
    "skin": "#EDBE98",
    "cheek": "#F2A39C",
    "hair": "#3A2820",
    "iris": "#5A3420",
    "pupil": "#1E1410",
    "white": "#FFFFFF",
    "mouth": "#A8424A",
    "tongue": "#E57F86",
    "earring": "#F2D16B",
    "tshirt": "#F3EBD8",
    "tshirt_print": "#C9A88A",
    "shorts": "#F4A9C4",
    "socks": "#FFFFFF",
    "shoes": "#F6C8D8",
    "swimsuit": "#F7B8DA",
    "swim_flower": "#EE7FB0",
    "cap_red": "#D93A33",
    "cap_blue": "#2F4F9A",
}

parts: dict[str, str] = {}


def part(name: str, *elements: str) -> None:
    parts[name] = "\n".join(elements)


# ---------------------------------------------------------------- vlasy

part("hair_back", shape(
    "M120 28 C58 28 46 80 48 140 C49 168 54 188 62 200 Q72 206 82 200 Q92 207 104 201 "
    "Q116 207 128 201 Q140 207 152 201 Q164 207 176 200 C186 188 191 168 192 140 C194 80 182 28 120 28 Z",
    PAL["hair"]))

# ofina (rovná, lehce zubatá) + prameny kolem obličeje – poznávací znak
part("hair_front", shape(
    "M58 182 C48 150 44 110 50 74 C56 42 84 24 120 24 C156 24 184 42 190 74 "
    "C196 110 192 150 182 182 Q176 178 172 172 C174 140 175 112 170 86 "
    "L162 82 L156 77 L148 83 L140 77 L132 83 L124 77 L116 83 L108 77 L100 83 L92 77 L84 83 L76 80 L70 86 "
    "C65 112 66 140 68 172 Q64 178 58 182 Z",
    PAL["hair"]),
    line("M150 40 Q160 50 164 64", "#5A4034", 2.5),  # lesk vlasů jako čára
    # uši s náušnicemi přes prameny (vlasy za ušima) – poznávací znak
    ellipse(66, 112, 8, 12, PAL["skin"]),
    ellipse(174, 112, 8, 12, PAL["skin"]),
    ellipse(65, 127, 3.4, 3.4, PAL["earring"]),
    ellipse(175, 127, 3.4, 3.4, PAL["earring"]),
)

# ---------------------------------------------------------------- hlava

part("head",
     shape("M120 40 C152 40 176 62 176 100 C176 136 154 162 120 162 C86 162 64 136 64 100 C64 62 88 40 120 40 Z",
           PAL["skin"]),
     ellipse(86, 132, 9, 6, PAL["cheek"], stroke=None),
     ellipse(154, 132, 9, 6, PAL["cheek"], stroke=None),
     line("M117 123 Q121 128 125 123", OUTLINE, 2),
     )

# ---------------------------------------------------------------- oči

EYES = [(98, 110), (142, 110)]


def eye_open(cx, cy, dx=0, dy=0, rx=11, ry=13, iris=8, side=1):
    return [
        ellipse(cx, cy, rx, ry, PAL["white"]),
        ellipse(cx + dx, cy + 2 + dy, iris, iris, PAL["iris"], stroke=None),
        ellipse(cx + dx, cy + 2 + dy, iris * 0.55, iris * 0.55, PAL["pupil"], stroke=None),
        ellipse(cx + dx + 3, cy - 2 + dy, 2.6, 2.6, PAL["white"], stroke=None),
        line(f"M{cx + side * 10} {cy - 8} L{cx + side * 15} {cy - 12}", OUTLINE, 2.5),  # řasa
    ]


part("eyes_open", *eye_open(*EYES[0], side=-1), *eye_open(*EYES[1], side=1))
part("eyes_side", *eye_open(*EYES[0], dx=4, dy=-3, side=-1), *eye_open(*EYES[1], dx=4, dy=-3, side=1))
part("eyes_wide", *eye_open(*EYES[0], rx=12, ry=15, iris=6.5, side=-1),
     *eye_open(*EYES[1], rx=12, ry=15, iris=6.5, side=1))
part("eyes_closed", *[line(f"M{x - 11} {y} Q{x} {y + 6} {x + 11} {y}", OUTLINE, 3) for x, y in EYES])
part("eyes_laugh", *[line(f"M{x - 11} {y + 3} Q{x} {y - 8} {x + 11} {y + 3}", OUTLINE, 3) for x, y in EYES])

# ---------------------------------------------------------------- obočí

BROW = 3.5
part("brows_normal", line("M88 94 Q98 89 108 93", PAL["hair"], BROW), line("M132 93 Q142 89 152 94", PAL["hair"], BROW))
part("brows_raised", line("M87 89 Q98 82 109 88", PAL["hair"], BROW), line("M131 88 Q142 82 153 89", PAL["hair"], BROW))
part("brows_sad", line("M88 94 Q98 93 107 88", PAL["hair"], BROW), line("M133 88 Q142 93 152 94", PAL["hair"], BROW))
part("brows_think", line("M88 93 Q98 90 108 93", PAL["hair"], BROW), line("M131 88 Q142 82 153 88", PAL["hair"], BROW))

# ---------------------------------------------------------------- ústa

part("mouth_smile", line("M108 139 Q120 150 132 139", OUTLINE, 2.5))
part("mouth_grin",
     shape("M103 136 Q120 162 137 136 Q120 141 103 136 Z", PAL["mouth"]),
     shape("M106 137.5 Q120 142 134 137.5 L132 142 Q120 146 108 142 Z", PAL["white"], stroke=None),
     ellipse(120, 151, 7, 3.5, PAL["tongue"], stroke=None),
     shape("M103 136 Q120 162 137 136 Q120 141 103 136 Z", "none"))
part("mouth_open",
     shape("M110 138 Q120 154 130 138 Q120 141 110 138 Z", PAL["mouth"]),
     ellipse(120, 146, 4.5, 2.2, PAL["tongue"], stroke=None))
part("mouth_o", ellipse(120, 143, 5.5, 7, PAL["mouth"]))
part("mouth_sad", line("M110 146 Q120 138 130 146", OUTLINE, 2.5))
part("mouth_small", line("M114 142 Q120 144 126 141", OUTLINE, 2.5))

# ---------------------------------------------------------------- trup a oblečení

NECK = shape("M110 150 L110 182 L130 182 L130 150 Z", PAL["skin"])

part("torso_tshirt",
     NECK,
     # kraťasy pod tričkem
     shape("M82 282 L158 282 L161 320 L125 320 L120 306 L115 320 L79 320 Z", PAL["shorts"]),
     # volné tričko bez rukávů (rukávy kreslí ruce)
     shape("M98 172 Q120 186 142 172 L162 180 Q168 186 166 200 L168 292 Q120 300 72 292 L74 200 "
           "Q72 186 78 180 Z", PAL["tshirt"]),
     line("M100 174 Q120 188 140 174", OUTLINE, 2),
     # jednoduchý obrázek na tričku (myška), bez loga
     ellipse(104, 238, 9, 9, PAL["tshirt_print"], stroke=None),
     ellipse(97, 229, 4, 4, PAL["tshirt_print"], stroke=None),
     ellipse(111, 229, 4, 4, PAL["tshirt_print"], stroke=None),
     )

part("torso_swimsuit",
     NECK,
     shape("M96 172 Q120 182 144 172 L160 182 Q166 190 162 214 L78 214 Q74 190 80 182 Z", PAL["skin"]),
     shape("M94 182 L100 182 L104 206 L136 206 L140 182 L146 182 L156 212 Q162 256 160 298 "
           "L134 312 L120 318 L106 312 L80 298 Q78 256 84 212 Z", PAL["swimsuit"]),
     shape("M80 298 Q92 312 106 312 L104 318 Q90 318 76 304 Z", PAL["swimsuit"]),
     shape("M160 298 Q148 312 134 312 L136 318 Q150 318 164 304 Z", PAL["swimsuit"]),
     *[ellipse(x, y, 5, 5, PAL["swim_flower"], stroke=None) for x, y in [(100, 228), (138, 246), (112, 272), (146, 284)]],
     )

# ---------------------------------------------------------------- ruce

ARM_W = 15
SLEEVE_W = 26
SHOULDER_L, SHOULDER_R = (82, 186), (158, 186)


def mirror(points):
    return [(W - x, y) for x, y in points]


ARMS = {
    "down": ([SHOULDER_L, (70, 236), (68, 280)], None),
    "walk": ([SHOULDER_L, (72, 234), (82, 276)], [SHOULDER_R, (174, 232), (180, 272)]),
    "up": ([SHOULDER_L, (52, 168), (30, 136)], None),
    "wave": ([SHOULDER_L, (70, 236), (68, 280)], [SHOULDER_R, (192, 170), (198, 126)]),
    "point": ([SHOULDER_L, (70, 236), (68, 280)], [SHOULDER_R, (196, 198), (226, 190)]),
    "hold": ([SHOULDER_L, (72, 234), (102, 246)], None),
}


def hand(x, y, kind="fist"):
    els = [ellipse(x, y, 9.5, 9.5, PAL["skin"])]
    return els


def arm(points, sleeve: str, finger=None):
    els = []
    sleeve_pts = partial(points, 0.32)
    if sleeve != "none":
        els.append(polyline(sleeve_pts, OUTLINE, SLEEVE_W + 5))
    els += limb(points, PAL["skin"], ARM_W)
    if sleeve != "none":
        els.append(polyline(sleeve_pts, PAL[sleeve], SLEEVE_W))
    x, y = points[-1]
    if finger:
        fx, fy = finger
        els += limb([(x, y), (fx, fy)], PAL["skin"], 5)
    els += hand(x, y)
    return els


for pose, (left, right) in ARMS.items():
    right = right or mirror(left)
    for sleeve in ("tshirt", "none"):
        finger = (240, 187) if pose == "point" else None
        part(f"arms_{pose}__{sleeve}", *arm(left, sleeve), *arm(right, sleeve, finger))

# ---------------------------------------------------------------- nohy

LEG_W = 17
LEGS = {
    "stand": ([(102, 300), (101, 344), (100, 372)], None, 0),
    "walk": ([(102, 300), (92, 342), (84, 372)], [(138, 300), (147, 340), (154, 368)], 0),
    "sit": ([(102, 300), (100, 320), (98, 356)], None, 0),
    "jump": ([(102, 300), (90, 330), (106, 352)], None, 1),
}


def foot(x, y, footwear, side, pointed=False):
    dx = -5 * side if not pointed else 0
    if footwear == "sneakers":
        if pointed:
            return [ellipse(x, y + 8, 10, 13, PAL["shoes"])]
        return [ellipse(x + dx, y + 10, 17, 10, PAL["shoes"]),
                line(f"M{x + dx - 15} {y + 14} Q{x + dx} {y + 19} {x + dx + 15} {y + 14}", OUTLINE, 2)]
    if pointed:
        return [ellipse(x, y + 7, 8, 11, PAL["skin"])]
    return [ellipse(x + dx, y + 8, 13, 8, PAL["skin"])]


def leg(points, footwear, side, pointed):
    els = limb(points, PAL["skin"], LEG_W)
    if footwear == "sneakers":
        sock = tail(points, 0.28)
        els.append(polyline(sock, PAL["socks"], LEG_W))
        els.append(line(f"M{sock[0][0] - 8} {sock[0][1]} L{sock[0][0] + 8} {sock[0][1]}", OUTLINE, 2))
    x, y = points[-1]
    els += foot(x, y, footwear, side, pointed)
    return els


for pose, (left, right, pointed) in LEGS.items():
    right = right or mirror(left)
    for footwear in ("sneakers", "barefoot"):
        part(f"legs_{pose}__{footwear}", *leg(left, footwear, -1, pointed), *leg(right, footwear, 1, pointed))

# ---------------------------------------------------------------- čepice (doplněk)


def cap(color, emblem):
    return [
        shape("M62 58 Q62 12 120 10 Q178 12 178 58 Z", color),
        line("M120 11 L120 56", OUTLINE, 2),
        line("M92 18 Q84 34 84 56", OUTLINE, 2),
        line("M148 18 Q156 34 156 56", OUTLINE, 2),
        ellipse(120, 10, 5, 3.5, color),
        emblem,
        shape("M58 56 Q120 70 182 56 Q188 62 181 67 Q120 82 59 67 Q52 62 58 56 Z", color),
    ]


part("hat_cap_red", *cap(PAL["cap_red"], ellipse(102, 38, 9, 7, "#F6D04D", stroke=None)))
part("hat_cap_blue", *cap(PAL["cap_blue"], ellipse(134, 36, 9, 9, "#FFFFFF", stroke=None)))

# ---------------------------------------------------------------- character.json

CHARACTER = {
    "id": "viky",
    "name": "Viky",
    "role": "hlavní hrdinka",
    "viewBox": [W, H],
    "traits": {
        "věk": "asi 7 let, štíhlá",
        "vlasy": "tmavě hnědé, rovné, po ramena, rovná ofina, prameny kolem obličeje",
        "oči": "velké, tmavě hnědé, výrazné",
        "pleť": "světle opálená, růžové tváře",
        "úsměv": "velký, široký, rozzářený",
        "doplňky": "drobné zlaté náušnice, ráda nosí kšiltovku (červenou nebo modrou)",
        "oblečení": "volné krémové tričko, růžové kraťasy, bílé ponožky, růžové tenisky; na pláži růžové plavky",
        "povaha": "zvídavá, veselá, ráda skáče a objevuje zvířata",
    },
    "requiredMarks": [
        {"id": "ofina", "part": "hair_front", "popis": "tmavé vlasy po ramena s rovnou ofinou"},
        {"id": "oci", "part": "eyes_*", "popis": "velké hnědé oči"},
        {"id": "nausnice", "part": "hair_front", "popis": "zlaté náušnice"},
    ],
    "palette": PAL,
    "layerOrder": ["hair_back", "legs", "torso", "head", "eyes", "brows", "mouth", "hair_front", "hat", "arms"],
    "expressions": {
        "radost": {"eyes": "open", "brows": "normal", "mouth": "smile"},
        "smich": {"eyes": "laugh", "brows": "raised", "mouth": "grin"},
        "prekvapeni": {"eyes": "wide", "brows": "raised", "mouth": "o"},
        "smutek": {"eyes": "open", "brows": "sad", "mouth": "sad"},
        "premysleni": {"eyes": "side", "brows": "think", "mouth": "small"},
        "strach": {"eyes": "wide", "brows": "sad", "mouth": "open"},
        "mluvi": {"eyes": "open", "brows": "normal", "mouth": "open"},
        "spi": {"eyes": "closed", "brows": "normal", "mouth": "small"},
    },
    "poses": {
        "stat": {"arms": "down", "legs": "stand"},
        "jit": {"arms": "walk", "legs": "walk"},
        "sedet": {"arms": "hold", "legs": "sit"},
        "skakat": {"arms": "up", "legs": "jump"},
        "ukazovat": {"arms": "point", "legs": "stand"},
        "mavat": {"arms": "wave", "legs": "stand"},
        "drzet": {"arms": "hold", "legs": "stand"},
    },
    "outfits": {
        "vychozi": {"torso": "tshirt", "sleeve": "tshirt", "footwear": "sneakers"},
        "plavky": {"torso": "swimsuit", "sleeve": "none", "footwear": "barefoot"},
    },
    "accessories": {
        "cepice_cervena": {"slot": "hat", "part": "hat_cap_red"},
        "cepice_modra": {"slot": "hat", "part": "hat_cap_blue"},
    },
    # body postavy v jejích souřadnicích: kam míří ocas bubliny, kam se dá rekvizita do rukou
    "anchors": {"head": [120, 100], "mouth": [120, 142], "hands": [120, 246], "feet": [120, 390],
                "handRight": [172, 280], "handLeft": [68, 280]},
    # poloměr hlavy i s vlasy kolem kotvy "head": ocas bubliny do tohoto kruhu nesmí zasáhnout
    "headRadius": 84,
}


def main():
    for name, body in parts.items():
        (HERE / f"{name}.svg").write_text(svg_doc(body, W, H, name), encoding="utf-8")
    CHARACTER["parts"] = sorted(parts)
    (HERE / "character.json").write_text(json.dumps(CHARACTER, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(parts)} dílů zapsáno do {HERE}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
