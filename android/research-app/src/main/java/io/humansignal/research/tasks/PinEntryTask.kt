package io.humansignal.research.tasks

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.add
import io.humansignal.research.column
import io.humansignal.research.dp
import io.humansignal.sdk.KeyClass
import io.humansignal.sdk.SecureKeypadView

/** Tek bir PIN girişi görünümü (T4 ve T6'da ortak). */
internal fun pinPad(
    container: FrameLayout, fieldId: String, pin: String, idDisplay: Int, descDisplay: String, idKeypad: Int,
    onSubmit: (entered: String) -> Unit,
): LinearLayout {
    val ctx = container.context
    val entered = StringBuilder()
    val shown = TextView(ctx).apply {
        id = idDisplay; contentDescription = descDisplay
        text = pin; textSize = 30f; gravity = Gravity.CENTER; letterSpacing = 0.3f
    }
    val dots = TextView(ctx).apply { textSize = 28f; gravity = Gravity.CENTER; text = " " }
    val keypad = SecureKeypadView(ctx).apply {
        id = idKeypad
        this.fieldId = fieldId
        listener = { k ->
            when (k.keyClass) {
                KeyClass.DIGIT -> if (entered.length < 8) entered.append(k.digit)
                KeyClass.DELETE -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
                KeyClass.SUBMIT -> onSubmit(entered.toString())
            }
            dots.text = "●".repeat(entered.length).ifEmpty { " " }
        }
    }
    val root = ctx.column(0) {
        add(TextView(ctx).apply { text = "Sahte PIN:"; gravity = Gravity.CENTER })
        add(shown); add(dots)
        add(keypad, h = LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    container.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
        bottomMargin = ctx.dp(8)
    })
    return root
}

/** T4 PIN girişi: ekrandaki SAHTE 4–6 haneli PIN, SecureKeypadView ile; 10 deneme, her biri farklı PIN. */
class PinEntryTask : Task("t4", "pin_entry", "PIN girişi", 10) {
    private val used = HashSet<String>()

    override fun startTrial(trial: Int, container: FrameLayout) {
        if (trial == 1) used.clear()
        var pin: String
        do { pin = Fake.pin(host.random) } while (!used.add(pin))
        host.setInstruction("Gösterilen sahte PIN'i girip ✓'ye basın.")
        val v = pinPad(container, "pin", pin, R.id.task_t4_pin_display, "task_t4_pin_display", R.id.task_t4_keypad) { entered ->
            host.trialEnded(if (entered == pin) "ok" else "wrong", mapOf("len" to pin.length.toString(), "entered_len" to entered.length.toString()))
        }
        v.post { host.trialStarted(mapOf("len" to pin.length.toString())) }
    }
}
