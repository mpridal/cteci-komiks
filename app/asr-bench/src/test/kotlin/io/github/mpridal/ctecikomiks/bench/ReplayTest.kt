package io.github.mpridal.ctecikomiks.bench

import io.github.mpridal.ctecikomiks.core.FilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Přehrání záznamů relací (fixtures). Jsou syntetické z asr-bench (hlas Windows, žádné dítě),
 * protože repo je veřejné. Skutečné záznamy z tabletu leží jen lokálně v asr-bench/sessions/.
 */
class ReplayTest {
    private val dir = File(javaClass.getResource("/sessions")!!.toURI())
    private val fixtures = dir.listFiles { f -> f.name.endsWith(".json") }!!.sorted()
    private val cfg = FilterConfig(minSnrDb = 12.0, stableMs = 200)

    @Test fun replayReproducesLoggedRun() {
        assertTrue("žádné fixtures", fixtures.isNotEmpty())
        for (f in fixtures) {
            for (p in replaySession(f, cfg)) {
                assertEquals("${f.name}: přehrání se liší od živého běhu", p.logged, p.replayed)
            }
        }
    }

    @Test fun childReadingPassesGateAndForeignTalkStaysLow() {
        val read = replaySession(File(dir, "p1_child_read.json"), cfg).single().replayed
        assertTrue("dětské čtení ${read.autoRatio}", read.autoRatio >= 0.85)
        val talk = replaySession(File(dir, "p1_talk_cs_child.json"), cfg).single().replayed
        assertTrue("sourozenec posunul ${talk.recognized + talk.skipped} slov", talk.recognized + talk.skipped <= 3)
    }

    @Test fun misreadGivesNoRewards() {
        val p = replaySession(File(dir, "p3_child_misread.json"), cfg).single()
        assertEquals(0, p.rewards)
    }

    @Test fun withoutStabilityFilterReplayDiffers() {
        // přehrání opravdu používá nastavení filtru (ne zalogovaný výsledek)
        val loose = replaySession(File(dir, "p2_child_syllables.json"), FilterConfig(minSnrDb = 0.0, stableMs = 0)).single()
        val strict = replaySession(File(dir, "p2_child_syllables.json"), cfg).single()
        assertEquals(strict.logged, strict.replayed)
        assertTrue(loose.replayed.total == strict.replayed.total)
    }
}
