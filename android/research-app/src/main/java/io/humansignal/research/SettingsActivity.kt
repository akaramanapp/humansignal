package io.humansignal.research

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity

/** Ayarlar (§4.2 madde 1). Kaydedince süreç yeniden başlar (SDK yapılandırması init'te sabitlenir). */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ayarlar"
        val p = AppPrefs(this)
        val url = input(R.id.settings_api_url, "settings_api_url", "API adresi", InputType.TYPE_TEXT_VARIATION_URI, p.apiUrl)
        val key = input(R.id.settings_api_key, "settings_api_key", "API anahtarı",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, p.apiKey)
        val wifi = check(R.id.settings_wifi_only, "settings_wifi_only", "Sadece Wi-Fi'da yükle", p.wifiOnly)
        val kid = check(R.id.settings_record_key_identity, "settings_record_key_identity",
            "Tuş kimliğini kaydet (yalnızca SAHTE PIN görevleri)", p.recordKeyIdentity)

        setContentView(scroll(column {
            add(title("Ayarlar"))
            add(label("API adresi")); add(url)
            add(label("API anahtarı (uygulama rolü)")); add(key)
            add(wifi); add(kid)
            add(label("Değişiklikler uygulamayı yeniden başlatır.", 13f))
            add(button(R.id.settings_save, "settings_save", "Kaydet") {
                val changed = p.apiUrl != url.text.toString().trim() || p.apiKey != key.text.toString().trim() ||
                    p.wifiOnly != wifi.isChecked || p.recordKeyIdentity != kid.isChecked
                p.apiUrl = url.text.toString(); p.apiKey = key.text.toString()
                p.wifiOnly = wifi.isChecked; p.recordKeyIdentity = kid.isChecked
                p.settingsDone = true
                if (changed) ResearchApp.restart(this@SettingsActivity)
                else { startActivity(android.content.Intent(this@SettingsActivity, LauncherActivity::class.java)); finish() }
            })
        }))
    }
}
