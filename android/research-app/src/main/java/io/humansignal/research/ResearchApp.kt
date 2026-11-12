package io.humansignal.research

import android.app.Activity
import android.app.Application
import android.content.Intent
import io.humansignal.sdk.HumanSignal
import io.humansignal.sdk.HumanSignalConfig
import kotlin.system.exitProcess

class ResearchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = AppPrefs(this)
        HumanSignal.init(
            this,
            HumanSignalConfig(
                baseUrl = prefs.apiUrl,
                apiKey = prefs.apiKey,
                wifiOnlyUpload = prefs.wifiOnly,
                recordKeyIdentity = prefs.recordKeyIdentity, // §4.2: yalnızca sahte PIN
                debugLogging = BuildConfig.DEBUG,
            ),
        )
    }

    companion object {
        /** SDK yapılandırması `init`'te sabitlenir; ayarlar değişince süreç yeniden başlatılır. */
        fun restart(from: Activity) {
            from.startActivity(Intent(from, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            from.finishAffinity()
            exitProcess(0)
        }
    }
}
