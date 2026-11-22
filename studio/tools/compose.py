#!/usr/bin/env python3
"""Složí kapitolu ze scene.json: vrstvy panelů (SVG) a chapter.json.

Použití:
  compose.py studio/chapters/viky/01/scene.json

Výstup do stejné složky:
  chapter.json             formát balíčku (docs/SPEC.md), slova se slabikami a značkou practice
  panels/<panel>/bg.svg    pozadí + statické rekvizity (vzadu)
  panels/<panel>/<id>.svg  jedna vrstva na postavu a na animovanou rekvizitu
  panels/<panel>/fg.svg    statické rekvizity vpředu (např. stůl), pokud jsou

Po složení se text zkontroluje stejně jako check_text.py. Návratový kód 1 = chyby v textu.

scene.json (zdroj kapitoly, píše ho Claude):
  {"seriesId", "chapter", "title", "keywords": [...],
   "pages": [{"music": null, "panels": [{
      "id": "p1", "background": "beach",
      "characters": [{"id": "viky", "x": 0.4, "y": 0.96, "height": 0.8, "pose": "mavat",
                      "expression": "radost", "outfit": "vychozi", "accessories": [], "flip": false}],
      "props": [{"id": "shell", "prop": "shell", "x": 0.5, "y": 0.8, "width": 0.12,
                 "at": "viky.hands", "layer": "back|front|animated", "rotate": 0}],
      "texts": [{"id": "t1", "kind": "narration|bubble", "speaker": "viky", "text": "...", "anchor": [x, y]}],
      "cues": [{"text": "t2", "word": "HOP!", "type": "anim|sfx|music", "layer": "viky", "action": "jump", "audio": "..."}]
   }]}]}
  x, y postavy = střed chodidel (podíl šířky/výšky panelu), height = výška postavy / výška panelu.
  x, y rekvizity = její střed, width = šířka / šířka panelu; "at": "<postava>.<kotva>" ji posadí na kotvu postavy.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import check_text  # noqa: E402
from puppet import KIT_DIR, compose_character, fmt, load_character  # noqa: E402

PW, PH = 800, 600
FORMAT_VERSION = 1


def r3(v: float) -> float:
    return round(v, 3)


def svg_wrap(inner: str, w: float, h: float) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}" '
            f'width="{fmt(w)}" height="{fmt(h)}">\n{inner}\n</svg>\n')


def file_inner(path: Path) -> tuple[str, float, float]:
    text = path.read_text(encoding="utf-8")
    import re
    vb = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', text)
    inner = re.search(r"<svg[^>]*>(.*)</svg>", text, re.S)
    if not vb or not inner:
        raise ValueError(f"{path}: chybí viewBox nebo obsah")
    return inner.group(1).strip(), float(vb.group(1)), float(vb.group(2))


class PanelComposer:
    def __init__(self, panel: dict, chars: dict):
        self.panel = panel
        self.chars = chars
        self.char_boxes: dict[str, tuple[list[float], dict]] = {}

    def character_box(self, c: dict) -> list[float]:
        spec = self.chars[c["id"]]
        vw, vh = spec["viewBox"]
        h = c["height"]
        w = h * PH * vw / vh / PW
        return [c["x"] - w / 2, c["y"] - h, c["x"] + w / 2, c["y"]]

    def anchor_point(self, char_id: str, anchor: str) -> tuple[float, float]:
        box, c = self.char_boxes[char_id]
        spec = self.chars[char_id]
        vw, vh = spec["viewBox"]
        ax, ay = spec["anchors"][anchor]
        if c.get("flip"):
            ax = vw - ax
        return (box[0] + (box[2] - box[0]) * ax / vw, box[1] + (box[3] - box[1]) * ay / vh)

    def prop_box(self, p: dict) -> tuple[list[float], str, float, float]:
        inner, w, h = file_inner(KIT_DIR / "props" / f"{p['prop']}.svg")
        x, y = p.get("x", 0.5), p.get("y", 0.5)
        if p.get("at"):
            char_id, anchor = p["at"].split(".")
            x, y = self.anchor_point(char_id, anchor)
            x += p.get("dx", 0)
            y += p.get("dy", 0)
        bw = p["width"]
        bh = bw * PW * h / w / PH
        return [x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2], inner, w, h

    @staticmethod
    def placed(inner: str, box: list[float], w: float, h: float, rotate: float = 0, flip=False) -> str:
        """Vloží obsah s vlastním viewBoxem do souřadnic panelu 800×600."""
        x, y = box[0] * PW, box[1] * PH
        bw, bh = (box[2] - box[0]) * PW, (box[3] - box[1]) * PH
        tr = []
        if rotate:
            tr.append(f"rotate({fmt(rotate)} {fmt(w / 2)} {fmt(h / 2)})")
        if flip:
            tr.append(f"translate({fmt(w)} 0) scale(-1 1)")
        g = f'<g transform="{" ".join(tr)}">{inner}</g>' if tr else inner
        return (f'<svg x="{fmt(x)}" y="{fmt(y)}" width="{fmt(bw)}" height="{fmt(bh)}" '
                f'viewBox="0 0 {fmt(w)} {fmt(h)}" overflow="visible">{g}</svg>')

    def compose(self, out_dir: Path) -> list[dict]:
        out_dir.mkdir(parents=True, exist_ok=True)
        p = self.panel
        bg_inner, _, _ = file_inner(KIT_DIR / "backgrounds" / f"{p['background']}.svg")
        back, front = [bg_inner], []
        layers = [{"id": "bg", "file": f"panels/{p['id']}/bg.svg"}]

        for c in p.get("characters", []):
            self.char_boxes[c["id"]] = (self.character_box(c), c)

        animated = []
        for pr in p.get("props", []):
            box, inner, w, h = self.prop_box(pr)
            mode = pr.get("layer", "back")
            if mode == "animated":
                animated.append((pr, box, inner, w, h))
            else:
                (front if mode == "front" else back).append(
                    self.placed(inner, box, w, h, pr.get("rotate", 0), pr.get("flip", False)))

        (out_dir / "bg.svg").write_text(svg_wrap("\n".join(back), PW, PH), encoding="utf-8")

        for c in p.get("characters", []):
            spec = self.chars[c["id"]]
            vw, vh = spec["viewBox"]
            g = compose_character(spec, pose=c.get("pose", "stat"), expression=c.get("expression", "radost"),
                                  outfit=c.get("outfit", "vychozi"), accessories=c.get("accessories", []),
                                  flip=c.get("flip", False))
            (out_dir / f"{c['id']}.svg").write_text(svg_wrap(g, vw, vh), encoding="utf-8")
            layers.append({"id": c["id"], "file": f"panels/{p['id']}/{c['id']}.svg",
                           "box": [r3(v) for v in self.char_boxes[c["id"]][0]]})

        for pr, box, inner, w, h in animated:
            body = inner
            if pr.get("rotate") or pr.get("flip"):
                tr = []
                if pr.get("rotate"):
                    tr.append(f"rotate({fmt(pr['rotate'])} {fmt(w / 2)} {fmt(h / 2)})")
                if pr.get("flip"):
                    tr.append(f"translate({fmt(w)} 0) scale(-1 1)")
                body = f'<g transform="{" ".join(tr)}">{inner}</g>'
            (out_dir / f"{pr['id']}.svg").write_text(svg_wrap(body, w, h), encoding="utf-8")
            layers.append({"id": pr["id"], "file": f"panels/{p['id']}/{pr['id']}.svg",
                           "box": [r3(v) for v in box]})

        if front:
            (out_dir / "fg.svg").write_text(svg_wrap("\n".join(front), PW, PH), encoding="utf-8")
            layers.append({"id": "fg", "file": f"panels/{p['id']}/fg.svg", "box": [0, 0, 1, 1]})
        return layers


def build_texts(panel: dict, composer: PanelComposer, profile: dict) -> tuple[list[dict], list[dict]]:
    texts, cues = [], []
    cue_n = 0
    for t in panel.get("texts", []):
        words = []
        for token in t["text"].split():
            syl = check_text.syllabify(token)
            w = {"w": token, "syl": syl}
            pr = check_text.practice_categories(token, syl, profile)
            if pr:
                w["practice"] = pr
            words.append(w)
        anchor = t.get("anchor")
        if anchor is None:
            if t["kind"] == "bubble" and t.get("speaker") in composer.char_boxes:
                box, _ = composer.char_boxes[t["speaker"]]
                anchor = [(box[0] + box[2]) / 2, 0.13]
            else:
                anchor = [0.25, 0.08]
        entry = {"id": t["id"], "kind": t["kind"]}
        if t.get("speaker"):
            entry["speaker"] = t["speaker"]
        entry["anchor"] = [r3(v) for v in anchor]
        entry["words"] = words
        texts.append(entry)
    for c in panel.get("cues", []):
        cue_n += 1
        cid = f"{panel['id']}c{cue_n}"
        target = next((t for t in texts if t["id"] == c["text"]), None)
        if target is None:
            raise ValueError(f"panel {panel['id']}: značka míří na neexistující text {c['text']}")
        want = check_text.strip_punct(c["word"]).upper()
        word = next((w for w in target["words"] if check_text.strip_punct(w["w"]).upper() == want), None)
        if word is None:
            raise ValueError(f"panel {panel['id']}: slovo {c['word']} v textu {c['text']} není")
        word.setdefault("cues", []).append(cid)
        cue = {"id": cid, "type": c["type"]}
        for k in ("audio", "layer", "action"):
            if k in c:
                cue[k] = c[k]
        cues.append(cue)
    return texts, cues


def compose_chapter(scene_path: Path, profile_path: Path = check_text.DEFAULT_PROFILE) -> tuple[dict, check_text.Report]:
    scene = json.loads(scene_path.read_text(encoding="utf-8"))
    profile = check_text.load_profile(profile_path)
    out = scene_path.parent
    chars: dict[str, dict] = {}
    pages = []
    for page in scene["pages"]:
        panels = []
        for panel in page["panels"]:
            for c in panel.get("characters", []):
                if c["id"] not in chars:
                    chars[c["id"]] = load_character(c["id"])
            composer = PanelComposer(panel, chars)
            layers = composer.compose(out / "panels" / panel["id"])
            texts, cues = build_texts(panel, composer, profile)
            panels.append({"id": panel["id"],
                           "image": {"type": "svg-layers", "aspect": "4:3", "layers": layers},
                           "texts": texts, "cues": cues})
        pg = {"panels": panels}
        if page.get("music"):
            pg = {"music": page["music"], **pg}
        pages.append(pg)
    chapter = {
        "formatVersion": FORMAT_VERSION,
        "seriesId": scene["seriesId"],
        "chapter": scene["chapter"],
        "title": scene["title"],
        "readerLevel": {"fontStage": profile.get("fontStage", "UPPER")},
        "cover": "panels/cover.webp",
        "audio": scene.get("audio", []),
        "pages": pages,
    }
    (out / "chapter.json").write_text(json.dumps(chapter, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rep = check_text.check_chapter(chapter, profile, scene.get("keywords", []))
    for msg in continuity_errors(scene):
        rep.error("kontinuita", msg)
    return chapter, rep


def continuity_errors(scene: dict) -> list[str]:
    """Doplňky postavy (čepice…) se v rámci jedné kapitoly nemění."""
    seen: dict[str, tuple[tuple, str]] = {}
    errors = []
    for page in scene["pages"]:
        for panel in page["panels"]:
            for c in panel.get("characters", []):
                acc = tuple(sorted(c.get("accessories", [])))
                if c["id"] not in seen:
                    seen[c["id"]] = (acc, panel["id"])
                elif seen[c["id"]][0] != acc:
                    first_acc, first_panel = seen[c["id"]]
                    errors.append(f"{c['id']}: v panelu {panel['id']} má doplňky {list(acc) or 'žádné'}, "
                                  f"ale v {first_panel} {list(first_acc) or 'žádné'} – v kapitole se nemění")
    return errors


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("scene", type=Path, help="scene.json kapitoly")
    ap.add_argument("--profile", type=Path, default=check_text.DEFAULT_PROFILE)
    args = ap.parse_args(argv)
    try:
        chapter, rep = compose_chapter(args.scene, args.profile)
    except (OSError, KeyError, ValueError, json.JSONDecodeError) as e:
        print(f"Chyba: {e}", file=sys.stderr)
        return 2
    n_panels = sum(len(p["panels"]) for p in chapter["pages"])
    for i in rep.issues:
        print(i)
    print(f"{'OK' if not rep.errors else 'NEPROŠLO'}: {n_panels} panelů, {len(rep.words)} slov, "
          f"{len(rep.practice_words)} těžkých, {len(rep.errors)} chyb textu")
    return 1 if rep.errors else 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
