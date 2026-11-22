package io.github.mpridal.ctecikomiks.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Systémový rozpoznávač Androidu (cs-CZ), pro srovnání s Voskem.
 * Poslouchá v kole: po každém výsledku nebo chybě se znovu spustí.
 */
class SystemEngine(private val context: Context) : SpeechEngine {
    override val name = "Systém"
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechEngine.Listener? = null
    private var grammar: List<String> = emptyList()
    private var active = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(grammar: List<String>?, listener: SpeechEngine.Listener) {
        stop()
        this.grammar = grammar ?: emptyList()
        this.listener = listener
        if (!isAvailable) {
            listener.onError("Systémový rozpoznávač není v zařízení dostupný")
            return
        }
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)).apply { setRecognitionListener(callback) }
        active = true
        listen()
        listener.onReady()
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            if (Build.VERSION.SDK_INT >= 33 && grammar.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(grammar.filter { it != "[unk]" }))
            }
        }
        recognizer?.startListening(intent)
    }

    private val callback = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            listener?.onFinal(best(results) ?: "")
            listen()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {}
                else -> listener?.onError("Systémový rozpoznávač: chyba $error")
            }
        }
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun stop() {
        active = false
        recognizer?.run { cancel(); destroy() }
        recognizer = null
    }
}
