package io.github.mpridal.ctecikomiks.bench

import io.github.mpridal.ctecikomiks.core.FilterConfig
import io.github.mpridal.ctecikomiks.core.HypothesisFilter
import io.github.mpridal.ctecikomiks.core.PageStats
import io.github.mpridal.ctecikomiks.core.ReadWord
import io.github.mpridal.ctecikomiks.core.ReadingTracker
import io.github.mpridal.ctecikomiks.core.TrackEvent
import io.github.mpridal.ctecikomiks.core.TrackerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Přehrání záznamu relace (formát proto-a-session/2, ukládá ho Čtečka do files/sessions/)
 * přes filtr a sledování s libovolným nastavením. Výstup rozpoznávače je v záznamu, takže
 * se dá ladit filtr i sledování na skutečném čtení bez dalšího čtení nahlas.
 */
data class ReplayPage(val panel: String, val logged: PageStats?, val replayed: PageStats, val rewards: Int)

fun parseWords(arr: JSONArray): List<ReadWord> = (0 until arr.length()).map {
    val w = arr.getJSONObject(it)
    fun list(key: String) = w.optJSONArray(key)?.let { s -> (0 until s.length()).map { i -> s.getString(i) } } ?: emptyList()
    ReadWord(text = w.getString("w"), syllables = list("syl"), practice = list("practice"),
        asr = list("asr"), inVocabulary = !w.optBoolean("oov", false))
}

fun replayPage(page: JSONObject, filterCfg: FilterConfig, trackerCfg: TrackerConfig = TrackerConfig()): ReplayPage {
    val words = parseWords(page.getJSONArray("words"))
    val tracker = ReadingTracker(words, trackerCfg)
    val filter = HypothesisFilter(filterCfg)
    var rewards = 0
    val gate = io.github.mpridal.ctecikomiks.core.RewardGate(words)
    fun handle(ev: List<TrackEvent>) { gate.offer(ev) }

    // časová osa: hlasitost po úsecích + zalogované hypotézy (průběžné jen při změně) + ruční posuny
    data class Item(val t: Long, val order: Int, val o: JSONObject?, val rms: Double)
    val items = mutableListOf<Item>()
    page.optJSONArray("levels")?.let { a ->
        for (i in 0 until a.length()) a.getJSONArray(i).let { items += Item(it.getLong(0), 0, null, it.getDouble(1)) }
    }
    page.getJSONArray("events").let { a ->
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("type") != "read") items += Item(it.getLong("t"), 1, it, 0.0) }
    }
    items.sortWith(compareBy({ it.t }, { it.order }))

    var raw = ""
    var rawWords: List<Pair<String, Double>>? = null
    var ignored = false
    fun wordsOf(o: JSONObject) = o.optJSONArray("words")?.let { a ->
        (0 until a.length()).map { k -> a.getJSONArray(k).let { it.getString(0) to it.getDouble(1) } }
    }
    var pendingTick = false
    for ((idx, item) in items.withIndex()) {
        val o = item.o
        if (o == null) {
            filter.onAudio(item.rms, item.t)
            // Čtečka volá onPartial každý úsek; zalogované jsou jen změny → doplnit opakování
            val next = items.getOrNull(idx + 1)?.o
            pendingTick = !(next != null && next.getString("type") in setOf("partial", "final") && next.getLong("t") - item.t < 100)
            if (pendingTick && raw.isNotEmpty()) {
                val (acc, conf) = filter.partialWithConfidence(raw, rawWords, item.t)
                handle(tracker.onPartial(acc, ignored, conf))
            }
            continue
        }
        when (o.getString("type")) {
            "partial" -> {
                raw = o.optString("raw", o.optString("text"))
                rawWords = wordsOf(o)
                ignored = o.optBoolean("ignored", false)
                val (acc, conf) = filter.partialWithConfidence(raw, rawWords, o.getLong("t"))
                handle(tracker.onPartial(acc, ignored, conf))
            }
            "final" -> {
                val (acc, conf) = filter.finalWithConfidence(o.optString("raw"), wordsOf(o), o.getLong("t"))
                handle(tracker.onFinal(acc, o.optBoolean("ignored", false), conf))
                rewards += gate.onFinal(wordsOf(o)).size
                raw = ""
                rawWords = null
            }
            "manual" -> tracker.manualAdvance()
        }
    }
    rewards += gate.flush().size
    val logged = page.optJSONObject("stats")?.let {
        PageStats(it.getInt("total"), it.getInt("recognized"), it.getInt("skipped"), it.getInt("manual"))
    }
    return ReplayPage(page.optString("panel"), logged, tracker.stats(), rewards)
}

fun replaySession(file: File, filterCfg: FilterConfig, trackerCfg: TrackerConfig = TrackerConfig()): List<ReplayPage> {
    val s = JSONObject(file.readText())
    val pages = s.getJSONArray("pages")
    return (0 until pages.length()).map { replayPage(pages.getJSONObject(it), filterCfg, trackerCfg) }
}

/**
 * Použití: gradlew :asr-bench:replay --args="<záznam.json nebo složka> [snrDb] [stableMs]"
 * Vypíše po stránkách zalogovaný a přehraný podíl slov bez ručního posunu.
 */
object ReplayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Použití: <záznam.json|složka> [snrDb] [stableMs]" }
        val cfg = FilterConfig(minSnrDb = args.getOrNull(1)?.toDouble() ?: 12.0, stableMs = args.getOrNull(2)?.toLong() ?: 200)
        val src = File(args[0])
        val files = if (src.isDirectory) src.listFiles { f -> f.name.endsWith(".json") }!!.sorted() else listOf(src)
        println("Filtr: $cfg")
        for (f in files) {
            val pages = replaySession(f, cfg)
            println("== ${f.name}")
            for (p in pages) {
                val l = p.logged?.let { "%3.0f %%".format(it.autoRatio * 100) } ?: "  –"
                println("  ${p.panel.padEnd(28)} záznam $l → přehrání ${"%3.0f %%".format(p.replayed.autoRatio * 100)} (ručně ${p.replayed.manual}, hvězdičky ${p.rewards})")
            }
            val total = pages.map { it.replayed }.reduce { a, b -> a + b }
            println("  celkem ${"%.0f %%".format(total.autoRatio * 100)} bez ručního posunu")
        }
    }
}
