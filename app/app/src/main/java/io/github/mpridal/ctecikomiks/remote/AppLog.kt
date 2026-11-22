package io.github.mpridal.ctecikomiks.remote

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime

/**
 * Deník událostí aplikace (start, chyby, pády, aktualizace, odesílání) v files/app-log.txt.
 * Odesílá se spolu se záznamy relací do soukromého repa logů. Neobsahuje zvuk ani text čtení.
 */
class AppLog(context: Context) {
    val file = File(context.filesDir, "app-log.txt")

    @Synchronized
    private fun write(level: String, msg: String) {
        runCatching {
            if (file.length() > 256_000) file.writeText(file.readLines().takeLast(1000).joinToString("\n") + "\n")
            file.appendText("${LocalDateTime.now()} $level $msg\n")
        }
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); write("W", msg) }
    fun e(msg: String) { Log.e(TAG, msg); write("E", msg) }

    fun read(): String = runCatching { file.readText() }.getOrDefault("")

    companion object { private const val TAG = "CteciKomiks" }
}
