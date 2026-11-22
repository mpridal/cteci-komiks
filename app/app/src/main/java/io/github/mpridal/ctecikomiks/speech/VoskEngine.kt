package io.github.mpridal.ctecikomiks.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.sqrt

/**
 * Vosk s gramatikou stránky. Mikrofon vždy vestavěný (VOICE_RECOGNITION), nikdy Bluetooth SCO.
 * Vše offline, zvuk se nikam neukládá.
 */
class VoskEngine(private val context: Context) : SpeechEngine {
    override val name = "Vosk"
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var pending: Pair<List<String>?, SpeechEngine.Listener>? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    override val providesAudioLevel = true

    override fun start(grammar: List<String>?, listener: SpeechEngine.Listener) {
        stop()
        val m = model
        if (m == null) {
            pending = grammar to listener
            if (!loading) {
                loading = true
                StorageService.unpack(context, "model-cs", "model",
                    { loaded ->
                        model = loaded
                        loading = false
                        pending?.let { (g, l) -> pending = null; start(g, l) }
                    },
                    { e ->
                        loading = false
                        listener.onError("Model se nenačetl: ${e.message}")
                    })
            }
            return
        }
        val recognizer = try {
            // null = volné rozpoznávání celým slovníkem (cizí řeč pak nevychází jako slova stránky)
            (if (grammar != null) Recognizer(m, SAMPLE_RATE.toFloat(), JSONArray(grammar).toString())
            else Recognizer(m, SAMPLE_RATE.toFloat())).apply { setWords(true) } // ne setPartialWords: zhoršuje rozpoznávání (asr-bench)
        } catch (e: Exception) {
            listener.onError("Recognizer: ${e.message}")
            return
        }
        running = true
        thread = Thread({ loop(recognizer, listener) }, "vosk-audio").apply { start() }
        main.post { listener.onReady() }
    }

    @SuppressLint("MissingPermission")
    private fun loop(recognizer: Recognizer, listener: SpeechEngine.Listener) {
        val record = openRecord()
        if (record == null) {
            recognizer.close()
            main.post { listener.onError("Mikrofon se nepodařilo otevřít") }
            return
        }
        builtInMic()?.let { record.setPreferredDevice(it) }
        val buf = ShortArray(CHUNK)
        val vad = VoiceEndDetector()
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                val rms = rms(buf, n)
                vad.feed(rms, now)?.let { t -> main.post { listener.onVoiceEnd(t) } }
                main.post { listener.onAudioLevel(rms, now) }
                if (recognizer.acceptWaveForm(buf, n)) {
                    val res = JSONObject(recognizer.result)
                    val text = res.optString("text")
                    val words = res.optJSONArray("result")?.let { a ->
                        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("word") to it.optDouble("conf", 1.0) } }
                    }
                    main.post { listener.onFinal(text, words) }
                } else {
                    // každý úsek, i beze změny – filtr šumu potřebuje čas, jak dlouho je slovo stabilní
                    val pr = JSONObject(recognizer.partialResult)
                    val p = pr.optString("partial")
                    val pw = pr.optJSONArray("partial_result")?.let { a ->
                        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("word") to it.optDouble("conf", 1.0) } }
                    }
                    main.post { listener.onPartial(p, pw) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio", e)
            main.post { listener.onError("Mikrofon: ${e.message}") }
        } finally {
            runCatching { record.stop() }
            record.release()
            recognizer.close()
        }
    }

    /** VOICE_RECOGNITION, při selhání obyčejný MIC (některá zařízení a emulátor). Nikdy Bluetooth SCO. */
    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            repeat(3) { attempt ->
                val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord zdroj=$source pokus=$attempt minBuf=$minBuf")
                    return r
                }
                Log.w(TAG, "AudioRecord nelze inicializovat (zdroj=$source, pokus=$attempt, minBuf=$minBuf)")
                r.release()
                Thread.sleep(200)
            }
        }
        return null
    }

    private fun builtInMic(): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun release() {
        stop()
        model?.close()
        model = null
    }

    /** Jednoduchý detektor konce hlasu podle hlasitosti (odhad odezvy pro bránu „pod 1 s“). */
    private class VoiceEndDetector {
        private var floor = 300.0
        private var speaking = false
        private var quietSince = 0L

        fun feed(rms: Double, now: Long): Long? {
            if (!speaking) floor = 0.95 * floor + 0.05 * minOf(rms, floor * 2)
            val loud = rms > maxOf(400.0, floor * 3)
            if (loud) {
                speaking = true
                quietSince = 0
            } else if (speaking) {
                if (quietSince == 0L) quietSince = now
                if (now - quietSince >= 120) {
                    speaking = false
                    return quietSince
                }
            }
            return null
        }
    }

    companion object {
        private fun rms(buf: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            return sqrt(sum / n)
        }

        private const val TAG = "VoskEngine"
        const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 0,1 s
    }
}
