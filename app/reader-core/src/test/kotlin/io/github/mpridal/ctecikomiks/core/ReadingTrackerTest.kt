package io.github.mpridal.ctecikomiks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTrackerTest {
    private fun w(text: String, vararg syl: String, practice: Boolean = false, asr: List<String> = emptyList(),
                  inVocab: Boolean = true) =
        ReadWord(text, syl.toList().ifEmpty { listOf(text) }, if (practice) listOf("long-syllable") else emptyList(),
            asr = asr, inVocabulary = inVocab)

    // "TO JE VIKY. VIKY MÁ KOČKU."
    private fun page() = listOf(
        w("TO"), w("JE"), w("VIKY.", "VI", "KY", asr = listOf("vika", "cviky"), inVocab = false),
        w("MÁMA", "MÁ", "MA"), w("MÁ"), w("KOČKU.", "KO", "ČKU", practice = true),
    )

    @Test fun normalization() {
        assertEquals("kočku", TextNorm.lower("KOČKU."))
        assertEquals("kocku", TextNorm.key("KOČKU."))
        assertEquals(listOf("to", "je"), TextNorm.tokens("  to   je "))
    }

    @Test fun readsWordsInOrder() {
        val t = ReadingTracker(page())
        t.onPartial("to")
        t.onPartial("to je")
        assertEquals(2, t.cursor)
        assertEquals(WordStatus.READ, t.status[1])
    }

    @Test fun partialRepeatsAreNotDoubleCounted() {
        val t = ReadingTracker(listOf(w("TO"), w("TO"), w("JE")))
        t.onPartial("to")
        t.onPartial("to")      // stejná hypotéza znovu
        assertEquals(1, t.cursor)
        t.onFinal("to")        // konec promluvy, nic nového
        assertEquals(1, t.cursor)
        t.onPartial("to")      // nová promluva
        assertEquals(2, t.cursor)
    }

    @Test fun ignoredTokensAreSwallowed() {
        val t = ReadingTracker(page())
        t.onPartial("to", ignore = true)   // vlastní zvuk z reproduktoru
        assertEquals(0, t.cursor)
        t.onPartial("to to")               // pokračování promluvy: první "to" se nezpracuje znovu
        assertEquals(1, t.cursor)
    }

    @Test fun proxyTokenReadsOutOfVocabularyWord() {
        val t = ReadingTracker(page())
        t.onFinal("to je vika")
        assertEquals(3, t.cursor)
        assertEquals(WordStatus.READ, t.status[2])
    }

    @Test fun unkAcceptedOnlyForOutOfVocabularyWords() {
        val t = ReadingTracker(page())
        t.onFinal("[unk] to je")
        assertEquals("unk na známém slově nic neposune", 2, t.cursor)
        val ev = t.feed("[unk]")
        assertEquals(3, t.cursor)
        val read = ev.filterIsInstance<TrackEvent.WordRead>().single()
        assertEquals(MatchKind.UNK, read.kind)
    }

    @Test fun syllablesInOrderReadTheWord() {
        val t = ReadingTracker(page().drop(3)) // MÁMA MÁ KOČKU
        val e1 = t.feed("má")
        // "má" je celé následující slovo i první slabika MÁMA: má přednost slabika aktuálního slova
        assertEquals(0, t.cursor)
        assertTrue(e1.single() is TrackEvent.SyllableHeard)
        t.feed("ma")
        assertEquals(1, t.cursor)
        assertEquals(WordStatus.READ, t.status[0])
    }

    @Test fun skippedWordsInWindowCountAsRead() {
        val t = ReadingTracker(page())
        val ev = t.onFinal("je")
        assertEquals(2, t.cursor)
        assertEquals(WordStatus.SKIPPED, t.status[0])
        assertFalse(ev.filterIsInstance<TrackEvent.WordRead>().single().direct)
    }

    @Test fun wordsBeyondWindowAreIgnored() {
        val t = ReadingTracker(page())
        t.feed("kočku") // index 5, okno 0..3
        assertEquals(0, t.cursor)
    }

    @Test fun fuzzyMatchForLongWords() {
        val t = ReadingTracker(listOf(w("KROKODÝL", "KRO", "KO", "DÝL")))
        t.feed("krokodýlu") // vzdálenost 1/9
        assertTrue(t.isDone)
    }

    @Test fun practiceWordNeedsStricterMatchAndGivesReward() {
        val sloppy = ReadingTracker(listOf(w("STŘÍKÁ", practice = true)))
        sloppy.feed("stříhá") // 1/6 = 0,17 → ještě projde přísným prahem 0,2
        assertTrue(sloppy.isDone)
        val wrong = ReadingTracker(listOf(w("STŘÍKÁ", practice = true)))
        wrong.feed("strýna") // 2/6 = 0,33 → běžný práh 0,34 by prošel, přísný ne
        assertFalse(wrong.isDone)
        val easy = ReadingTracker(listOf(w("STŘÍKÁ")))
        easy.feed("strýna")
        assertTrue(easy.isDone)

        val t = ReadingTracker(page())
        t.onFinal("to je vika máma má")
        val ev = t.feed("kočku")
        val read = ev.filterIsInstance<TrackEvent.WordRead>().single()
        assertTrue(read.reward)
        assertTrue(ev.last() is TrackEvent.PageDone)
    }

    @Test fun noRewardWhenPracticeWordIsSkippedOver() {
        val words = listOf(w("TO"), w("KOČKU", practice = true), w("JE"))
        val t = ReadingTracker(words)
        t.feed("to")
        val ev = t.feed("je")
        assertTrue(ev.filterIsInstance<TrackEvent.WordRead>().none { it.reward })
        assertEquals(WordStatus.SKIPPED, t.status[1])
    }

    @Test fun manualAdvanceAndStats() {
        val t = ReadingTracker(page())
        t.onFinal("to")
        t.manualAdvance()
        t.onFinal("vika máma má kočku")
        val s = t.stats()
        assertEquals(6, s.total)
        assertEquals(1, s.manual)
        assertEquals(5.0 / 6, s.autoRatio, 1e-9)
    }
}
