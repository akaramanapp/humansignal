package io.humansignal.research.tasks

import android.widget.EditText
import android.widget.FrameLayout
import io.humansignal.research.Marks
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.add
import io.humansignal.research.button
import io.humansignal.research.column
import io.humansignal.research.input
import io.humansignal.research.label
import io.humansignal.research.title
import io.humansignal.sdk.HumanSignal

/**
 * T6 Sahte para transferi: alıcı seç → tutar → açıklama → onay → PIN ile onayla; 3 deneme.
 * Gerçek bir bankacılık akışını taklit eder; her adım `screen` marker'ı ile işaretlenir.
 */
class FakeTransferTask : Task("t6", "fake_transfer", "Sahte para transferi", 3) {
    private val tracked = ArrayList<EditText>()
    private lateinit var container: FrameLayout
    private var recipient = ""; private var amount = ""; private var desc = ""; private var pin = ""
    private var chosen = ""; private var typedAmount = ""; private var typedDesc = ""

    override fun startTrial(trial: Int, container: FrameLayout) {
        cleanup()
        this.container = container
        val r = host.random
        recipient = Fake.recipients[r.nextInt(Fake.recipients.size)]
        amount = Fake.amount(r); desc = Fake.descriptions[r.nextInt(Fake.descriptions.size)]; pin = Fake.pin(r)
        host.setInstruction("$recipient kişisine $amount ₺ gönderin. Açıklama: \"$desc\"")
        stepRecipient()
        container.post { host.trialStarted(mapOf("pin_len" to pin.length.toString())) }
    }

    private fun step(name: String) {
        container.removeAllViews()
        Marks.screen("t6_$name")
    }

    private fun stepRecipient() {
        step("recipient")
        val ctx = container.context
        container.addView(ctx.column(0) {
            add(ctx.title("Alıcı seçin"))
            Fake.recipients.forEachIndexed { i, name ->
                add(ctx.button(RECIPIENT_IDS[i], "task_t6_recipient_$i", name) { chosen = name; stepAmount() }.apply {
                    contentDescription = "task_t6_recipient_$i"
                    tag = name
                })
            }
        })
    }

    private fun stepAmount() {
        step("amount")
        val ctx = container.context
        val e = ctx.input(R.id.task_t6_amount, "task_t6_amount", "Tutar (₺)")
        HumanSignal.trackTextField(e, "t6_amount"); tracked.add(e)
        container.addView(ctx.column(0) {
            add(ctx.title("Tutar")); add(ctx.label("Alıcı: $chosen")); add(e)
            add(ctx.button(R.id.task_t6_next, "task_t6_next", "Devam") { typedAmount = e.text.toString(); host.hideKeyboard(); stepDesc() })
        })
    }

    private fun stepDesc() {
        step("description")
        val ctx = container.context
        val e = ctx.input(R.id.task_t6_desc, "task_t6_desc", "Açıklama")
        HumanSignal.trackTextField(e, "t6_desc"); tracked.add(e)
        container.addView(ctx.column(0) {
            add(ctx.title("Açıklama")); add(e)
            add(ctx.button(R.id.task_t6_next, "task_t6_next", "Devam") { typedDesc = e.text.toString(); host.hideKeyboard(); stepConfirm() })
        })
    }

    private fun stepConfirm() {
        step("confirm")
        val ctx = container.context
        container.addView(ctx.column(0) {
            add(ctx.title("Onay"))
            add(ctx.label("Alıcı: $chosen\nTutar: $typedAmount ₺\nAçıklama: $typedDesc", 17f).apply {
                id = R.id.task_t6_summary; contentDescription = "task_t6_summary"
            })
            add(ctx.button(R.id.task_t6_confirm, "task_t6_confirm", "Onayla") { stepPin() })
            add(ctx.button(R.id.task_t6_back, "task_t6_back", "Geri") { stepRecipient() })
        })
    }

    private fun stepPin() {
        step("pin")
        host.setInstruction("İşlemi onaylamak için sahte PIN'i girin.")
        pinPad(container, "t6_pin", pin, R.id.task_t6_pin_display, "task_t6_pin_display", R.id.task_t6_keypad) { entered ->
            val result = when {
                entered != pin -> "wrong_pin"
                chosen != recipient -> "wrong_recipient"
                Fake.norm(typedAmount) != Fake.norm(amount) -> "wrong_amount"
                Fake.norm(typedDesc) != Fake.norm(desc) -> "wrong_desc"
                else -> "ok"
            }
            host.trialEnded(result)
        }
    }

    override fun cleanup() {
        tracked.forEach { HumanSignal.untrackTextField(it) }
        tracked.clear()
    }

    private companion object {
        val RECIPIENT_IDS = intArrayOf(R.id.task_t6_recipient_0, R.id.task_t6_recipient_1, R.id.task_t6_recipient_2, R.id.task_t6_recipient_3)
    }
}
