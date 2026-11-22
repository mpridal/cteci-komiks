package io.github.mpridal.ctecikomiks.bench

import io.github.mpridal.ctecikomiks.core.FilterConfig
import io.github.mpridal.ctecikomiks.core.HypothesisFilter
import io.github.mpridal.ctecikomiks.core.ReadWord
import io.github.mpridal.ctecikomiks.core.ReadingTracker
import io.github.mpridal.ctecikomiks.core.RewardGate
import io.github.mpridal.ctecikomiks.core.TextNorm
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
        "hlas+stab100" to FilterConfig(minSnrDb = 12.0, stableMs = 100, minConf = 0.0),
        "hlas+stab150" to FilterConfig(minSnrDb = 12.0, stableMs = 150, minConf = 0.0),
        "hlas+stab300" to FilterConfig(minSnrDb = 12.0, stableMs = 300, minConf = 0.0),
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
        // bez diakritiky: Windows při předání argumentů poškodí znaky mimo cp1252 (ě)
        fun plain(s: String) = TextNorm.key(s.replace('_', ' ').replace("/", " slash ").replace("+", " plus "))
        configs.filterKeys { k -> args[3].split(',').any { plain(k) == plain(it) } }
    } else configs

    val out = JSONObject()
    for ((name, gc) in selected) {
        val (grammarOf, cfg) = gc
        val results = scenarios.map { sc ->
            runScenario(model, pages[sc.getInt("page")], sc, File(corpus, sc.getString("wav")), cfg, grammarOf)
        }
        // DUMP_SESSIONS=<složka>: každý scénář jako záznam relace (pro replay a fixtures)
        System.getenv("DUMP_SESSIONS")?.let { dir ->
            val d = File(dir, TextNorm.key(name.replace("/", " ").replace("+", " ")).replace(Regex("\\s+"), "-")).apply { mkdirs() }
            for (r in results) {
                File(d, "${r.name}.json").writeText(JSONObject().put("format", "proto-a-session/2")
                    .put("engine", "BENCH").put("mode", name).put("pages", JSONArray().put(r.session)).toString(1))
            }
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
    /** Stránka ve formátu záznamu relace (proto-a-session/2), pro replay a fixtures. */
    val session: JSONObject,
    val rewardLatencies: List<Long> = emptyList(),
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
    // jistota u průběžných výsledků zhoršuje rozpoznávání (čtení 100 % → 67 %), jen pro pokusy
    if (System.getenv("PARTIAL_WORDS") == "1") rec.setPartialWords(true)
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

    val gate = RewardGate(words)
    val rewardLat = mutableListOf<Long>()
    fun award(indices: List<Int>) {
        rewards += indices.size
        for (idx in indices) endOf[idx]?.let { rewardLat += t - it }
    }
    fun handle(events: List<TrackEvent>) {
        gate.offer(events)
        for (e in events) {
            if (e is TrackEvent.WordRead) {
                val end = endOf[e.index]
                if (end != null) {
                    if (e.direct) latencies += t - end
                    if (t < end - 600) early++
                }
            }
        }
    }

    val levels = JSONArray()
    val events = JSONArray()
    var lastRaw = ""
    var lastPw: List<Pair<String, Double>>? = null
    var i = 0
    while (i < samples.size) {
        val n = minOf(chunk, samples.size - i)
        val buf = samples.copyOfRange(i, i + n)
        i += n
        t = i * 1000L / 16000
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        val rms = sqrt(sum / n)
        filter.onAudio(rms, t)
        levels.put(JSONArray().put(t).put(rms.toInt()))
        if (rec.acceptWaveForm(buf, n)) {
            val res = JSONObject(rec.result)
            val text = res.optString("text")
            val words2 = res.optJSONArray("result")?.let { a ->
                (0 until a.length()).map { k -> a.getJSONObject(k).let { it.getString("word") to it.getDouble("conf") } }
            }
            if (text.isNotBlank()) heard += text
            val (accepted, conf) = filter.finalWithConfidence(text, words2, t)
            events.put(JSONObject().put("t", t).put("type", "final").put("raw", text).put("accepted", accepted).apply {
                words2?.let { put("words", JSONArray(it.map { (w, c) -> JSONArray().put(w).put(c) })) }
            })
            lastRaw = ""
            handle(tracker.onFinal(accepted, confidences = conf))
            award(gate.onFinal(words2))
        } else {
            val pr = JSONObject(rec.partialResult)
            val p = pr.optString("partial")
            val pw = pr.optJSONArray("partial_result")?.let { a ->
                (0 until a.length()).map { k -> a.getJSONObject(k).let { it.getString("word") to it.optDouble("conf", 1.0) } }
            }
            val (accepted, conf) = filter.partialWithConfidence(p, pw, t)
            // logovat i změnu jistoty: replay pak rozhoduje o odměně se stejnou jistotou jako živý běh
            if (p != lastRaw || pw != lastPw) {
                events.put(JSONObject().put("t", t).put("type", "partial").put("raw", p).put("accepted", accepted).apply {
                    pw?.let { put("words", JSONArray(it.map { (w, c) -> JSONArray().put(w).put(c) })) }
                })
                lastRaw = p
                lastPw = pw
            }
            handle(tracker.onPartial(accepted, confidences = conf))
        }
    }
    val fin = JSONObject(rec.finalResult)
    val finAccepted = filter.final(fin.optString("text"), null, t)
    events.put(JSONObject().put("t", t).put("type", "final").put("raw", fin.optString("text")).put("accepted", finAccepted))
    handle(tracker.onFinal(finAccepted))
    award(gate.onFinal(fin.optJSONArray("result")?.let { a ->
        (0 until a.length()).map { k -> a.getJSONObject(k).let { it.getString("word") to it.getDouble("conf") } }
    }))
    award(gate.flush())
    rec.close()
    val st = tracker.stats()
    val session = JSONObject().put("panel", sc.getString("name")).put("filter", cfg.toString())
        .put("words", JSONArray(words.map { w ->
            JSONObject().put("w", w.text).put("syl", JSONArray(w.syllables)).put("practice", JSONArray(w.practice))
                .put("asr", JSONArray(w.asr)).put("oov", !w.inVocabulary)
        }))
        .put("stats", JSONObject().put("total", st.total).put("recognized", st.recognized)
            .put("skipped", st.skipped).put("manual", st.manual))
        .put("events", events).put("levels", levels)
    return ScenarioResult(sc.getString("name"), sc.getString("kind"), st.total, st.recognized + st.skipped,
        tracker.status.count { it != WordStatus.PENDING }, rewards, latencies, early, heard, session, rewardLat)
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
            when {
                kind in setOf("read", "syllables", "read_talk", "child_read", "child_syllables") -> {
                    put("auto", "%.0f %%".format(100.0 * rs.sumOf { it.autoRead } / total))
                    if (lat.isNotEmpty()) {
                        put("latMedianMs", lat[lat.size / 2])
                        put("latP90Ms", lat[(lat.size * 0.9).toInt().coerceAtMost(lat.size - 1)])
                    }
                    put("early", rs.sumOf { it.early })
                    put("rewards", rs.sumOf { it.rewards })
                    rs.flatMap { it.rewardLatencies }.sorted().takeIf { it.isNotEmpty() }?.let { put("rewardLatMedianMs", it[it.size / 2]) }
                }
                kind.endsWith("misread") -> {
                    put("falseRewards", rs.sumOf { it.rewards })
                    put("auto", "%.0f %%".format(100.0 * rs.sumOf { it.autoRead } / total))
                }
                else -> { put("falseAdvancedWords", rs.sumOf { it.advanced }); put("of", total) }
            }
        })
    }
    return o
}
