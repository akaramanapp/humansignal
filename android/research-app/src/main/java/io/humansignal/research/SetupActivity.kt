package io.humansignal.research

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.humansignal.sdk.AgeRange
import io.humansignal.sdk.BotHost
import io.humansignal.sdk.BotTool
import io.humansignal.sdk.Hands
import io.humansignal.sdk.Handedness
import io.humansignal.sdk.HumanSignal
import io.humansignal.sdk.Label
import io.humansignal.sdk.ParticipantInfo
import io.humansignal.sdk.Placement
import io.humansignal.sdk.Posture
import io.humansignal.sdk.SessionMeta
import io.humansignal.sdk.UsageContext
import kotlinx.coroutines.launch

/** Oturum Kurulumu (§4.2 madde 3): etiket, katılımcı, ilk oturum bilgisi, ortam. */
class SetupActivity : AppCompatActivity() {
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Oturum Kurulumu"
        prefs = AppPrefs(this)

        val botBox = column(0)
        val humanBox = column(0)
        val firstBox = column(0)

        val kind = radios(R.id.setup_label_kind, "setup_label_kind", listOf("İnsan" to Label.Kind.HUMAN, "Bot" to Label.Kind.BOT)) { k ->
            botBox.visibility = if (k == Label.Kind.BOT) View.VISIBLE else View.GONE
            humanBox.visibility = if (k == Label.Kind.HUMAN) View.VISIBLE else View.GONE
        }
        val tool = spinner(R.id.setup_bot_tool, "setup_bot_tool", BotTool.entries.map { it.wire to it })
        val host = spinner(R.id.setup_bot_host, "setup_bot_host", BotHost.entries.map { it.wire to it })
        botBox.apply { add(label("Bot aracı")); add(tool); add(label("Host")); add(host); visibility = View.GONE }

        val code = input(R.id.setup_participant_code, "setup_participant_code", "P001", InputType.TYPE_CLASS_TEXT, prefs.suggestNextCode())
        val handed = radios(R.id.setup_handedness, "setup_handedness",
            listOf("Sağ" to Handedness.RIGHT, "Sol" to Handedness.LEFT, "İkisi" to Handedness.BOTH))
        val age = spinner(R.id.setup_age_range, "setup_age_range",
            listOf("18–24" to AgeRange.A18_24, "25–34" to AgeRange.A25_34, "35–44" to AgeRange.A35_44, "45–54" to AgeRange.A45_54,
                "55–64" to AgeRange.A55_64, "65+" to AgeRange.A65_PLUS))
        val gender = spinner(R.id.setup_gender, "setup_gender",
            listOf<Pair<String, String?>>("Belirtmek istemiyorum" to null, "Kadın" to "female", "Erkek" to "male", "Diğer" to "other"))
        firstBox.apply {
            add(label("Katılımcı bilgisi (yalnızca ilk oturum)"))
            add(label("El tercihi", 13f)); add(handed)
            add(label("Yaş aralığı", 13f)); add(age)
            add(label("Cinsiyet (opsiyonel)", 13f)); add(gender)
        }
        val refreshFirst = { firstBox.visibility = if (code.text.toString().trim() in prefs.knownParticipants) View.GONE else View.VISIBLE }
        code.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = refreshFirst()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        humanBox.apply { add(label("Katılımcı kodu (P + 3 rakam)")); add(code); add(firstBox); visibility = View.GONE }
        refreshFirst()

        val placement = radios(R.id.setup_placement, "setup_placement",
            listOf("Elde" to Placement.HAND, "Masada" to Placement.TABLE, "Tutucuda" to Placement.HOLDER, "Kucakta" to Placement.LAP))
        val posture = radios(R.id.setup_posture, "setup_posture",
            listOf("Oturuyor" to Posture.SITTING, "Ayakta" to Posture.STANDING, "Yürüyor" to Posture.WALKING, "Uzanıyor" to Posture.LYING))
        val hands = radios(R.id.setup_hands, "setup_hands",
            listOf("Tek el (sol)" to Hands.ONE_LEFT, "Tek el (sağ)" to Hands.ONE_RIGHT, "İki el" to Hands.TWO))

        setContentView(scroll(column {
            add(title("Oturum Kurulumu"))
            add(label("Etiket")); add(kind)
            add(botBox); add(humanBox)
            add(label("Telefonun konumu")); add(placement)
            add(label("Duruş")); add(posture)
            add(label("El kullanımı")); add(hands)
            add(button(R.id.setup_start, "setup_start", "Oturumu başlat") {
                start(kind.picked(), tool.pickedValue(), host.pickedValue(), code.text.toString().trim(),
                    handed.picked(), age.pickedValue(), gender.pickedValue(), placement.picked(), posture.picked(), hands.picked(),
                    firstBox.visibility == View.VISIBLE)
            })
            add(label("Geliştirici", 13f))
            add(row {
                add(button(R.id.dev_live_sensor, "dev_live_sensor", "Canlı Sensör") { startActivity(Intent(this@SetupActivity, LiveSensorActivity::class.java)) },
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                add(button(R.id.dev_clock_test, "dev_clock_test", "Saat Testi") { startActivity(Intent(this@SetupActivity, ClockTestActivity::class.java)) },
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                add(button(R.id.dev_settings, "dev_settings", "Ayarlar") { startActivity(Intent(this@SetupActivity, SettingsActivity::class.java)) },
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }))
    }

    private fun start(
        kind: Label.Kind?, tool: BotTool, host: BotHost, code: String, handed: Handedness?, age: AgeRange, gender: String?,
        placement: Placement?, posture: Posture?, hands: Hands?, firstSession: Boolean,
    ) {
        if (kind == null) return toast("Etiket seçin")
        val isHuman = kind == Label.Kind.HUMAN
        if (isHuman && !AppPrefs.CODE_RE.matches(code)) return toast("Katılımcı kodu P + 3 rakam olmalı (ör. P007)")
        if (isHuman && firstSession && handed == null) return toast("El tercihini seçin")
        // Bot oturumlarında ortam alanları boş bırakılabilir → "other"/"na".
        if (isHuman && (placement == null || posture == null || hands == null)) return toast("Ortam bilgilerini doldurun")

        val meta = SessionMeta(
            participantCode = if (isHuman) code else null,
            label = if (isHuman) Label(kind) else Label(kind, tool, host),
            context = UsageContext(placement ?: Placement.OTHER, posture ?: Posture.NA, hands ?: Hands.NA),
            participantInfo = if (isHuman && firstSession) ParticipantInfo(handed, age, gender) else null,
        )
        lifecycleScope.launch {
            try {
                HumanSignal.startSession(meta)
                if (isHuman) prefs.addParticipant(code)
                startActivity(Intent(this@SetupActivity, TaskActivity::class.java))
                finish()
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
