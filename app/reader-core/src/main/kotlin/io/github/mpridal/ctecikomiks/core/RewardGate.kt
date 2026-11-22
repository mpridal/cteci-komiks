package io.github.mpridal.ctecikomiks.core

/**
 * Potvrzení hvězdičky jistotou rozpoznávače.
 *
 * Tracker hlásí odměnu hned při přečtení (průběžný výsledek). Jistotu slov ale Vosk spolehlivě dává
 * jen v konečném výsledku promluvy (zapnutí jistoty u průběžných výsledků zhoršuje rozpoznávání,
 * asr-bench 22. 11. 2026). Kandidát na hvězdičku proto čeká na konečný výsledek a potvrdí se,
 * jen když má slovo jistotu aspoň [minConf]. Gramatika stránky totiž chybné čtení „přitáhne“
 * ke správnému slovu, ale s nízkou jistotou (typicky 0,5–0,6 proti 1,0).
 */
class RewardGate(private val words: List<ReadWord>, private val minConf: Double = 0.9) {
    private val pending = mutableListOf<Int>()

    /** Zaznamená kandidáty z událostí trackeru (WordRead s reward = true). */
    fun offer(events: List<TrackEvent>) {
        for (e in events) if (e is TrackEvent.WordRead && e.reward) pending += e.index
    }

    val hasPending: Boolean get() = pending.isNotEmpty()

    /**
     * Konečný výsledek promluvy: vrátí potvrzené indexy slov. Slovo, které v konečném výsledku
     * nenajde (např. čtené po slabikách), potvrdí – chybné čtení se pozná právě podle nízké jistoty.
     * [finalWords] = null (rozpoznávač jistotu nedává) → potvrdí vše.
     */
    fun onFinal(finalWords: List<Pair<String, Double>>?): List<Int> {
        if (pending.isEmpty()) return emptyList()
        val confirmed = if (finalWords == null) pending.toList() else pending.filter { idx ->
            val w = words[idx]
            val keys = (listOf(w.text) + w.asr).map { TextNorm.key(it) }.toSet()
            val confs = finalWords.filter { TextNorm.key(it.first) in keys }.map { it.second }
            confs.isEmpty() || confs.max() >= minConf
        }
        pending.clear()
        return confirmed
    }

    /** Konec stránky bez konečného výsledku: kandidáty potvrdí (dítě se netrestá). */
    fun flush(): List<Int> = pending.toList().also { pending.clear() }
}
