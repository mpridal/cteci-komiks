package io.github.mpridal.ctecikomiks

import android.app.Application
import io.github.mpridal.ctecikomiks.remote.AppLog
import io.github.mpridal.ctecikomiks.remote.LogUploader
import io.github.mpridal.ctecikomiks.remote.TokenStore
import io.github.mpridal.ctecikomiks.update.Updater

/** Aplikace drží jedinou instanci aktualizace a odesílání záznamů (bez DI frameworku). */
class CteciApp : Application() {
    lateinit var log: AppLog
        private set
    lateinit var updater: Updater
        private set
    lateinit var uploader: LogUploader
        private set

    override fun onCreate() {
        super.onCreate()
        log = AppLog(this)
        log.i("start ${BuildConfig.VERSION_NAME} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE})")
        Thread.getDefaultUncaughtExceptionHandler().let { previous ->
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                log.e("pád: ${e.stackTraceToString().take(4000)}")
                previous?.uncaughtException(t, e)
            }
        }
        updater = Updater(this, log, BuildConfig.UPDATE_REPO, BuildConfig.VERSION_NAME)
        uploader = LogUploader(this, log, TokenStore(this), BuildConfig.LOG_REPO)
    }
}
