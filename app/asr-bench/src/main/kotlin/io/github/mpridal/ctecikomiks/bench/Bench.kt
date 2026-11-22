package io.github.mpridal.ctecikomiks.bench

import io.github.mpridal.ctecikomiks.core.FilterConfig
import io.github.mpridal.ctecikomiks.core.HypothesisFilter
import io.github.mpridal.ctecikomiks.core.ReadWord
import io.github.mpridal.ctecikomiks.core.ReadingTracker
import io.github.mpridal.ctecikomiks.core.TrackEvent
import io.github.mpridal.ctecikomiks.core.WordStatus
import io.github.mpridal.ctecikomiks.core.grammarFor
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioSystem
import kotlin.math.sqrt

/**
 * asr-bench: změří sledování čtení na nahrávkách z make_corpus.py pro několik nastavení filtru šumu.
 * Argumenty: <složka korpusu> <složka modelu> [výstup.json]
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Použití: <korpus> <model> [výstup.json]" }
    val corpus = File(args[0])
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    val model = Model(args[1])
    val manifest = JSONObject(File(corpus, "manifest.json").readText())
    val pages = manifest.getJSONArray("pages").let { arr -> (0 until arr.length()).map { parsePage(arr.getJSONObject(it)) } }
    val scenarios = manifest.getJSONArray("scenarios").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }

    val filters = linkedMapOf(
        "vypnuto" to FilterConfig(minSnrDb = 0.0, stableMs = 0, minConf = 0.0),
        "hlas+stabilita" to FilterConfig(minSnrDb = 12.0, stableMs = 200, minConf = 0.0),
        "hlas+stab+jistota" to FilterConfig(minSnrDb = 12.0, stableMs = 200, minConf = 0.7),
    )
    // slovník modelu pro rušivá slova (gramatika stránky + distraktory)
    val vocabFile = System.getenv("VOSK_VOCAB")?.let(::File)
    val pool = vocabFile?.takeIf { it.exists() }?.readLines()
        ?.filter { w -> w.length in 2..7 && w.all { it.isLetter() } }
        ?.shuffled(java.util.Random(42)) ?: emptyList()
    val grammars = linkedMapOf<String, (List<ReadWord>) -> String?>(
        "stránka" to { w -> JSONArray(grammarFor(w)).toString() },
        "volně" to { _ -> null },
    )
    val counts = (System.getenv("DISTRACTORS") ?: "3000").split(',').mapNotNull { it.trim().toIntOrNull() }
    if (pool.isNotEmpty()) for (n in counts) {
        grammars["stránka+$n"] = { w -> JSONArray(grammarFor(w) + pool.take(n)).toString() }
    }

    val configs = linkedMapOf<String, Pair<(List<ReadWord>) -> String?, FilterConfig>>()
    for ((g, gf) in grammars) for ((f, fc) in filters) configs["$g / $f"] = gf to fc
    // výběr konfigurací: čárkami oddělené části názvu, mezera se píše jako _
    val selected = if (args.size >= 4) {
        configs.filterKeys { k -> args[3].split(',').any { k == it.replace('_', ' ') } }
    } else configs

    val out = JSONObject()
    for ((name, gc) in selected) {
        val (grammarOf, cfg) = gc
        val results = scenarios.map { sc ->
            runScenario(model, pages[sc.getInt("page")], sc, File(corpus, sc.getString("wav")), cfg, grammarOf)
        }
        val summary = summarize(results)
        out.put(name, JSONObject().put("config", cfg.toString()).put("summary", summary)
            .put("scenarios", JSONArray(results.map { it.toJson() })))
        println("== $name: $cfg")
        println(summary.toString(2))
    }
    if (args.size >= 3) File(args[2]).writeText(out.toString(2))
}

private fun parsePage(o: JSONObject): List<ReadWord> {
    val arr = o.getJSONArray("words")
    return (0 until arr.length()).map {
        val w = arr.getJSONObject(it)
        ReadWord(
            text = w.getString("w"),
            syllables = w.getJSONArray("syl").let { s -> (0 until s.length()).map { i -> s.getString(i) } },
            practice = w.getJSONArray("practice").let { s -> (0 until s.length()).map { i -> s.getString(i) } },
            asr = w.getJSONArray("asr").let { s -> (0 until s.length()).map { i -> s.getString(i) } },
            inVocabulary = !w.getBoolean("oov"),
        )
    }
}

data class ScenarioResult(
    val name: String,
    val kind: String,
    val total: Int,
    val autoRead: Int,
    val advanced: Int,
    val rewards: Int,
    val latencies: List<Long>,
    val early: Int,
    val heard: List<String>,
) {
    fun toJson() = JSONObject().put("name", name).put("kind", kind).put("total", total).put("autoRead", autoRead)
        .put("advanced", advanced).put("rewards", rewards).put("latencies", JSONArray(latencies)).put("early", early)
        .put("heard", JSONArray(heard))
}

private fun runScenario(model: Model, words: List<ReadWord>, sc: JSONObject, wav: File, cfg: FilterConfig,
                        grammarOf: (List<ReadWord>) -> String?): ScenarioResult {
    val grammar = grammarOf(words)
    val rec = if (grammar != null) Recognizer(model, 16000f, grammar) else Recognizer(model, 16000f)
    rec.setWords(true)
    val tracker = ReadingTracker(words)
    val filter = HypothesisFilter(cfg)
    val ends = sc.optJSONArray("wordEndsMs")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
    val spoken = sc.optJSONArray("spokenWords")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
    val endOf = spoken.zip(ends).toMap()
    val samples = readWav(wav)
    val chunk = 1600
    val latencies = mutableListOf<Long>()
    val heard = mutableListOf<String>()
    var rewards = 0
    var early = 0
    var t = 0L

    fun handle(events: List<TrackEvent>) {
        for (e in events) {
            if (e is TrackEvent.WordRead) {
                if (e.reward) rewards++
                val end = endOf[e.index]
                if (end != null) {
                    if (e.direct) latencies += t - end
                    if (t < end - 600) early++
                }
            }
        }
    }

    var i = 0
    while (i < samples.size) {
        val n = minOf(chunk, samples.size - i)
        val buf = samples.copyOfRange(i, i + n)
        i += n
        t = i * 1000L / 16000
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        filter.onAudio(sqrt(sum / n), t)
        if (rec.acceptWaveForm(buf, n)) {
            val res = JSONObject(rec.result)
            val text = res.optString("text")
            val words2 = res.optJSONArray("result")?.let { a ->
                (0 until a.length()).map { k -> a.getJSONObject(k).let { it.getString("word") to it.getDouble("conf") } }
            }
            if (text.isNotBlank()) heard += text
            handle(tracker.onFinal(filter.final(text, words2, t)))
        } else {
            val p = JSONObject(rec.partialResult).optString("partial")
            handle(tracker.onPartial(filter.partial(p, t)))
        }
    }
    val fin = JSONObject(rec.finalResult)
    handle(tracker.onFinal(filter.final(fin.optString("text"), null, t)))
    rec.close()
    val st = tracker.stats()
    return ScenarioResult(sc.getString("name"), sc.getString("kind"), st.total, st.recognized + st.skipped,
        tracker.status.count { it != WordStatus.PENDING }, rewards, latencies, early, heard)
}

private fun readWav(f: File): ShortArray {
    AudioSystem.getAudioInputStream(f).use { ais ->
        val bytes = ais.readAllBytes()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(sb.remaining()).also { sb.get(it) }
    }
}

private fun summarize(results: List<ScenarioResult>): JSONObject {
    val o = JSONObject()
    for ((kind, rs) in results.groupBy { it.kind }) {
        val total = rs.sumOf { it.total }
        val lat = rs.flatMap { it.latencies }.sorted()
        o.put(kind, JSONObject().apply {
            when (kind) {
                "read", "syllables", "read_babble" -> {
                    put("auto", "%.0f %%".format(100.0 * rs.sumOf { it.autoRead } / total))
                    if (lat.isNotEmpty()) {
                        put("latMedianMs", lat[lat.size / 2])
                        put("latP90Ms", lat[(lat.size * 0.9).toInt().coerceAtMost(lat.size - 1)])
                    }
                    put("early", rs.sumOf { it.early })
                    put("rewards", rs.sumOf { it.rewards })
                }
                "misread" -> {
                    put("falseRewards", rs.sumOf { it.rewards })
                    put("auto", "%.0f %%".format(100.0 * rs.sumOf { it.autoRead } / total))
                }
                else -> put("falseAdvancedWords", rs.sumOf { it.advanced }).put("of", total)
            }
        })
    }
    return o
}
