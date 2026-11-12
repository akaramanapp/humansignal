package io.humansignal.research.tasks

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import io.humansignal.research.R
import io.humansignal.research.Task
import io.humansignal.research.add
import io.humansignal.research.button
import io.humansignal.research.column
import io.humansignal.research.input
import io.humansignal.research.label
import io.humansignal.research.scroll
import io.humansignal.sdk.HumanSignal

/**
 * T5 Metin yazma: sistem klavyesiyle sahte IBAN, tutar ve açıklama (`trackTextField`); 3 deneme.
 * SDK yalnızca zamanlama/uzunluk kaydeder; doğruluk kontrolü uygulamada yapılır, içerik hiçbir yere yazılmaz.
 */
class TextEntryTask : Task("t5", "text_entry", "Metin yazma", 3) {
    private val tracked = ArrayList<EditText>()

    override fun startTrial(trial: Int, container: FrameLayout) {
        cleanup()
        val r = host.random
        val iban = Fake.iban(r); val amount = Fake.amount(r); val desc = Fake.descriptions[r.nextInt(Fake.descriptions.size)]
        host.setInstruction("Aşağıdaki sahte bilgileri alanlara yazın.")
        val ctx = container.context
        val ibanIn = ctx.input(R.id.task_t5_iban, "task_t5_iban", "IBAN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val amountIn = ctx.input(R.id.task_t5_amount, "task_t5_amount", "Tutar", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val descIn = ctx.input(R.id.task_t5_desc, "task_t5_desc", "Açıklama")
        // Sayı klavyesi virgül göstermeyebilir: tutar alanı metin olarak da kabul edilir.
        amountIn.inputType = InputType.TYPE_CLASS_TEXT
        listOf(ibanIn to "iban", amountIn to "amount", descIn to "desc").forEach { (e, f) -> HumanSignal.trackTextField(e, f); tracked.add(e) }

        val view = ctx.column(0) {
            add(ctx.label("IBAN: $iban").apply { id = R.id.task_t5_prompt_iban; contentDescription = "task_t5_prompt_iban" }); add(ibanIn)
            add(ctx.label("Tutar: $amount ₺").apply { id = R.id.task_t5_prompt_amount; contentDescription = "task_t5_prompt_amount" }); add(amountIn)
            add(ctx.label("Açıklama: $desc").apply { id = R.id.task_t5_prompt_desc; contentDescription = "task_t5_prompt_desc" }); add(descIn)
            add(ctx.button(R.id.task_t5_submit, "task_t5_submit", "Devam") {
                // Hangi alanın tutmadığı kaydedilir (içerik değil, yalnızca alan adı).
                val wrong = listOfNotNull(
                    "iban".takeIf { Fake.norm(ibanIn.text.toString()) != Fake.norm(iban) },
                    "amount".takeIf { Fake.norm(amountIn.text.toString()) != Fake.norm(amount) },
                    "desc".takeIf { Fake.norm(descIn.text.toString()) != Fake.norm(desc) },
                )
                host.trialEnded(if (wrong.isEmpty()) "ok" else "mismatch", if (wrong.isEmpty()) emptyMap() else mapOf("fields" to wrong.joinToString("+")))
            })
        }
        container.addView(ctx.scroll(view))
        view.post { host.trialStarted() }
    }

    override fun cleanup() {
        tracked.forEach { HumanSignal.untrackTextField(it) }
        tracked.clear()
    }
}
