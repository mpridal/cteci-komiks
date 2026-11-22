package io.github.mpridal.ctecikomiks.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.github.mpridal.ctecikomiks.CteciApp
import io.github.mpridal.ctecikomiks.core.update.Release
import io.github.mpridal.ctecikomiks.core.update.ReleaseAsset
import io.github.mpridal.ctecikomiks.core.update.SemVer
import io.github.mpridal.ctecikomiks.core.update.UpdateDecision
import io.github.mpridal.ctecikomiks.core.update.UpdatePolicy
import io.github.mpridal.ctecikomiks.remote.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

// Převzato z ThermoCamera (mpridal/thermocamera, app/update/Updater.kt), upraveno:
// bez Hilt a nastavení, repo a verze jako parametry, deník AppLog.

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloaded(val version: String, val file: File) : UpdateState
    data object Installing : UpdateState
}

/**
 * Aktualizace z veřejných GitHub Releases (bez tokenu). Při startu na pozadí zkontroluje poslední
 * vydání, stáhne APK, ověří SHA-256 a podpis; instalaci potvrdí uživatel (Android to vyžaduje).
 */
class Updater(
    private val context: Context,
    private val log: AppLog,
    private val repo: String,
    private val installedVersion: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val policy = UpdatePolicy("cteci-komiks")
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var started = false

    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    fun checkOnStart() {
        if (started) return
        started = true
        checkNow()
    }

    fun checkNow() {
        scope.launch {
            if (!online()) return@launch
            runCatching { check() }.onFailure { log.w("aktualizace: kontrola selhala: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun check() {
        when (val d = policy.decide(installedVersion, fetchLatest())) {
            UpdateDecision.UpToDate -> cleanup(keep = null)
            is UpdateDecision.Ignore -> log.i("aktualizace: ${d.reason}")
            is UpdateDecision.Download -> download(d.version, d.apk, d.sha256)
        }
    }

    private fun fetchLatest(): Release? {
        val c = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "cteci-komiks-updater")
        }
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) { log.w("aktualizace: releases/latest HTTP ${c.responseCode}"); return null }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = o.optJSONArray("assets")
            return Release(
                tag = o.optString("tag_name"),
                draft = o.optBoolean("draft"),
                prerelease = o.optBoolean("prerelease"),
                assets = (0 until (assets?.length() ?: 0)).map { i ->
                    val a = assets!!.getJSONObject(i)
                    ReleaseAsset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
                },
            )
        } finally {
            c.disconnect()
        }
    }

    private fun download(version: SemVer, apk: ReleaseAsset, shaAsset: ReleaseAsset) {
        val target = File(dir, policy.apkName(version))
        val expected = UpdatePolicy.parseSha256(httpText(shaAsset.url)) ?: run { log.w("aktualizace: vadný kontrolní součet"); return }
        cleanup(keep = target)
        if (!(target.exists() && target.length() == apk.size && UpdatePolicy.sha256(target) == expected)) {
            log.i("aktualizace: stahuji ${apk.name} (${apk.size / 1_000_000} MB)")
            httpDownload(apk.url, target)
        }
        val actual = UpdatePolicy.sha256(target)
        if (actual != expected) {
            log.w("aktualizace: SHA-256 nesouhlasí pro ${target.name} – smazáno")
            target.delete()
            return
        }
        if (!sameSignerAsInstalled(target)) {
            log.w("aktualizace: jiný podpis ${target.name} – smazáno (debug build? odinstalovat a nainstalovat vydání)")
            target.delete()
            return
        }
        log.i("aktualizace: stažena a ověřena verze $version")
        _state.value = UpdateState.Downloaded(version.toString(), target)
    }

    private fun httpText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 10_000; instanceFollowRedirects = true }
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun httpDownload(url: String, target: File) {
        val have = if (target.exists()) target.length() else 0L
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resume = have > 0 && c.responseCode == 206
            RandomAccessFile(target, "rw").use { raf ->
                if (resume) raf.seek(have) else raf.setLength(0)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun cleanup(keep: File?) {
        dir.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): List<ByteArray> {
        info ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si -> (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.map { it.toByteArray() } }.orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
    }

    @Suppress("DEPRECATION")
    private fun sameSignerAsInstalled(apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = signatures(pm.getPackageInfo(context.packageName, flags))
        val downloaded = signatures(pm.getPackageArchiveInfo(apk.path, flags))
        val pkgOk = pm.getPackageArchiveInfo(apk.path, 0)?.packageName == context.packageName
        return pkgOk && downloaded.isNotEmpty() && downloaded.all { d -> installed.any { it.contentEquals(d) } }
    }

    /** Smí aplikace instalovat balíčky („Instalace neznámých aplikací“)? */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install() {
        val s = _state.value as? UpdateState.Downloaded ?: return
        _state.value = UpdateState.Installing
        scope.launch {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, s.file.length()).use { out -> s.file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
                log.i("aktualizace: instalace ${s.file.name} odeslána")
            }.onFailure {
                log.e("aktualizace: instalace selhala: ${it.message}")
                _state.value = s
            }
        }
    }

    fun onInstallFinished(ok: Boolean, message: String?) {
        log.i("aktualizace: výsledek ok=$ok ${message.orEmpty()}")
        if (!ok) (_state.value as? UpdateState.Installing)?.let { _state.value = UpdateState.Idle }
    }
}

/** Výsledek PackageInstalleru; když je potřeba potvrzení uživatele, otevře systémový dialog. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updater = (context.applicationContext as CteciApp).updater
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallFinished(true, null)
            else -> updater.onInstallFinished(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
