package io.github.mpridal.ctecikomiks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardGateTest {
    private val words = listOf(
        ReadWord("TO", listOf("TO")),
        ReadWord("STŘÍKÁ!", listOf("STŘÍ", "KÁ"), practice = listOf("long-syllable")),
        ReadWord("VIKY", listOf("VI", "KY"), practice = listOf("focus"), asr = listOf("vika"), inVocabulary = false),
    )

    private fun candidates(gate: RewardGate, vararg idx: Int) =
        gate.offer(idx.map { TrackEvent.WordRead(it, MatchKind.WORD, direct = true, reward = true) })

    @Test fun highConfidenceConfirms() {
        val g = RewardGate(words)
        candidates(g, 1)
        assertTrue(g.hasPending)
        assertEquals(listOf(1), g.onFinal(listOf("to" to 1.0, "stříká" to 0.98)))
        assertFalse(g.hasPending)
    }

    @Test fun lowConfidenceRejects() {
        val g = RewardGate(words)
        candidates(g, 1)
        assertEquals(emptyList<Int>(), g.onFinal(listOf("stříká" to 0.55)))
    }

    @Test fun proxyWordCountsAndMissingWordConfirms() {
        val g = RewardGate(words)
        candidates(g, 2)
        assertEquals(listOf(2), g.onFinal(listOf("vika" to 0.95)))
        candidates(g, 1)
        assertEquals("slovo čtené po slabikách v konečném výsledku chybí → potvrdit", listOf(1), g.onFinal(listOf("stří" to 1.0, "ká" to 1.0)))
    }

    @Test fun noConfidenceOrPageEndConfirms() {
        val g = RewardGate(words)
        candidates(g, 1)
        assertEquals(listOf(1), g.onFinal(null))
        candidates(g, 2)
        assertEquals(listOf(2), g.flush())
    }

    @Test fun nonRewardEventsAreIgnored() {
        val g = RewardGate(words)
        g.offer(listOf(TrackEvent.WordRead(1, MatchKind.WORD, direct = true, reward = false), TrackEvent.Skipped(0)))
        assertFalse(g.hasPending)
    }
}
