package io.humansignal.research

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.humansignal.sdk.HumanSignal

/** Ekran akışı (§4.1): Ayarlar (ilk açılış) → Rıza → Oturum Kurulumu. Açık oturum varsa görevlere döner. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPrefs(this)
        val st = HumanSignal.status.value
        val next = when {
            !prefs.settingsDone -> SettingsActivity::class.java
            !st.consentGiven -> ConsentActivity::class.java
            st.sessionActive -> TaskActivity::class.java
            else -> SetupActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
