package io.github.mpridal.ctecikomiks.core

import kotlin.math.log10

/** Nastavení filtru šumu. Ladí se v asr-bench (prototyp A). */
data class FilterConfig(
    /** Hlas musí být aspoň o tolik dB nad okolním šumem. 0 = bez hlasitostního filtru. */
    val minSnrDb: Double = 12.0,
    /** Jak dlouho po posledním hlasitém úseku se tokeny ještě přijímají. */
    val voiceHoldMs: Long = 700,
    /** Token průběžného výsledku musí zůstat beze změny tak dlouho, než se přijme. 0 = hned. */
    val stableMs: Long = 200,
    /** Slova konečného výsledku s nižší jistotou se zahodí. 0 = bez filtru. */
    val minConf: Double = 0.0,
    /** Délka okna pro odhad úrovně šumu (percentil hlasitosti). */
    val floorWindowMs: Long = 4000,
)

/**
 * Filtr mezi rozpoznávačem a [ReadingTracker]: nepustí dál tokeny vzniklé ze šumu.
 *
 * Odmítnuté tokeny nahradí [REJECTED], aby tracker držel pozice v hypotéze (tokeny se „spolknou“),
 * nestabilní konec průběžné hypotézy prostě odřízne (přijde později).
 */
class HypothesisFilter(private val config: FilterConfig = FilterConfig()) {
    private val levels = ArrayDeque<Pair<Long, Double>>()
    private var lastVoiceMs = Long.MIN_VALUE / 2
    private var voiceStartMs = -1L
    private val firstSeen = mutableListOf<Pair<String, Long>>()
    private val decided = mutableListOf<String>()

    /** Hladina šumu (dB) = 20. percentil hlasitosti posledních [FilterConfig.floorWindowMs]. */
    var floorDb: Double = 30.0
        private set

    /** Hlasitost dalšího úseku zvuku (RMS vzorků int16) v čase [tMs]. */
    fun onAudio(rms: Double, tMs: Long) {
        val db = 20 * log10(maxOf(rms, 1.0))
        levels.addLast(tMs to db)
        while (levels.isNotEmpty() && levels.first().first < tMs - config.floorWindowMs) levels.removeFirst()
        val sorted = levels.map { it.second }.sorted()
        floorDb = sorted[(sorted.size * 0.2).toInt().coerceAtMost(sorted.size - 1)]
        if (config.minSnrDb <= 0 || db - floorDb >= config.minSnrDb) {
            if (voiceStartMs < 0) voiceStartMs = tMs
            lastVoiceMs = tMs
        } else {
            voiceStartMs = -1
        }
    }

    private fun voiced(tMs: Long): Boolean = config.minSnrDb <= 0 || tMs - lastVoiceMs <= config.voiceHoldMs

    /** Průběžná hypotéza → hypotéza pro tracker (přijatá část). */
    fun partial(text: String, tMs: Long): String {
        val tokens = TextNorm.tokens(text)
        // sledování, odkdy je token na pozici beze změny
        for (i in tokens.indices) {
            if (i < firstSeen.size) {
                if (firstSeen[i].first != tokens[i]) {
                    firstSeen[i] = tokens[i] to tMs
                    while (decided.size > i) decided.removeAt(decided.size - 1)
                }
            } else {
                firstSeen += tokens[i] to tMs
            }
        }
        while (firstSeen.size > tokens.size) firstSeen.removeAt(firstSeen.size - 1)
        while (decided.size > tokens.size) decided.removeAt(decided.size - 1)

        while (decidedConf.size > decided.size) decidedConf.removeAt(decidedConf.size - 1)
        // rozhodnout stabilní tokeny v pořadí, rozhodnutí se nemění
        for (i in decided.size until tokens.size) {
            val (tok, since) = firstSeen[i]
            if (tMs - since < config.stableMs) break
            decided += if (voiced(since)) tok else REJECTED
            decidedConf += pendingWords?.getOrNull(i)?.takeIf { it.first == tok }?.second
        }
        return decided.joinToString(" ")
    }

    private val decidedConf = mutableListOf<Double?>()
    private var pendingWords: List<Pair<String, Double>>? = null

    /**
     * Jako [partial], navíc s jistotou slov (Vosk partial_result). Jistota rozhodnutého tokenu
     * je ta z chvíle, kdy se stal stabilním; tracker ji použije pro odměnu.
     */
    fun partialWithConfidence(text: String, words: List<Pair<String, Double>>?, tMs: Long): Pair<String, List<Double?>> {
        pendingWords = words
        val out = partial(text, tMs)
        pendingWords = null
        return out to decidedConf.toList()
    }

    /** Jako [final], navíc s jistotou slov. */
    fun finalWithConfidence(text: String, words: List<Pair<String, Double>>?, tMs: Long): Pair<String, List<Double?>> {
        val keep = decidedConf.toList()
        val out = final(text, words, tMs)
        val n = TextNorm.tokens(out).size
        val conf = List(n) { i -> if (i < keep.size) keep[i] else words?.getOrNull(i)?.second }
        return out to conf
    }

    /**
     * Konečný výsledek promluvy. [words] = slova s jistotou (když je rozpoznávač dává),
     * jinak se použije [text].
     */
    fun final(text: String, words: List<Pair<String, Double>>?, tMs: Long): String {
        val tokens = words?.map { it.first } ?: TextNorm.tokens(text)
        val out = tokens.mapIndexed { i, tok ->
            when {
                i < decided.size -> decided[i] // už rozhodnuté v průběhu zůstává
                !voiced(firstSeen.getOrNull(i)?.second ?: tMs) -> REJECTED
                words != null && config.minConf > 0 && words[i].second < config.minConf -> REJECTED
                else -> tok
            }
        }
        firstSeen.clear()
        decided.clear()
        decidedConf.clear()
        return out.joinToString(" ")
    }

    companion object {
        /** Token, který tracker přeskočí (ze šumu). */
        const val REJECTED = "<rej>"
    }
}
