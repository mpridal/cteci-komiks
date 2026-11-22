"""Generátor pozadí a rekvizit. Spuštění: python studio/kit/build_scenery.py

Pozadí: viewBox 0 0 800 600 (panel 4:3), horních ~25 % bez důležité kresby (místo pro bubliny).
Rekvizity: vlastní viewBox, do panelu se umisťují podle šířky (podíl šířky panelu).
SVG se needitují ručně: úprava tohoto skriptu a nové spuštění.
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tools"))

from puppet import OUTLINE, ellipse, line, shape, svg_doc  # noqa: E402

PW, PH = 800, 600

C = {
    "sky": "#BFE3F5", "sky_zoo": "#CDEBF2", "sky_park": "#DDEFD9", "sun": "#FFE27A",
    "sea": "#4FB6D8", "sea_light": "#8FD3E8", "foam": "#FFFFFF", "hill": "#9CC3B5",
    "sand": "#F1DDB0", "sand_dark": "#E2C48C", "wall": "#E3C08F", "wall_line": "#C9A26C",
    "wood": "#9A6B45", "wood_dark": "#6E4A2E", "leaf": "#5E9E4E", "leaf_dark": "#3F7A36",
    "tree1": "#8DBF7A", "tree2": "#6FA65E", "floor": "#D9C7A8", "cloth": "#6E6470", "cloth_line": "#5A515C",
    "boat": "#FFFFFF", "croc": "#5B7A3E", "croc_dark": "#3E5A2A", "croc_belly": "#C9D48A",
    "shell": "#F6D3C2", "shell_line": "#E0A28C", "cookie": "#E9C27A", "choco": "#5A3420",
    "water_drop": "#DDF3FB", "white": "#FFFFFF", "eye": "#F2D16B",
}

backgrounds: dict[str, str] = {}
props: dict[str, tuple[str, float, float]] = {}


def bg(name, *els):
    backgrounds[name] = "\n".join(els)


def prop(name, w, h, *els):
    props[name] = ("\n".join(els), w, h)


def wave_line(y, amp, step, x0=0, x1=PW):
    d = f"M{x0} {y}"
    x = x0
    up = True
    while x < x1:
        d += f" Q{x + step / 2} {y - amp if up else y + amp} {x + step} {y}"
        x += step
        up = not up
    return d


# ---------------------------------------------------------------- pláž

shore = wave_line(420, 8, 80)
bg("beach",
   shape(f"M0 0 H{PW} V{PH} H0 Z", C["sky"], stroke=None),
   ellipse(710, 70, 38, 38, C["sun"], stroke=None),
   shape("M0 300 Q120 255 240 292 Q320 270 420 300 Z", C["hill"]),
   shape(f"M0 300 H{PW} V440 H0 Z", C["sea"], stroke=None),
   line("M0 300 H800", OUTLINE, 2),
   *[shape(f"M{x} 296 L{x + 10} 270 L{x + 18} 296 Z", C["boat"], width=1.5) for x in (470, 560, 640, 735)],
   *[line(f"M{x} {y} Q{x + 20} {y - 5} {x + 40} {y}", C["sea_light"], 3) for x, y in
     [(60, 340), (300, 330), (520, 350), (680, 335), (180, 380), (430, 390), (620, 395)]],
   shape(shore + f" V{PH} H0 Z", C["sand"]),
   line(wave_line(426, 8, 80), C["foam"], 6),
   *[ellipse(x, y, 4, 2, C["sand_dark"], stroke=None) for x, y in
     [(90, 500), (160, 560), (300, 520), (520, 540), (640, 500), (720, 570), (420, 585)]],
   )

# pláž, záběr do vody (postava stojí ve vodě)
bg("sea",
   shape(f"M0 0 H{PW} V{PH} H0 Z", C["sky"], stroke=None),
   shape("M380 300 Q520 262 640 290 Q720 270 800 288 V300 Z", C["hill"]),
   shape(f"M0 300 H{PW} V{PH} H0 Z", C["sea"], stroke=None),
   line("M0 300 H800", OUTLINE, 2),
   *[shape(f"M{x} 296 L{x + 10} 270 L{x + 18} 296 Z", C["boat"], width=1.5) for x in (60, 150, 690)],
   shape(f"M0 470 Q200 455 400 470 Q600 485 800 468 V{PH} H0 Z", C["sea_light"], stroke=None),
   *[line(f"M{x} {y} Q{x + 25} {y - 6} {x + 50} {y}", C["foam"], 3) for x, y in
     [(40, 360), (250, 345), (560, 365), (700, 340), (120, 420), (620, 430), (80, 520), (660, 540), (330, 575)]],
   )

# ---------------------------------------------------------------- zoo

bg("zoo",
   shape(f"M0 0 H{PW} V{PH} H0 Z", C["sky_zoo"], stroke=None),
   shape("M0 240 H800 V370 H0 Z", C["wall"]),
   *[line(f"M0 {y} H800", C["wall_line"], 2) for y in (275, 310, 345)],
   *[line(f"M{x} {y0} V{y0 + 35}", C["wall_line"], 2)
     for i, y0 in enumerate((240, 275, 310, 345)) for x in range(60 + (i % 2) * 50, 800, 100)],
   # palma
   shape("M250 370 L262 200 L276 200 L290 370 Z", C["wood"]),
   *[line(f"M258 {y} Q270 {y - 6} 282 {y}", C["wood_dark"], 2) for y in range(220, 370, 22)],
   *[shape(f"M269 196 L{x1} {y1} L{x2} {y2} Z", c) for (x1, y1, x2, y2), c in [
       ((110, 200, 130, 240), C["leaf"]), ((150, 120, 120, 160), C["leaf_dark"]), ((230, 80, 190, 100), C["leaf"]),
       ((320, 82, 290, 72), C["leaf_dark"]), ((400, 130, 380, 100), C["leaf"]), ((430, 210, 420, 180), C["leaf_dark"]),
       ((360, 250, 390, 240), C["leaf"]), ((170, 260, 150, 250), C["leaf_dark"])]],
   shape(f"M0 370 H{PW} V{PH} H0 Z", C["sand"]),
   # dřevěné zábradlí
   shape("M60 300 H90 V470 H60 Z", C["wood"]),
   shape("M710 300 H740 V470 H710 Z", C["wood"]),
   shape("M20 310 H780 V328 H20 Z", C["wood"]),
   line("M20 400 H780", "#8A8A8A", 2),
   *[ellipse(x, y, 6, 3, C["sand_dark"], stroke=None) for x, y in
     [(150, 520), (230, 560), (380, 500), (520, 575), (640, 520), (700, 590), (110, 590)]],
   )

# ---------------------------------------------------------------- terasa (stůl je zvlášť jako rekvizita vpředu)

bg("terrace",
   shape(f"M0 0 H{PW} V{PH} H0 Z", C["sky_park"], stroke=None),
   *[ellipse(x, y, r, r * 0.8, c, stroke=None) for x, y, r, c in [
       (80, 230, 120, C["tree1"]), (260, 200, 110, C["tree2"]), (470, 240, 130, C["tree1"]),
       (680, 210, 120, C["tree2"]), (790, 260, 90, C["tree1"])]],
   shape("M120 120 L140 120 L150 420 L110 420 Z", C["wood"]),
   shape("M600 140 L615 140 L622 420 L592 420 Z", C["wood"]),
   shape(f"M0 400 H{PW} V{PH} H0 Z", C["floor"]),
   line("M0 400 H800", OUTLINE, 2),
   # židle za postavou
   shape("M300 330 H500 V350 H300 Z", "#3A3A3A"),
   shape("M300 350 V460 H315 V350 Z M485 350 V460 H500 V350 Z", "#3A3A3A"),
   )

# ---------------------------------------------------------------- rekvizity

prop("table_front", 800, 220,
     shape("M0 40 Q400 20 800 40 V220 H0 Z", C["cloth"]),
     *[line(f"M{x} 50 L{x + 30} 220", C["cloth_line"], 2) for x in range(20, 800, 70)])

prop("shell", 100, 84,
     shape("M50 80 L8 36 Q20 4 50 4 Q80 4 92 36 Z", C["shell"]),
     *[line(f"M50 78 L{x} 12", C["shell_line"], 2.5) for x in (22, 36, 50, 64, 78)],
     shape("M38 80 H62 L58 70 H42 Z", C["shell_line"]))

prop("cookie", 70, 64,
     shape("M8 32 Q6 6 34 4 Q62 4 64 30 Q66 58 36 60 Q10 60 8 32 Z", C["cookie"]),
     *[ellipse(x, y, 5, 4.5, C["choco"], stroke=None) for x, y in [(24, 20), (42, 16), (34, 34), (50, 40), (20, 44), (52, 26)]])

prop("croc", 260, 90,
     shape("M10 60 Q40 50 80 46 L140 40 Q200 36 236 44 Q256 50 252 58 Q240 66 200 64 L150 66 Q100 72 70 70 Q40 70 10 60 Z",
           C["croc"]),
     shape("M150 66 Q120 72 80 70 Q110 60 150 60 Z", C["croc_belly"], stroke=None),
     *[shape(f"M{x} {y} L{x + 7} {y - 9} L{x + 14} {y} Z", C["croc_dark"], width=1.5)
       for x, y in [(60, 50), (80, 47), (100, 44), (120, 42), (140, 41), (160, 40)]],
     ellipse(205, 40, 9, 7, C["croc"]),
     ellipse(207, 39, 4, 4, C["eye"], stroke=None),
     ellipse(207, 39, 1.8, 3, OUTLINE, stroke=None),
     line("M215 58 Q235 60 250 54", OUTLINE, 2),
     *[shape(f"M{x} 64 L{x - 6} 84 L{x + 8} 84 Z", C["croc"], width=2) for x in (100, 130, 172, 196)])

prop("splash", 260, 120,
     *[shape(f"M{x} 110 Q{x + dx} {60 - h} {x + 2 * dx} 110 Z", C["water_drop"], width=2)
       for x, dx, h in [(20, 18, 10), (60, 22, 40), (110, 24, 60), (160, 20, 35), (205, 16, 5)]],
     *[ellipse(x, y, 6, 8, C["water_drop"], width=2) for x, y in [(40, 30), (130, 8), (220, 34), (90, 20)]])

prop("motion_lines", 120, 120,
     *[line(f"M{x} {y} L{x} {y + 40}", OUTLINE, 3) for x, y in [(20, 70), (60, 80), (100, 70)]])


def main():
    (HERE / "backgrounds").mkdir(exist_ok=True)
    (HERE / "props").mkdir(exist_ok=True)
    for name, body in backgrounds.items():
        (HERE / "backgrounds" / f"{name}.svg").write_text(svg_doc(body, PW, PH, name), encoding="utf-8")
    for name, (body, w, h) in props.items():
        (HERE / "props" / f"{name}.svg").write_text(svg_doc(body, w, h, name), encoding="utf-8")
    print(f"{len(backgrounds)} pozadí, {len(props)} rekvizit")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
