package io.humansignal.research

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.humansignal.sdk.BotHost
import io.humansignal.sdk.BotTool
import io.humansignal.sdk.CollectorStatus
import io.humansignal.sdk.Hands
import io.humansignal.sdk.HumanSignal
import io.humansignal.sdk.Label
import io.humansignal.sdk.Placement
import io.humansignal.sdk.Posture
import io.humansignal.sdk.SessionMeta
import io.humansignal.sdk.UsageContext
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Canlı Sensör (geliştirici) ekranı (§4.2 madde 6): her sensör için anlık Hz ve son değerler,
 * kuyruk durumu. M1 demosu: burada acc/gyr için ~100 Hz görülmeli.
 */
class LiveSensorActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var table: TableLayout
    private lateinit var footer: TextView
    private lateinit var consentBtn: Button
    private lateinit var sessionBtn: Button
    private var markCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Canlı Sensör"
        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        header = mono(14f).also { root.addView(it) }

        consentBtn = button("Rıza") { toggleConsent() }
        sessionBtn = button("Oturum başlat") { toggleSession() }
        root.addView(row(consentBtn, sessionBtn))
        root.addView(row(
            button("İşaret koy") {
                markCount++
                HumanSignal.mark("dev_mark", mapOf("n" to markCount.toString()))
                toast("mark #$markCount")
            },
            button("Yerel veriyi sil") {
                HumanSignal.deleteLocalData()
                toast("Yerel veri silindi")
            },
        ))

        root.addView(row(
            button("Saat Testi") { startActivity(android.content.Intent(this, ClockTestActivity::class.java)) },
            button("Giriş Testi") { startActivity(android.content.Intent(this, InputTestActivity::class.java)) },
        ))

        table = TableLayout(this).apply {
            setPadding(0, pad, 0, pad)
            isStretchAllColumns = false
            setColumnShrinkable(3, true)
        }
        root.addView(table)
        footer = mono(13f).also { root.addView(it) }

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HumanSignal.status.collect { render(it) }
            }
        }
    }

    private fun toggleConsent() {
        val given = HumanSignal.status.value.consentGiven
        HumanSignal.setConsent(!given, CONSENT_VERSION)
    }

    private fun toggleSession() {
        sessionBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                if (HumanSignal.status.value.sessionActive) {
                    HumanSignal.stopSession()
                } else {
                    // M1 geliştirici oturumu. M4'te Oturum Kurulumu ekranı gerçek etiketi soracak.
                    HumanSignal.startSession(
                        SessionMeta(
                            participantCode = null,
                            label = Label(Label.Kind.BOT, BotTool.OTHER, if (isEmulator()) BotHost.EMULATOR_ANDROID_STUDIO else BotHost.REAL_DEVICE),
                            context = UsageContext(Placement.OTHER, Posture.NA, Hands.NA),
                            notes = "m1-live-sensor-dev",
                        ),
                    )
                }
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            } finally {
                sessionBtn.isEnabled = true
            }
        }
    }

    private fun render(s: CollectorStatus) {
        consentBtn.text = if (s.consentGiven) "Rızayı geri al" else "Rıza ver (geliştirici)"
        sessionBtn.text = if (s.sessionActive) "Oturumu durdur" else "Oturum başlat"
        sessionBtn.isEnabled = s.consentGiven || s.sessionActive

        header.text = buildString {
            append("SDK ").append(HumanSignal.SDK_VERSION)
            append("  ·  rıza: ").append(if (s.consentGiven) "VAR" else "YOK")
            append("\noturum: ")
            append(
                when {
                    !s.sessionActive -> "kapalı"
                    s.paused -> "DURAKLATILDI (arka plan)"
                    else -> "AÇIK"
                },
            )
            s.localSessionId?.let { append("\nid: ").append(it.take(8)).append('…') }
        }

        table.removeAllViews()
        table.addView(tableRow(listOf("akış", "hedef", "Hz", "son değerler", "saat"), header = true))
        for (x in s.sensors) {
            val r = tableRow(
                listOf(
                    x.stream,
                    x.targetHz?.toString() ?: "değ.",
                    String.format(Locale.US, "%.1f", x.actualHz),
                    x.lastValues.joinToString(" ") { String.format(Locale.US, "%.3f", it) },
                    x.clockBasis ?: "-",
                ),
            )
            val hzCell = r.getChildAt(2) as TextView
            x.targetHz?.let { target ->
                hzCell.setTextColor(
                    when {
                        x.actualHz >= target * 0.9 -> Color.rgb(0x2e, 0x7d, 0x32)
                        x.actualHz >= target * 0.5 -> Color.rgb(0xef, 0x6c, 0x00)
                        else -> Color.rgb(0xc6, 0x28, 0x28)
                    },
                )
            }
            table.addView(r)
        }

        footer.text = buildString {
            if (s.sensorsMissing.isNotEmpty()) append("eksik sensörler: ").append(s.sensorsMissing.joinToString()).append('\n')
            append("dokunma: ").append(String.format(Locale.US, "%.1f", s.touchEventsPerSec)).append(" satır/sn")
            append("  ·  toplam ").append(s.touchEvents).append('\n')
            append("yazılan parça: ").append(s.chunksWritten)
            append("  ·  kuyrukta: ").append(s.pendingChunks)
            append("  ·  düşürülen: ").append(s.droppedChunks)
            append("\nyerel depolama: ").append(String.format(Locale.US, "%.1f KB", s.localStorageBytes / 1024.0))
            append("\nsunucu: ").append(s.serverSessionId?.take(8)?.plus("…") ?: if (s.sessionActive) "kayıt bekleniyor" else "-")
            s.uploadError?.let { append("\nyükleme hatası: ").append(it) }
            s.uploadRetryInSec?.let { append("\nyükleme yeniden deneme: ").append(it).append(" sn") }
            s.lastError?.let { append("\nson hata: ").append(it) }
        }
    }

    // ---- küçük UI yardımcıları ----

    private fun tableRow(cells: List<String>, header: Boolean = false) = TableRow(this).apply {
        cells.forEachIndexed { i, c ->
            addView(mono(12f).apply {
                text = c
                setPadding(0, dp(2), dp(10), dp(2))
                if (header) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                if (i == 1 || i == 2) gravity = Gravity.END
            })
        }
    }

    private fun mono(sizeSp: Float) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = sizeSp
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun isEmulator(): Boolean {
        val f = android.os.Build.FINGERPRINT.lowercase()
        return f.contains("generic") || f.contains("emulator") || android.os.Build.MODEL.contains("sdk_gphone")
    }

    companion object {
        const val CONSENT_VERSION = "2026-11-v1"
    }
}
