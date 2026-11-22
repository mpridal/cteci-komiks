"""Testy skládání kapitol (compose.py) na skutečných scénách z studio/chapters."""
import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))

import compose  # noqa: E402
from puppet import load_character, resolve_parts  # noqa: E402

CHAPTERS = TOOLS.parent / "chapters"
FORBIDDEN = re.compile(r"<text|<script|href=|<image|<foreignObject", re.I)


def scenes():
    return sorted(CHAPTERS.glob("*/*/scene.json"))


class ComposeTest(unittest.TestCase):
    def compose_copy(self, scene: Path):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        dst = tmp / "scene.json"
        shutil.copy(scene, dst)
        chapter, rep = compose.compose_chapter(dst)
        return tmp, chapter, rep

    def test_scenes_compose_and_pass_text_check(self):
        self.assertTrue(scenes(), "žádné scene.json")
        for scene in scenes():
            with self.subTest(scene=str(scene.relative_to(CHAPTERS))):
                out, chapter, rep = self.compose_copy(scene)
                self.assertEqual(rep.errors, [], [str(i) for i in rep.errors])
                self.assertEqual(chapter["formatVersion"], 1)
                for page in chapter["pages"]:
                    for panel in page["panels"]:
                        layers = panel["image"]["layers"]
                        self.assertEqual(layers[0]["id"], "bg")
                        for layer in layers:
                            svg = (out / layer["file"]).read_text(encoding="utf-8")
                            self.assertIsNone(FORBIDDEN.search(svg), f"{layer['file']} obsahuje zakázaný prvek")
                            if "box" in layer:
                                x1, y1, x2, y2 = layer["box"]
                                self.assertLess(x1, x2)
                                self.assertLess(y1, y2)
                        cue_ids = {c["id"] for c in panel["cues"]}
                        layer_ids = {l["id"] for l in layers}
                        for t in panel["texts"]:
                            for w in t["words"]:
                                for cid in w.get("cues", []):
                                    self.assertIn(cid, cue_ids)
                        for c in panel["cues"]:
                            if c["type"] == "anim":
                                self.assertIn(c["layer"], layer_ids)
                json.dumps(chapter)

    def test_accessories_do_not_change_within_chapter(self):
        def scene(*accs):
            return {"pages": [{"panels": [{"id": f"p{i}", "characters": [{"id": "viky", "accessories": a}]}
                                          for i, a in enumerate(accs, 1)]}]}
        self.assertEqual(compose.continuity_errors(scene(["cepice_modra"], ["cepice_modra"])), [])
        errs = compose.continuity_errors(scene(["cepice_modra"], ["cepice_modra"], ["cepice_cervena"]))
        self.assertEqual(len(errs), 1)
        self.assertIn("p3", errs[0])
        self.assertTrue(compose.continuity_errors(scene(["cepice_modra"], [])))

    def test_character_has_required_marks_in_every_pose(self):
        spec = load_character("viky")
        mark_parts = {m["part"] for m in spec["requiredMarks"]}
        for pose in spec["poses"]:
            for expr in spec["expressions"]:
                for outfit in spec["outfits"]:
                    parts = resolve_parts(spec, pose, expr, outfit)
                    for mp in mark_parts:
                        prefix = mp.rstrip("*")
                        self.assertTrue(any(p == mp or (mp.endswith("*") and p.startswith(prefix)) for p in parts),
                                        f"{pose}/{expr}/{outfit}: chybí {mp}")
                    for p in parts:
                        self.assertTrue((spec["_dir"] / f"{p}.svg").exists(), p)


if __name__ == "__main__":
    unittest.main()
