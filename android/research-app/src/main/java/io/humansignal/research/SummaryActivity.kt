package io.humansignal.research

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.humansignal.sdk.HumanSignal
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/** Özet (§4.2 madde 5): süre, parça sayısı, gerçekleşen sensör Hz, yükleme durumu. */
class SummaryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Özet"
        val body = TextView(this).apply {
            id = R.id.summary_text; contentDescription = "summary_text"; typeface = Typeface.MONOSPACE; textSize = 15f
        }
        val upload = TextView(this).apply {
            id = R.id.summary_upload; contentDescription = "summary_upload"; textSize = 17f; setPadding(0, dp(12), 0, dp(12))
        }
        setContentView(scroll(column {
            add(title("Oturum tamamlandı"))
            add(body); add(upload)
            add(button(R.id.summary_new_session, "summary_new_session", "Yeni oturum") {
                startActivity(Intent(this@SummaryActivity, SetupActivity::class.java)); finish()
            })
        }))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HumanSignal.lastSummary.combine(HumanSignal.status) { s, st -> s to st }.collect { (s, st) ->
                    body.text = if (s == null) "Özet yok." else buildString {
                        append("Süre            : ").append(String.format(Locale.US, "%.0f sn (%.1f dk)", s.durationS, s.durationS / 60)).append('\n')
                        append("Parça           : ").append(s.totalChunks).append('\n')
                        append("Dokunma satırı  : ").append(s.touchEvents).append('\n')
                        append("Bitiş nedeni    : ").append(s.stopReason.wire).append('\n')
                        append("Oturum (sunucu) : ").append(s.serverSessionId?.take(8)?.plus("…") ?: "henüz kayıtlı değil").append('\n')
                        append("\nSensör (ortalama Hz)\n")
                        s.sensorHz.forEach { (k, v) -> append(String.format(Locale.US, "  %-6s %6.1f\n", k, v)) }
                        if (s.sensorsMissing.isNotEmpty()) append("  eksik: ").append(s.sensorsMissing.joinToString()).append('\n')
                    }
                    upload.text = when {
                        st.uploadError != null -> "Yükleme durdu: ${st.uploadError}"
                        st.pendingChunks == 0 -> "✓ Tüm veriler yüklendi"
                        st.uploadRetryInSec != null -> "Yükleniyor… ${st.pendingChunks} parça bekliyor (yeniden deneme ${st.uploadRetryInSec} sn)"
                        else -> "Yükleniyor… ${st.pendingChunks} parça bekliyor"
                    }
                }
            }
        }
    }
}
