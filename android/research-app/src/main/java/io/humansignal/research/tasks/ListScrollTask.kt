package io.humansignal.research.tasks

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import io.humansignal.research.Marks
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.dp

/** T2 Liste kaydırma: 200 öğelik listede belirtilen işlemi bulup dokunmak; 5 deneme. */
class ListScrollTask : Task("t2", "list_scroll", "Liste kaydırma", 5) {
    private val used = HashSet<Int>()

    override fun startTrial(trial: Int, container: FrameLayout) {
        if (trial == 1) used.clear()
        var target: Int
        do { target = 20 + host.random.nextInt(181) } while (!used.add(target))
        host.setInstruction("$target numaralı işlemi bulun ve dokunun.")
        val ctx = container.context
        val list = ListView(ctx).apply {
            id = R.id.task_t2_list
            contentDescription = "task_t2_list"
            adapter = object : BaseAdapter() {
                override fun getCount() = 200
                override fun getItem(p: Int) = p + 1
                override fun getItemId(p: Int) = (p + 1).toLong()
                override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                    val tv = (convert as? TextView) ?: TextView(ctx).apply { textSize = 16f; setPadding(ctx.dp(12), ctx.dp(14), ctx.dp(12), ctx.dp(14)) }
                    val n = p + 1
                    tv.text = "İşlem #$n   ·   ${(n * 37) % 900 + 50},00 ₺   ·   ${DESCS[n % DESCS.size]}"
                    tv.contentDescription = "task_t2_item_$n"
                    return tv
                }
            }
            setOnItemClickListener { _, _, pos, _ ->
                val n = pos + 1
                if (n == target) host.trialEnded("ok", mapOf("target" to target.toString()))
                else Marks.mark("wrong_item", mapOf("task" to name, "trial" to trial.toString(), "item" to n.toString(), "target" to target.toString()))
            }
        }
        container.addView(list, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        list.post { host.trialStarted(mapOf("target" to target.toString())) }
    }

    private companion object {
        val DESCS = listOf("Market", "Fatura", "Kira", "Akaryakıt", "Restoran", "Eczane", "Kitap", "Ulaşım")
    }
}
