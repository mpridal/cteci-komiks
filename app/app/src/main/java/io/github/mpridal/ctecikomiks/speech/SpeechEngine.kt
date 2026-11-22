package io.github.mpridal.ctecikomiks.speech

/** Rozhraní rozpoznávače (SPEC: SpeechTracker). Výsledky chodí na hlavní vlákno. */
interface SpeechEngine {
    val name: String

    /** Dává rozpoznávač hlasitost zvuku (pro filtr šumu)? */
    val providesAudioLevel: Boolean get() = false

    interface Listener {
        fun onReady() {}
        /** Průběžná hypotéza celé promluvy (u Vosku každých 0,1 s, i beze změny). */
        fun onPartial(text: String)
        /** Konečný výsledek promluvy; [words] = slova s jistotou, když je rozpoznávač dává. */
        fun onFinal(text: String, words: List<Pair<String, Double>>? = null)
        fun onError(message: String)
        /** Hlasitost úseku zvuku (RMS int16), čas v ms (SystemClock.elapsedRealtime). */
        fun onAudioLevel(rms: Double, atMs: Long) {}
        /** Konec hlasu podle hlasitosti (pro odhad odezvy). */
        fun onVoiceEnd(atMs: Long) {}
    }

    /**
     * Začne poslouchat. [grammar] = slova, která smí rozpoznat (null = volné rozpoznávání
     * celým slovníkem modelu). Volá se znovu při každé nové stránce.
     */
    fun start(grammar: List<String>?, listener: Listener)
    fun stop()
    fun release() { stop() }
}
