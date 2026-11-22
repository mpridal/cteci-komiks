package io.github.mpridal.ctecikomiks.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mpridal.ctecikomiks.audio.Sfx
import io.github.mpridal.ctecikomiks.core.FilterConfig
import io.github.mpridal.ctecikomiks.core.HypothesisFilter
import io.github.mpridal.ctecikomiks.core.PageStats
import io.github.mpridal.ctecikomiks.core.ReadingTracker
import io.github.mpridal.ctecikomiks.core.TrackEvent
import io.github.mpridal.ctecikomiks.core.WordStatus
import io.github.mpridal.ctecikomiks.data.ProtoContent
import io.github.mpridal.ctecikomiks.data.ProtoPage
import io.github.mpridal.ctecikomiks.speech.SpeechEngine
import io.github.mpridal.ctecikomiks.speech.SystemEngine
import io.github.mpridal.ctecikomiks.speech.VoskEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EngineKind(val label: String) { VOSK("Vosk"), SYSTEM("Systém") }

/** Režim gramatiky Vosku (asr-bench: volně = nejméně falešných posunů od cizí řeči). */
enum class GrammarMode(val label: String) { FREE("Volně"), DISTRACT("Stránka + 1000"), PAGE("Jen stránka") }

data class EngineSummary(
    val engine: EngineKind,
    val mode: String,
    val stats: PageStats,
    val rewards: Int,
    val latencies: List<Long>,
) {
    val medianLatency: Long? get() = latencies.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}

data class UiState(
    val loading: Boolean = true,
    val engine: EngineKind = EngineKind.VOSK,
    val grammarMode: GrammarMode = GrammarMode.FREE,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val statuses: List<WordStatus> = emptyList(),
    val cursor: Int = 0,
    val syllablesHeard: Int = 0,
    val heard: String = "",
    val stars: Int = 0,
    val rewardTick: Int = 0,
    val lastLatencyMs: Long? = null,
    val listening: Boolean = false,
    val micAllowed: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val summaries: Map<String, EngineSummary> = emptyMap(),
    val savedLog: String? = null,
)

class ProtoViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    var pages: List<ProtoPage> = emptyList()
        private set
    private val sfx = Sfx(app)
    private val engines = mapOf(EngineKind.VOSK to VoskEngine(app), EngineKind.SYSTEM to SystemEngine(app))
    private var tracker: ReadingTracker? = null
    private var filter = HypothesisFilter()
    private val distractors: List<String> by lazy {
        runCatching { app.assets.open("proto/distractors.txt").bufferedReader().readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
    }
    private var ignoreUntil = 0L
    private var lastVoiceEnd = 0L
    private var pageStart = 0L

    // měření pro bránu
    private var sessionStats = PageStats(0, 0, 0, 0)
    private var sessionRewards = 0
    private val latencies = mutableListOf<Long>()
    private var log = JSONArray()
    private var pageLog = JSONArray()

    init {
        viewModelScope.launch {
            pages = ProtoContent.load(app)
            _state.update { it.copy(loading = false, pageCount = pages.size) }
            openPage(0)
        }
    }

    val page: ProtoPage? get() = pages.getOrNull(_state.value.pageIndex)

    fun onMicPermission(granted: Boolean) {
        _state.update { it.copy(micAllowed = granted) }
        if (granted) startListening()
    }

    fun selectEngine(kind: EngineKind) {
        if (kind == _state.value.engine && !_state.value.finished) return
        engines[_state.value.engine]?.stop()
        resetSession()
        _state.update { it.copy(engine = kind, finished = false, stars = 0, error = null) }
        openPage(0)
    }

    fun selectGrammar(mode: GrammarMode) {
        if (mode == _state.value.grammarMode) return
        engines[_state.value.engine]?.stop()
        resetSession()
        _state.update { it.copy(grammarMode = mode, finished = false, stars = 0, error = null) }
        openPage(0)
    }

    private val modeLabel: String
        get() = if (_state.value.engine == EngineKind.VOSK) "Vosk · ${_state.value.grammarMode.label}" else _state.value.engine.label

    fun restart() {
        engines[_state.value.engine]?.stop()
        resetSession()
        _state.update { it.copy(finished = false, stars = 0, error = null) }
        openPage(0)
    }

    private fun resetSession() {
        sessionStats = PageStats(0, 0, 0, 0)
        sessionRewards = 0
        latencies.clear()
        log = JSONArray()
    }

    private fun openPage(index: Int) {
        val p = pages.getOrNull(index) ?: return
        tracker = ReadingTracker(p.words)
        val engine = engines.getValue(_state.value.engine)
        // nastavení z asr-bench; systémový rozpoznávač nedává hlasitost ani průběžné výsledky v taktu
        filter = HypothesisFilter(
            if (engine.providesAudioLevel) FilterConfig(minSnrDb = 12.0, stableMs = 200)
            else FilterConfig(minSnrDb = 0.0, stableMs = 0)
        )
        pageStart = SystemClock.elapsedRealtime()
        pageLog = JSONArray()
        lastVoiceEnd = 0
        _state.update {
            it.copy(pageIndex = index, statuses = p.words.map { WordStatus.PENDING }, cursor = 0,
                syllablesHeard = 0, heard = "", lastLatencyMs = null)
        }
        startListening()
    }

    private fun startListening() {
        val p = page ?: return
        if (!_state.value.micAllowed || _state.value.finished) return
        val engine = engines.getValue(_state.value.engine)
        val grammar = when {
            _state.value.engine != EngineKind.VOSK -> p.grammar()
            _state.value.grammarMode == GrammarMode.FREE -> null
            _state.value.grammarMode == GrammarMode.DISTRACT -> p.grammar() + distractors
            else -> p.grammar()
        }
        engine.start(grammar, listener)
    }

    private val listener = object : SpeechEngine.Listener {
        override fun onReady() = _state.update { it.copy(listening = true, error = null) }

        override fun onPartial(text: String) = onHypothesis(text, null, final = false)

        override fun onFinal(text: String, words: List<Pair<String, Double>>?) = onHypothesis(text, words, final = true)

        override fun onAudioLevel(rms: Double, atMs: Long) = filter.onAudio(rms, atMs)

        override fun onError(message: String) {
            Log.w(TAG, message)
            _state.update { it.copy(error = message) }
        }

        override fun onVoiceEnd(atMs: Long) {
            lastVoiceEnd = atMs
        }
    }

    private var lastLogged = ""

    private fun onHypothesis(text: String, words: List<Pair<String, Double>>?, final: Boolean) {
        val t = tracker ?: return
        val now = SystemClock.elapsedRealtime()
        val ignore = now < ignoreUntil
        val accepted = if (final) filter.final(text, words, now) else filter.partial(text, now)
        if (final || text != lastLogged) {
            logEvent(if (final) "final" else "partial", "$text → $accepted", ignore)
            lastLogged = if (final) "" else text
        }
        val events = if (final) t.onFinal(accepted, ignore) else t.onPartial(accepted, ignore)
        _state.update { it.copy(heard = text.ifEmpty { it.heard }) }
        handle(events, now, recognized = true)
    }

    /** Tlačítko „dál“: posune o jedno slovo (počítá se jako ruční posun). */
    fun manualAdvance() {
        val t = tracker ?: return
        val idx = t.cursor
        logEvent("manual", idx.toString(), false)
        val events = t.manualAdvance()
        page?.words?.getOrNull(idx)?.let { fireCues(it.cues) }
        handle(events, SystemClock.elapsedRealtime(), recognized = false)
    }

    private fun handle(events: List<TrackEvent>, now: Long, recognized: Boolean) {
        val p = page ?: return
        val t = tracker ?: return
        var latency: Long? = null
        for (e in events) {
            when (e) {
                is TrackEvent.WordRead -> {
                    fireCues(p.words[e.index].cues)
                    if (e.reward) {
                        sessionRewards++
                        playSfx("cink")
                        _state.update { it.copy(stars = it.stars + 1, rewardTick = it.rewardTick + 1) }
                    }
                    if (recognized && e.direct && lastVoiceEnd > 0 && now - lastVoiceEnd in 0..3000) {
                        latency = now - lastVoiceEnd
                        latencies += latency
                    }
                    logEvent("read", "${e.index}:${e.kind}:${if (e.direct) "direct" else "skip"}${if (e.reward) ":reward" else ""}", false)
                }
                is TrackEvent.Skipped -> fireCues(p.words[e.index].cues)
                is TrackEvent.SyllableHeard -> _state.update { it.copy(syllablesHeard = e.syllable + 1) }
                TrackEvent.PageDone -> finishPage()
            }
        }
        _state.update {
            it.copy(statuses = t.status.toList(), cursor = t.cursor,
                syllablesHeard = if (events.any { e -> e is TrackEvent.WordRead || e is TrackEvent.Skipped }) 0 else it.syllablesHeard,
                lastLatencyMs = latency ?: it.lastLatencyMs)
        }
    }

    private fun fireCues(cueIds: List<String>) {
        val p = page ?: return
        for (id in cueIds) {
            val cue = p.cues[id] ?: continue
            playSfx(Sfx.forLayer(cue.layer))
        }
    }

    private fun playSfx(name: String) {
        val dur = sfx.play(name)
        if (dur > 0 && sfx.speakerActive()) {
            ignoreUntil = maxOf(ignoreUntil, SystemClock.elapsedRealtime() + dur + 300)
        }
    }

    private fun finishPage() {
        val t = tracker ?: return
        engines[_state.value.engine]?.stop()
        _state.update { it.copy(listening = false) }
        sessionStats += t.stats()
        log.put(JSONObject().apply {
            put("engine", _state.value.engine.name)
            put("panel", "${page?.chapterTitle}/${page?.panelId}")
            put("words", JSONArray(page?.words?.map { it.text }))
            put("stats", JSONObject().apply {
                val s = t.stats(); put("total", s.total); put("recognized", s.recognized)
                put("skipped", s.skipped); put("manual", s.manual)
            })
            put("events", pageLog)
        })
        playSfx("fanfare")
        viewModelScope.launch {
            delay(1800)
            val next = _state.value.pageIndex + 1
            if (next < pages.size) openPage(next) else finishSession()
        }
    }

    private fun finishSession() {
        val kind = _state.value.engine
        val summary = EngineSummary(kind, modeLabel, sessionStats, sessionRewards, latencies.toList())
        val file = saveLog(summary)
        Log.i(TAG, "Souhrn ${summary.mode}: ${summary.stats} auto=${summary.stats.autoRatio} odezva=${summary.medianLatency} → $file")
        _state.update {
            it.copy(finished = true, listening = false, summaries = it.summaries + (summary.mode to summary), savedLog = file)
        }
    }

    private fun saveLog(summary: EngineSummary): String? = runCatching {
        val dir = File(getApplication<Application>().filesDir, "sessions").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val f = File(dir, "proto-a-${summary.engine.name.lowercase()}-$stamp.json")
        f.writeText(JSONObject().apply {
            put("engine", summary.engine.name)
            put("mode", summary.mode)
            put("autoRatio", summary.stats.autoRatio)
            put("rewards", summary.rewards)
            put("latenciesMs", JSONArray(summary.latencies))
            put("pages", log)
        }.toString(2))
        f.absolutePath
    }.getOrNull()

    private fun logEvent(type: String, text: String, ignored: Boolean) {
        pageLog.put(JSONObject().apply {
            put("t", SystemClock.elapsedRealtime() - pageStart)
            put("type", type)
            put("text", text)
            if (ignored) put("ignored", true)
        })
    }

    override fun onCleared() {
        engines.values.forEach { it.release() }
        sfx.release()
    }

    companion object {
        private const val TAG = "ProtoA"
    }
}
