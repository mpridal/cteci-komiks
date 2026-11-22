package io.github.mpridal.ctecikomiks.core

/** Prahy sledování (SPEC: Algoritmus sledování). Ladí se v prototypu A. */
data class TrackerConfig(
    /** Slova do této délky se porovnávají jen přesně. */
    val shortWordMaxLength: Int = 3,
    /** Max. normalizovaná Levenshteinova vzdálenost pro delší slova. */
    val maxDistance: Double = 0.34,
    /**
     * Hvězdička za těžké slovo jen při přesné shodě (asr-bench: tolerantní shoda dávala
     * odměnu i za chybné čtení, např. SŘÍKÁ místo STŘÍKÁ). Posun kurzoru zůstává tolerantní.
     */
    val rewardRequiresExact: Boolean = true,
    /** Okno za kurzorem, ve kterém se hledá shoda (kurzor + window). */
    val window: Int = 3,
    /** Přijmout [unk] jako přečtení slova, které slovník rozpoznávače nezná. */
    val acceptUnkForOov: Boolean = true,
)

enum class WordStatus { PENDING, READ, SKIPPED, MANUAL }

enum class MatchKind { WORD, SYLLABLES, PROXY, UNK }

sealed interface TrackEvent {
    /** Slovo přečteno. [direct] = rozpoznané přímo na kurzoru (ne přeskočením). */
    data class WordRead(val index: Int, val kind: MatchKind, val direct: Boolean, val reward: Boolean) : TrackEvent
    /** Slovo přeskočené posunem kurzoru – počítá se jako přečtené, dítě se netrestá. */
    data class Skipped(val index: Int) : TrackEvent
    /** Slabika aktuálního slova zazněla (pro průběžné zvýraznění). */
    data class SyllableHeard(val index: Int, val syllable: Int) : TrackEvent
    data object PageDone : TrackEvent
}

/**
 * Sleduje, kde v známém textu stránky čtenářka je. Nepřepisuje řeč, jen porovnává tokeny
 * rozpoznávače se slovy v okně od kurzoru.
 */
class ReadingTracker(val words: List<ReadWord>, private val config: TrackerConfig = TrackerConfig()) {
    val status: Array<WordStatus> = Array(words.size) { WordStatus.PENDING }
    var cursor: Int = 0
        private set
    private var syllableProgress = 0
    private var consumedInUtterance = 0

    val isDone: Boolean get() = cursor >= words.size

    /**
     * Průběžný výsledek rozpoznávače (celá dosavadní hypotéza promluvy).
     * [ignore] = tokeny jen „spolknout“ (např. během zvuku z reproduktoru), nic neposouvat.
     */
    fun onPartial(hypothesis: String, ignore: Boolean = false): List<TrackEvent> =
        process(TextNorm.tokens(hypothesis), final = false, ignore = ignore)

    /** Konečný výsledek promluvy. Další promluva začíná od nuly. */
    fun onFinal(hypothesis: String, ignore: Boolean = false): List<TrackEvent> =
        process(TextNorm.tokens(hypothesis), final = true, ignore = ignore)

    private fun process(tokens: List<String>, final: Boolean, ignore: Boolean): List<TrackEvent> {
        val events = mutableListOf<TrackEvent>()
        if (tokens.size < consumedInUtterance) consumedInUtterance = tokens.size // rozpoznávač hypotézu zkrátil
        if (!ignore) for (i in consumedInUtterance until tokens.size) events += feed(tokens[i])
        consumedInUtterance = if (final) 0 else tokens.size
        return events
    }

    /** Zpracuje jeden token. */
    fun feed(token: String): List<TrackEvent> {
        if (isDone) return emptyList()
        val raw = token.lowercase()
        if (raw == HypothesisFilter.REJECTED) return emptyList()
        if (raw == "[unk]" || raw == "<unk>") {
            val w = words[cursor]
            return if (config.acceptUnkForOov && !w.inVocabulary) read(cursor, MatchKind.UNK) else emptyList()
        }
        val t = TextNorm.key(token)
        if (t.isEmpty()) return emptyList()

        // 1) celé aktuální slovo (nebo jeho náhrada)
        matchWord(cursor, t)?.let { return read(cursor, it) }

        // 2) další slabika aktuálního slova
        val current = words[cursor]
        if (current.syllables.size > 1 && syllableProgress < current.syllables.size) {
            val expected = TextNorm.key(current.syllables[syllableProgress])
            if (expected == t) {
                syllableProgress++
                return if (syllableProgress == current.syllables.size) {
                    read(cursor, MatchKind.SYLLABLES)
                } else {
                    listOf(TrackEvent.SyllableHeard(cursor, syllableProgress - 1))
                }
            }
        }

        // 3) slovo dál v okně – předchozí se přeskočí
        val last = minOf(words.size - 1, cursor + config.window)
        for (j in cursor + 1..last) {
            matchWord(j, t)?.let { return read(j, it) }
        }

        // 4) kousky slova po sobě (čtení po slabikách ve volném rozpoznávání: "ko" "čku" → KOČKU)
        if (t.length <= MAX_PIECE) {
            recentPieces += t
            while (recentPieces.size > MAX_PIECES) recentPieces.removeAt(0)
            for (n in 2..recentPieces.size) {
                val joined = recentPieces.takeLast(n).joinToString("")
                matchWord(cursor, joined)?.let { return read(cursor, Match(MatchKind.SYLLABLES, it.exact)) }
            }
        } else {
            recentPieces.clear()
        }
        return emptyList()
    }

    private data class Match(val kind: MatchKind, val exact: Boolean)

    private val recentPieces = mutableListOf<String>()

    /** Shoda pro posun (tolerantní); [Match.exact] = přesná shoda, jen ta dává odměnu. */
    private fun matchWord(index: Int, t: String): Match? {
        val w = words[index]
        val key = TextNorm.key(w.text)
        if (matches(key, t, config.maxDistance)) return Match(MatchKind.WORD, key == t)
        w.asr.map { TextNorm.key(it) }.firstOrNull { matches(it, t, config.maxDistance) }
            ?.let { return Match(MatchKind.PROXY, it == t) }
        return null
    }

    private fun matches(expected: String, token: String, threshold: Double): Boolean =
        if (expected.length <= config.shortWordMaxLength || token.length <= config.shortWordMaxLength) {
            expected == token
        } else {
            normalizedDistance(expected, token) <= threshold
        }

    private fun read(index: Int, kind: MatchKind): List<TrackEvent> = read(index, Match(kind, kind == MatchKind.SYLLABLES))

    private fun read(index: Int, match: Match): List<TrackEvent> {
        val events = mutableListOf<TrackEvent>()
        val direct = index == cursor
        for (k in cursor until index) {
            status[k] = WordStatus.SKIPPED
            events += TrackEvent.Skipped(k)
        }
        status[index] = WordStatus.READ
        // hvězdička jen za přímé a přesné přečtení těžkého slova (ne přeskočením, ne [unk], ne podobně)
        val reward = direct && words[index].isPractice && match.kind != MatchKind.UNK &&
            (match.exact || !config.rewardRequiresExact)
        events += TrackEvent.WordRead(index, match.kind, direct, reward)
        advanceTo(index + 1)
        if (isDone) events += TrackEvent.PageDone
        return events
    }

    /** Tlačítko „dál“: aktuální slovo se označí jako ručně posunuté. */
    fun manualAdvance(): List<TrackEvent> {
        if (isDone) return emptyList()
        status[cursor] = WordStatus.MANUAL
        advanceTo(cursor + 1)
        return if (isDone) listOf(TrackEvent.PageDone) else emptyList()
    }

    private fun advanceTo(index: Int) {
        cursor = index
        syllableProgress = 0
        recentPieces.clear()
    }

    private companion object {
        /** Kousek slova (slabika) má nejvýš tolik písmen. */
        const val MAX_PIECE = 4
        /** Kolik posledních kousků se zkouší slepit. */
        const val MAX_PIECES = 4
    }

    /** Statistika stránky pro bránu prototypu A. */
    fun stats(): PageStats = PageStats(
        total = words.size,
        recognized = status.count { it == WordStatus.READ },
        skipped = status.count { it == WordStatus.SKIPPED },
        manual = status.count { it == WordStatus.MANUAL },
    )
}

data class PageStats(val total: Int, val recognized: Int, val skipped: Int, val manual: Int) {
    /** Podíl slov přečtených bez ručního posunu (přeskočená v okně se počítají). */
    val autoRatio: Double get() = if (total == 0) 0.0 else (recognized + skipped).toDouble() / total

    operator fun plus(o: PageStats) =
        PageStats(total + o.total, recognized + o.recognized, skipped + o.skipped, manual + o.manual)
}
