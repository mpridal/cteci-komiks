package io.github.mpridal.ctecikomiks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HypothesisFilterTest {
    private fun quietThenLoud(f: HypothesisFilter, upTo: Long, loudFrom: Long) {
        var t = 0L
        while (t <= upTo) {
            f.onAudio(if (t >= loudFrom) 3000.0 else 50.0, t)
            t += 100
        }
    }

    @Test fun tokensDuringSilenceAreRejected() {
        val f = HypothesisFilter(FilterConfig(minSnrDb = 12.0, stableMs = 0))
        quietThenLoud(f, 2000, loudFrom = 99999)
        assertEquals(HypothesisFilter.REJECTED, f.partial("to", 2000))
    }

    @Test fun tokensDuringVoiceAreAccepted() {
        val f = HypothesisFilter(FilterConfig(minSnrDb = 12.0, stableMs = 0))
        quietThenLoud(f, 2000, loudFrom = 1500)
        assertEquals("to", f.partial("to", 2000))
    }

    @Test fun unstableTailWaits() {
        val f = HypothesisFilter(FilterConfig(minSnrDb = 0.0, stableMs = 200))
        assertEquals("", f.partial("to", 1000))
        assertEquals("", f.partial("to je", 1100))
        assertEquals("to", f.partial("to je", 1200))
        assertEquals("to je", f.partial("to je", 1300))
    }

    @Test fun changedTokenRestartsStability() {
        val f = HypothesisFilter(FilterConfig(minSnrDb = 0.0, stableMs = 200))
        f.partial("to", 0)
        f.partial("do", 150)          // rozpoznávač si to rozmyslel
        assertEquals("", f.partial("do", 300))
        assertEquals("do", f.partial("do", 350))
    }

    @Test fun finalDropsLowConfidenceWords() {
        val f = HypothesisFilter(FilterConfig(minSnrDb = 0.0, stableMs = 0, minConf = 0.6))
        val out = f.final("to je", listOf("to" to 0.95, "je" to 0.3), 1000)
        assertEquals("to ${HypothesisFilter.REJECTED}", out)
    }

    @Test fun trackerIgnoresRejectedTokens() {
        val t = ReadingTracker(listOf(ReadWord("TO", listOf("TO")), ReadWord("JE", listOf("JE"))))
        t.onPartial("${HypothesisFilter.REJECTED} je")
        assertEquals("<rej> se přeskočí, 'je' přeskočí 'TO' v okně", 2, t.cursor)
        val t2 = ReadingTracker(listOf(ReadWord("TO", listOf("TO")), ReadWord("JE", listOf("JE"))))
        t2.onPartial(HypothesisFilter.REJECTED)
        t2.onPartial("${HypothesisFilter.REJECTED} to")
        assertEquals(1, t2.cursor)
    }
}
