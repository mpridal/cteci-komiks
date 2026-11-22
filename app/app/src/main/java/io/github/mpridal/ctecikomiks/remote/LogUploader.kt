package io.github.mpridal.ctecikomiks.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import io.github.mpridal.ctecikomiks.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Odesílá záznamy relací (files/sessions) a deník aplikace do SOUKROMÉHO repa logů na GitHubu
 * (GitHub Contents API, token jen pro toto repo). Co neodešle, zkusí znovu při dalším startu.
 * Záznamy obsahují přepis čtení a hlasitost, nikdy zvuk.
 */
class LogUploader(
    private val context: Context,
    private val log: AppLog,
    private val tokens: TokenStore,
    private val repo: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val sessionsDir get() = File(context.filesDir, "sessions")
    private val sentFile get() = File(context.filesDir, "uploaded.txt")
    private val _status = MutableStateFlow(if (tokens.hasToken) "připraveno" else "bez tokenu")
    val status: StateFlow<String> = _status

    private val device: String
        get() = "${Build.MODEL}-${tokens.installId}".replace(Regex("[^A-Za-z0-9_-]"), "_")

    val hasToken: Boolean get() = tokens.hasToken

    fun setToken(token: String) {
        tokens.save(token)
        log.i("token pro logy uložen")
        uploadPending()
    }

    fun clearToken() {
        tokens.clear()
        _status.value = "bez tokenu"
    }

    /** Odešle vše neodeslané (záznamy relací + deník). Bezpečné volat kdykoli. */
    fun uploadPending() {
        scope.launch { mutex.withLock { runCatching { doUpload() }.onFailure { fail(it) } } }
    }

    private fun fail(e: Throwable) {
        _status.value = "chyba: ${e.message}"
        log.w("odeslání logů selhalo: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun doUpload() {
        val token = tokens.load() ?: run { _status.value = "bez tokenu"; return }
        if (!online()) { _status.value = "offline, odešle se později"; return }
        val sent = if (sentFile.exists()) sentFile.readLines().toMutableSet() else mutableSetOf()
        val pending = sessionsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { it.name !in sent }.sortedBy { it.name }
        var n = 0
        for (f in pending) {
            put(token, "sessions/$device/${f.name}", f.readBytes(), "záznam ${f.name}")
            sentFile.appendText(f.name + "\n")
            n++
        }
        // deník: snímek jako nový soubor, jen když se od posledního odeslání změnil
        val logText = log.read()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val lastLen = context.getSharedPreferences("remote", Context.MODE_PRIVATE).getLong("logLen", -1)
        if (logText.isNotEmpty() && logText.length.toLong() != lastLen) {
            put(token, "logs/$device/app-log-$stamp.txt", logText.toByteArray(), "deník ${BuildConfig.VERSION_NAME}")
            context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putLong("logLen", logText.length.toLong()).apply()
        }
        _status.value = "odesláno ($n nových záznamů)"
        if (n > 0) log.i("odesláno $n záznamů do $repo")
    }

    private fun put(token: String, path: String, content: ByteArray, message: String) {
        val c = (URL("https://api.github.com/repos/$repo/contents/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "cteci-komiks-logs")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("message", message)
                .put("content", Base64.encodeToString(content, Base64.NO_WRAP)).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            // 422 = soubor už existuje (odesláno dříve) → bereme jako hotovo
            if (code !in 200..299 && code != 422) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(200)
                throw IllegalStateException("HTTP $code $err")
            }
        } finally {
            c.disconnect()
        }
    }
}
