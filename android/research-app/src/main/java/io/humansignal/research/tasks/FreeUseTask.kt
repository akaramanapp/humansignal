package io.humansignal.research.tasks

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import io.humansignal.research.Marks
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.add
import io.humansignal.research.button
import io.humansignal.research.column
import io.humansignal.research.dp
import io.humansignal.research.label
import io.humansignal.research.scroll
import io.humansignal.research.title

/** T7 Serbest kullanım: 60 saniye boyunca bir haber listesinde serbest gezinme; 1 deneme. */
class FreeUseTask : Task("t7", "free_use", "Serbest kullanım", 1) {
    private var container: FrameLayout? = null
    private var endAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val c = container ?: return
            val left = ((endAt - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0)
            host.setInstruction("Haberlerde serbestçe gezinin. Kalan süre: $left sn")
            if (left <= 0) { container = null; host.trialEnded("ok"); return }
            c.postDelayed(this, 1000)
        }
    }

    override fun startTrial(trial: Int, container: FrameLayout) {
        this.container = container
        endAt = SystemClock.uptimeMillis() + DURATION_MS
        showList()
        container.post { host.trialStarted(mapOf("duration_s" to (DURATION_MS / 1000).toString())); ticker.run() }
    }

    private fun showList() {
        val c = container ?: return
        c.removeAllViews()
        Marks.screen("t7_list")
        val ctx = c.context
        val list = ListView(ctx).apply {
            id = R.id.task_t7_list; contentDescription = "task_t7_list"
            adapter = object : BaseAdapter() {
                override fun getCount() = HEADLINES.size * 5
                override fun getItem(p: Int) = p
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, convert: View?, parent: ViewGroup): View =
                    ((convert as? TextView) ?: TextView(ctx).apply { textSize = 17f; setPadding(ctx.dp(12), ctx.dp(16), ctx.dp(12), ctx.dp(16)) }).apply {
                        text = "${p + 1}. ${HEADLINES[p % HEADLINES.size]}"
                        contentDescription = "task_t7_item_${p + 1}"
                    }
            }
            setOnItemClickListener { _, _, pos, _ -> showDetail(pos) }
        }
        c.addView(list, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun showDetail(pos: Int) {
        val c = container ?: return
        c.removeAllViews()
        Marks.screen("t7_detail")
        val ctx = c.context
        c.addView(ctx.scroll(ctx.column(0) {
            add(ctx.button(R.id.task_t7_back, "task_t7_back", "← Geri") { showList() })
            add(ctx.title(HEADLINES[pos % HEADLINES.size]))
            add(ctx.label(LOREM.repeat(6), 16f))
        }))
    }

    override fun cleanup() {
        container?.removeCallbacks(ticker)
        container = null
    }

    private companion object {
        const val DURATION_MS = 60_000L
        val HEADLINES = listOf(
            "Merkez bankası faiz kararını açıkladı", "Yeni metro hattı hizmete girdi", "Hafta sonu yağış bekleniyor",
            "Yerli otomobilde yeni model tanıtıldı", "Müzede yeni sergi kapılarını açtı", "Liglerde haftanın sonuçları",
            "Teknoloji fuarı rekor ziyaretçi ağırladı", "Kış lastiği uygulaması başlıyor", "Kitap fuarında imza günleri",
        )
        const val LOREM = "Bu, araştırma uygulamasındaki örnek bir haber metnidir. İçerik gerçek değildir ve yalnızca kaydırma " +
            "ve okuma davranışını gözlemlemek için kullanılır. "
    }
}
