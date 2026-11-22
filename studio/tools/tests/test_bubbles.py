"""Bubliny v náhledech: ocas vždy existuje a nikdy nezasahuje do hlavy."""
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import render_previews as rp  # noqa: E402


class BubbleTest(unittest.TestCase):
    def check(self, cx, cy, bw, bh, head):
        cx, cy = rp.place_bubble(cx, cy, bw, bh, head)
        hx, hy, hr = head
        bx, by, tx, ty = rp.tail_geometry(cx - bw / 2, cy - bh / 2, bw, bh, head)
        self.assertGreaterEqual(math.dist((bx, by), (tx, ty)), rp.TAIL_MIN - 0.01, "ocas je moc krátký")
        self.assertGreaterEqual(math.dist((tx, ty), (hx, hy)), hr + rp.TAIL_GAP - 0.01, "ocas píchá do hlavy")
        near = (min(max(hx, cx - bw / 2), cx + bw / 2), min(max(hy, cy - bh / 2), cy + bh / 2))
        self.assertGreaterEqual(math.dist(near, (hx, hy)), hr, "bublina zakrývá hlavu")

    def test_far_bubble_keeps_position(self):
        self.assertEqual(rp.place_bubble(600, 80, 200, 60, (200, 300, 80)), (600, 80))
        self.check(600, 80, 200, 60, (200, 300, 80))

    def test_bubble_on_head_is_moved(self):
        self.check(400, 180, 260, 90, (400, 230, 80))

    def test_bubble_touching_head_gets_tail(self):
        self.check(560, 90, 260, 100, (420, 210, 80))


if __name__ == "__main__":
    unittest.main()
