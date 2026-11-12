package io.humansignal.research

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.humansignal.sdk.ClockTestState
import io.humansignal.sdk.HumanSignal
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Saat Testi (geliştirici, §4.2 madde 7): "Ekrana 10 kez sertçe vurun." Her vuruşta touch DOWN ile en yakın ivme
 * sıçraması arasındaki fark gösterilir. Kabul: medyan |fark| < 20 ms. Açık oturum gerekir.
 */
class ClockTestActivity : AppCompatActivity() {
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Saat Testi"
        val pad = (16 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val restart = Button(this).apply {
            text = "Yeniden başlat"
            isAllCaps = false
            setOnClickListener { start() }
            id = R.id.clock_test_restart
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(restart)
            addView(info, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { HumanSignal.clockTest.collect { render(it) } }
        }
    }

    private fun start() {
        if (!HumanSignal.status.value.sessionActive) {
            info.text = "Önce Canlı Sensör ekranından bir oturum başlatın."
            return
        }
        HumanSignal.startClockTest(10)
    }

    override fun onDestroy() {
        HumanSignal.stopClockTest()
        super.onDestroy()
    }

    private fun render(s: ClockTestState?) {
        if (s == null || !HumanSignal.status.value.sessionActive) return
        val done = s.tapsAnalyzed + s.tapsWithoutSpike
        info.text = buildString {
            append("Telefonu masaya koyun ve ekrana\n${s.targetTaps} kez sertçe vurun.\n\n")
            append("vuruş: $done / ${s.targetTaps}\n\n")
            s.diffsMs.forEachIndexed { i, d -> append(String.format(Locale.US, "#%-2d  %+7.1f ms\n", i + 1, d)) }
            if (s.tapsWithoutSpike > 0) append("sıçrama bulunamayan: ${s.tapsWithoutSpike}\n")
            s.medianAbsMs?.let { append(String.format(Locale.US, "\nmedyan |fark|: %.1f ms\n", it)) }
            when (s.passed) {
                true -> append("\nGEÇTİ (< 20 ms)")
                false -> append("\nKALDI (≥ 20 ms)")
                null -> {}
            }
        }
        info.setTextColor(
            when (s.passed) {
                true -> Color.rgb(0x2e, 0x7d, 0x32)
                false -> Color.rgb(0xc6, 0x28, 0x28)
                null -> Color.DKGRAY
            },
        )
    }
}
