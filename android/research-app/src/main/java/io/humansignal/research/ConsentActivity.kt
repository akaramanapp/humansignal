package io.humansignal.research

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.humansignal.sdk.HumanSignal

/**
 * Rıza (§4.2 madde 2). Metin `assets/consent_tr.md`'den gelir; sürüm [AppPrefs.CONSENT_VERSION].
 * Veri AB'de (Supabase eu-west-1) saklandığı için yurt dışına aktarım için **ayrı** açık rıza istenir (§7 KVKK notu).
 */
class ConsentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bilgilendirme ve Rıza"
        val text = assets.open("consent_tr.md").bufferedReader().use { it.readText() }
        val general = check(R.id.consent_general, "consent_general", "Yukarıdaki metni okudum; verilerimin bu amaçla işlenmesini kabul ediyorum.", false)
        val abroad = check(R.id.consent_abroad, "consent_abroad", "Verilerimin yurt dışındaki (AB) sunucuda saklanmasına açık rıza veriyorum.", false)
        lateinit var accept: android.widget.Button
        accept = button(R.id.consent_accept, "consent_accept", "Kabul ediyorum") {
            HumanSignal.setConsent(true, AppPrefs.CONSENT_VERSION)
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
        val update = { accept.isEnabled = general.isChecked && abroad.isChecked }
        general.setOnCheckedChangeListener { _, _ -> update() }
        abroad.setOnCheckedChangeListener { _, _ -> update() }
        update()

        setContentView(scroll(column {
            add(TextView(this@ConsentActivity).apply { this.text = renderMarkdown(text); textSize = 15f })
            add(label("Rıza metni sürümü: ${AppPrefs.CONSENT_VERSION}", 12f))
            add(general); add(abroad); add(accept)
            add(button(R.id.consent_decline, "consent_decline", "Kabul etmiyorum") {
                HumanSignal.setConsent(false, AppPrefs.CONSENT_VERSION)
                finishAffinity()
            })
        }))
    }

    /** Basit Markdown → düz metin (başlık işaretleri ve ** kaldırılır). */
    private fun renderMarkdown(md: String) = md.lines().joinToString("\n") { l ->
        l.replace(Regex("^#+\\s*"), "").replace("**", "").replace(Regex("^- "), "• ")
    }
}
