package io.humansignal.research.tasks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import io.humansignal.research.Marks
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.dp
import kotlin.math.roundToInt

/**
 * T1 Dokunma hedefleri: rastgele yerlerde 60–120 dp çaplı daireler; 30 hedef.
 * Hedef konumu (ekran px, merkez) ve boyutu `task_start` marker'ında (§4.3; adb bot'ları logcat'ten okur).
 */
class TapTargetsTask : Task("t1", "tap_targets", "Dokunma hedefleri", 30) {
    override fun startTrial(trial: Int, container: FrameLayout) {
        host.setInstruction("Beliren daireye dokunun.")
        val ctx = container.context
        val dDp = 60 + host.random.nextInt(61)
        val d = ctx.dp(dDp)
        val target = Circle(ctx).apply {
            id = R.id.task_t1_target
            contentDescription = "task_t1_target"
            setOnClickListener { host.trialEnded("ok") }
        }
        // Hedef dışına dokunma: kaçırma olarak işaretlenir, deneme sürer.
        container.setOnClickListener { Marks.mark("target_miss", mapOf("task" to name, "trial" to trial.toString())) }
        container.post {
            val w = container.width - d
            val h = container.height - d
            if (w <= 0 || h <= 0) { host.trialEnded("skipped", mapOf("reason" to "no_space")); return@post }
            val lp = FrameLayout.LayoutParams(d, d).apply {
                leftMargin = host.random.nextInt(w + 1)
                topMargin = host.random.nextInt(h + 1)
            }
            container.addView(target, lp)
            target.post {
                val loc = IntArray(2).also { target.getLocationOnScreen(it) }
                host.trialStarted(mapOf(
                    "x" to (loc[0] + d / 2f).roundToInt().toString(),
                    "y" to (loc[1] + d / 2f).roundToInt().toString(),
                    "d_px" to d.toString(), "d_dp" to dDp.toString(),
                ))
            }
        }
    }

    override fun cleanup() {}

    private class Circle(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1e, 0x88, 0xe5) }
        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}
