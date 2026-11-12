package io.humansignal.research

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.humansignal.sdk.HumanSignal
import io.humansignal.sdk.KeyClass
import io.humansignal.sdk.SecureKeypadView

/**
 * Giriş Testi (geliştirici, M2): sistem klavyesi alanı (`trackTextField`) ve `SecureKeypadView`.
 * Gerçek görevler (T4 PIN, T5 metin) M4'te gelecek; bu ekran toplayıcıları elle denemek içindir.
 * Yalnızca SAHTE değerler girin.
 */
class InputTestActivity : AppCompatActivity() {
    private lateinit var amount: EditText
    private lateinit var pinDots: TextView
    private val pin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Giriş Testi"
        val pad = (16 * resources.displayMetrics.density).toInt()

        amount = EditText(this).apply {
            id = R.id.input_test_amount
            hint = "Tutar (sahte)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            contentDescription = "input_test_amount"
        }
        pinDots = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            text = " "
        }
        val keypad = SecureKeypadView(this).apply {
            id = R.id.input_test_keypad
            fieldId = "pin"
            listener = { k ->
                when (k.keyClass) {
                    KeyClass.DIGIT -> if (pin.length < 6) pin.append(k.digit)
                    KeyClass.DELETE -> if (pin.isNotEmpty()) pin.setLength(pin.length - 1)
                    KeyClass.SUBMIT -> {
                        HumanSignal.mark("dev_pin_submit", mapOf("len" to pin.length.toString()))
                        pin.setLength(0)
                    }
                }
                pinDots.text = "●".repeat(pin.length).ifEmpty { " " }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@InputTestActivity).apply { text = "Sistem klavyesi alanı (yalnızca uzunluk/zaman kaydedilir)" })
            addView(amount, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(TextView(this@InputTestActivity).apply {
                text = "\nSecureKeypadView (dokunmalar maskelenir)"
            })
            addView(pinDots, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(keypad, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        HumanSignal.trackTextField(amount, "amount")
        HumanSignal.mark("screen", mapOf("name" to "InputTest"))
    }

    override fun onDestroy() {
        HumanSignal.untrackTextField(amount)
        super.onDestroy()
    }
}
