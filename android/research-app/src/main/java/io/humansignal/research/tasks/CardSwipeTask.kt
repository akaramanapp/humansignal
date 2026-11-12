package io.humansignal.research.tasks

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.dp
import io.humansignal.research.dpf
import kotlin.math.abs

/** T3 Kart kaydırma: kartı oktaki yöne kaydırmak; 20 kart. */
class CardSwipeTask : Task("t3", "card_swipe", "Kart kaydırma", 20) {
    @SuppressLint("ClickableViewAccessibility")
    override fun startTrial(trial: Int, container: FrameLayout) {
        val right = host.random.nextBoolean()
        val dir = if (right) "right" else "left"
        host.setInstruction(if (right) "Kartı SAĞA kaydırın →" else "← Kartı SOLA kaydırın")
        val ctx = container.context
        val card = TextView(ctx).apply {
            id = R.id.task_t3_card
            contentDescription = "task_t3_card_$dir"
            text = if (right) "→" else "←"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = ctx.dpf(16f); setColor(Color.rgb(0x5e, 0x35, 0xb1)) }
        }
        var downX = 0f
        var dragging = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; dragging = true }
                MotionEvent.ACTION_MOVE -> if (dragging) { v.translationX = e.rawX - downX; v.rotation = v.translationX / 40f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val dx = e.rawX - downX
                    if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > v.width * 0.3f) {
                        val swiped = if (dx > 0) "right" else "left"
                        v.animate().translationX(if (dx > 0) container.width.toFloat() else -container.width.toFloat())
                            .setDuration(150).withEndAction {
                                host.trialEnded(if (swiped == dir) "ok" else "wrong", mapOf("expected" to dir, "swiped" to swiped))
                            }.start()
                    } else {
                        v.animate().translationX(0f).rotation(0f).setDuration(150).start()
                    }
                }
            }
            true
        }
        val w = minOf(container.width.takeIf { it > 0 } ?: ctx.dp(300), ctx.dp(300))
        container.addView(card, FrameLayout.LayoutParams(w, ctx.dp(380), Gravity.CENTER))
        card.post { host.trialStarted(mapOf("expected" to dir)) }
    }
}
