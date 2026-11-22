package io.github.mpridal.ctecikomiks.core

import java.text.Normalizer

/** Slovo stránky, jak ho zná Čtečka z chapter.json. */
data class ReadWord(
    /** Slovo i s interpunkcí, jak se zobrazuje ("MOUR."). */
    val text: String,
    /** Slabiky pro čtení po slabikách. */
    val syllables: List<String>,
    /** Kategorie těžkého slova (long-syllable, no-vowel, focus); prázdné = snadné slovo. */
    val practice: List<String> = emptyList(),
    /** Id značek, které přečtení slova spustí. */
    val cues: List<String> = emptyList(),
    /** Náhradní tokeny rozpoznávače pro slova, která model nezná (pole "asr"). */
    val asr: List<String> = emptyList(),
    /** Zná slovo slovník rozpoznávače? (false = slovo má "asr" nebo se přijme i [unk]). */
    val inVocabulary: Boolean = true,
) {
    val isPractice: Boolean get() = practice.isNotEmpty()
}

object TextNorm {
    private val punctuation = Regex("[\\p{P}\\p{S}]+")
    private val marks = Regex("\\p{Mn}+")

    /** Malá písmena bez interpunkce, s diakritikou (pro gramatiku rozpoznávače). */
    fun lower(s: String): String = s.replace(punctuation, "").lowercase().trim()

    /** Klíč pro porovnání: malá písmena, bez interpunkce i diakritiky. */
    fun key(s: String): String =
        Normalizer.normalize(lower(s), Normalizer.Form.NFD).replace(marks, "")

    /** Rozdělí výsledek rozpoznávače na tokeny. */
    fun tokens(hypothesis: String): List<String> =
        hypothesis.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/** Gramatika rozpoznávače pro stránku: slova, slabiky víceslabičných slov, náhrady a [unk]. */
fun grammarFor(words: List<ReadWord>): List<String> {
    val items = linkedSetOf<String>()
    for (w in words) {
        items += TextNorm.lower(w.text)
        if (w.syllables.size > 1) w.syllables.forEach { items += it.lowercase() }
        items += w.asr
    }
    items.removeAll { it.isBlank() }
    return items.toList() + "[unk]"
}

fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

fun normalizedDistance(a: String, b: String): Double {
    val n = maxOf(a.length, b.length)
    return if (n == 0) 0.0 else levenshtein(a, b).toDouble() / n
}
