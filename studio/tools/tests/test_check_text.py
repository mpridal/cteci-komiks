"""Testy pro check_text.py. Spuštění: python -m unittest discover -s studio/tools/tests"""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_text as ct  # noqa: E402

BASE = ct.load_profile(ct.DEFAULT_PROFILE)


def profile(**practice):
    p = copy.deepcopy(BASE)
    p["practice"] = {"easySyllableMaxLetters": 2, "minPerChapter": 0, "maxPerSentence": 2, "focus": []}
    p["practice"].update(practice)
    return p


P = profile()


def W(word, practice=None):
    d = {"w": word, "syl": ct.syllabify(word)}
    if practice:
        d["practice"] = practice
    return d


def chapter_with(words, extra_texts=0):
    texts = [{"id": "t1", "kind": "bubble", "words": words}]
    for i in range(extra_texts):
        texts.append({"id": f"x{i}", "kind": "bubble", "words": [W("JE.")]})
    return {"title": "VIKY", "readerLevel": {"fontStage": "UPPER"},
            "pages": [{"panels": [{"id": "p1", "texts": texts}]}]}


class SyllabifyTest(unittest.TestCase):
    def test_open_syllables(self):
        self.assertEqual(ct.syllabify("MAMINKA"), ["MA", "MI", "NKA"])
        self.assertEqual(ct.syllabify("Viky."), ["VI", "KY"])

    def test_ch_is_one_letter(self):
        self.assertEqual(ct.letters("CHATA"), ["CH", "A", "T", "A"])
        self.assertEqual(ct.syllabify("CHATA"), ["CHA", "TA"])

    def test_diphthong(self):
        self.assertEqual(ct.syllabify("MOUCHA"), ["MOU", "CHA"])
        self.assertEqual(ct.syllabify("AUTO"), ["AU", "TO"])

    def test_vowelless(self):
        self.assertEqual(ct.syllabify("VLK"), ["VLK"])

    def test_syllabic_r_l(self):
        self.assertEqual(ct.syllabify("DRŽÍ"), ["DR", "ŽÍ"])
        self.assertEqual(ct.syllabify("KRTEK"), ["KR", "TEK"])
        self.assertEqual(ct.syllabify("VLNA"), ["VL", "NA"])
        self.assertEqual(ct.syllabify("BRATR"), ["BRA", "TR"])
        self.assertEqual(ct.syllabify("CHLÉB"), ["CHLÉB"])
        self.assertEqual(ct.practice_categories("DRŽÍ", ["DR", "ŽÍ"], P), ["no-vowel"])


class CategoriesTest(unittest.TestCase):
    def cats(self, word, p=P):
        return ct.practice_categories(word, ct.syllabify(word), p)

    def test_easy(self):
        self.assertEqual(self.cats("MÁMA"), [])
        self.assertEqual(self.cats("CHATA"), [])  # CHA = 2 písmena

    def test_long_syllable(self):
        self.assertEqual(self.cats("KOČKA"), ["long-syllable"])
        self.assertEqual(self.cats("DŮM"), ["long-syllable"])

    def test_no_vowel(self):
        self.assertEqual(self.cats("V"), ["no-vowel"])
        self.assertEqual(self.cats("VLK"), ["long-syllable", "no-vowel"])

    def test_focus(self):
        p = profile(focus=["ČKA", "PES"])
        self.assertEqual(self.cats("KOČKA", p), ["long-syllable", "focus"])
        self.assertEqual(self.cats("PES.", p), ["long-syllable", "focus"])


class PlainTextTest(unittest.TestCase):
    def test_hard_words_are_allowed(self):
        rep = ct.check_plain_text("VIKY MÁ KOČKU.", P, [])
        self.assertEqual(rep.errors, [])
        self.assertEqual([w["word"] for w in rep.practice_words], ["KOČKU"])

    def test_max_practice_per_sentence(self):
        rep = ct.check_plain_text("VLK A PES JDOU.", P, [])
        self.assertTrue(any("těžká slova" in i.message for i in rep.errors))

    def test_keywords(self):
        self.assertEqual(ct.check_plain_text("VIKY. VIKY. VIKY.", P, ["VIKY"]).errors, [])
        self.assertTrue(ct.check_plain_text("TO JE VIKY.", P, ["VIKY"]).errors)

    def test_sentence_too_long(self):
        rep = ct.check_plain_text("TO JE MÁMA A TO JE TÁTA.", P, [])
        self.assertTrue(any("slov (max" in i.message for i in rep.errors))

    def test_too_many_syllables(self):
        rep = ct.check_plain_text("POPELINEKO.", P, [])
        self.assertTrue(any("slabik" in i.message for i in rep.errors))

    def test_unknown_letter(self):
        p = dict(P, _known=set(ct.letters("AEIOUMT")))
        rep = ct.check_plain_text("TO JE MÁMA.", p, [])
        self.assertTrue(any("neznámá písmena" in i.message for i in rep.errors))


class ChapterTest(unittest.TestCase):
    def test_good_chapter(self):
        ch = chapter_with([W("TO"), W("JE"), W("KOČKA.", ["long-syllable"])])
        self.assertEqual(ct.check_chapter(ch, P, []).errors, [])

    def test_missing_practice_tag_is_error(self):
        ch = chapter_with([W("TO"), W("JE"), W("KOČKA.")])
        self.assertTrue(any("--annotate" in i.message for i in ct.check_chapter(ch, P, []).errors))

    def test_annotate_fills_tags(self):
        ch = chapter_with([W("TO", ["no-vowel"]), W("JE"), W("KOČKA.")])
        rep = ct.check_chapter(ch, P, [], annotate=True)
        self.assertEqual(rep.errors, [])
        words = ch["pages"][0]["panels"][0]["texts"][0]["words"]
        self.assertNotIn("practice", words[0])
        self.assertEqual(words[2]["practice"], ["long-syllable"])

    def test_min_per_chapter(self):
        ch = chapter_with([W("TO"), W("JE"), W("MÁMA.")])
        rep = ct.check_chapter(ch, profile(minPerChapter=1), [])
        self.assertTrue(any(i.where == "trénink" for i in rep.errors))

    def test_focus_missing_is_warning(self):
        ch = chapter_with([W("TO"), W("JE"), W("MÁMA.")])
        rep = ct.check_chapter(ch, profile(focus=["VLK"]), [])
        self.assertEqual(rep.errors, [])
        self.assertTrue(any("VLK" in i.message for i in rep.issues))

    def test_syl_must_match_word(self):
        ch = chapter_with([{"w": "VIKY.", "syl": ["VI", "KA"]}])
        self.assertTrue(any("nedávají" in i.message for i in ct.check_chapter(ch, P, []).errors))

    def test_different_split_is_warning(self):
        ch = chapter_with([{"w": "AUTO.", "syl": ["A", "U", "TO"]}])
        rep = ct.check_chapter(ch, P, [])
        self.assertEqual(rep.errors, [])
        self.assertTrue(any(i.level == "warning" for i in rep.issues))

    def test_too_many_bubbles(self):
        ch = chapter_with([W("JE.")], extra_texts=2)
        self.assertTrue(any("bubliny" in i.message for i in ct.check_chapter(ch, P, []).errors))

    def test_too_many_words_per_panel(self):
        ch = chapter_with([W("MÁMA.")] * 13)
        self.assertTrue(any("slov (max" in i.message for i in ct.check_chapter(ch, P, []).errors))


class CliTest(unittest.TestCase):
    def test_exit_codes(self):
        self.assertEqual(ct.main(["--text", "VIKY MÁ KOČKU."]), 0)
        self.assertEqual(ct.main(["--text", "VLK A PES JDOU."]), 1)
        self.assertEqual(ct.main(["neexistuje.json"]), 2)

    def test_annotate_writes_file(self):
        ch = chapter_with([W("TO"), W("JE"), W("KOČKA.")])
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "chapter.json"
            path.write_text(json.dumps(ch), encoding="utf-8")
            prof = Path(d) / "profile.json"
            raw = {k: v for k, v in profile().items() if not k.startswith("_")}
            prof.write_text(json.dumps(raw), encoding="utf-8")
            self.assertEqual(ct.main([str(path), "--profile", str(prof), "--annotate"]), 0)
            self.assertEqual(ct.main([str(path), "--profile", str(prof)]), 0)


if __name__ == "__main__":
    unittest.main()
