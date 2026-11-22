package io.github.mpridal.ctecikomiks.bench

import org.json.JSONArray
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import javax.sound.sampled.AudioSystem

/** Sonda: co Vosk vrací v průběžném výsledku s setPartialWords(true). Argumenty: <model> <wav> [gramatika json] */
object ProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(args[0])
        val rec = if (args.size > 2) Recognizer(model, 16000f, File(args[2]).readText()) else Recognizer(model, 16000f)
        rec.setWords(true)
        rec.setPartialWords(true)
        val bytes = AudioSystem.getAudioInputStream(File(args[1])).use { it.readAllBytes() }
        var i = 0
        var last = ""
        while (i < bytes.size) {
            val n = minOf(3200, bytes.size - i)
            val chunk = bytes.copyOfRange(i, i + n)
            i += n
            val out = if (rec.acceptWaveForm(chunk, n)) "FINAL " + rec.result else rec.partialResult
            if (out != last) { println("${i / 32}ms $out".replace("\n", " ")); last = out }
        }
        println("END " + rec.finalResult.replace("\n", " "))
        JSONArray()
    }
}
