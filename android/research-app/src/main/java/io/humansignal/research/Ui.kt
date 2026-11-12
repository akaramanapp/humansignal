package io.humansignal.research

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * Programatik View yardımcıları. Görsel cila gerekmez (§4); önemli olan her etkileşimli öğenin sabit
 * resource-id'si ve contentDescription'ı olması (§4.3 bot erişilebilirliği). `id` = R.id.x, `desc` = "x".
 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.column(padDp: Int = 16, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    block()
}

fun Context.row(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun Context.scroll(child: View) = ScrollView(this).apply { addView(child, MATCH_PARENT, WRAP_CONTENT) }

fun Context.title(text: String) = TextView(this).apply {
    this.text = text
    textSize = 22f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, 0, 0, dp(8))
}

fun Context.label(text: String, sizeSp: Float = 15f) = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setPadding(0, dp(10), 0, dp(4))
}

fun Context.button(id: Int, desc: String, text: String, onClick: () -> Unit) = Button(this).apply {
    this.id = id
    contentDescription = desc
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.input(id: Int, desc: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String = "") = EditText(this).apply {
    this.id = id
    contentDescription = desc
    this.hint = hint
    // Önce tek satır: setSingleLine dönüşüm yöntemini değiştirir ve şifre maskesini ezer.
    isSingleLine = true
    inputType = type
    setText(value)
}

fun Context.check(id: Int, desc: String, text: String, value: Boolean) = CheckBox(this).apply {
    this.id = id
    contentDescription = desc
    this.text = text
    isChecked = value
}

/** Seçenekler: (görünen ad, değer). Her RadioButton'un contentDescription'ı "<desc>_<değer>". */
fun <T> Context.radios(id: Int, desc: String, options: List<Pair<String, T>>, onPick: (T) -> Unit = {}): RadioGroup =
    RadioGroup(this).apply {
        this.id = id
        contentDescription = desc
        orientation = RadioGroup.VERTICAL
        options.forEachIndexed { i, (text, value) ->
            addView(RadioButton(this@radios).apply {
                this.id = View.generateViewId()
                this.text = text
                contentDescription = "${desc}_${value.toString().lowercase()}"
                tag = value
                setOnCheckedChangeListener { _, on -> if (on) onPick(value) }
            })
        }
    }

@Suppress("UNCHECKED_CAST")
fun <T> RadioGroup.picked(): T? = findViewById<RadioButton>(checkedRadioButtonId)?.tag as T?

fun <T> Context.spinner(id: Int, desc: String, options: List<Pair<String, T>>) = Spinner(this).apply {
    this.id = id
    contentDescription = desc
    adapter = ArrayAdapter(this@spinner, android.R.layout.simple_spinner_dropdown_item, options.map { it.first })
    tag = options.map { it.second }
}

@Suppress("UNCHECKED_CAST")
fun <T> Spinner.pickedValue(): T = (tag as List<T>)[selectedItemPosition]

fun ViewGroup.add(v: View, w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f): View {
    addView(v, LinearLayout.LayoutParams(w, h, weight))
    return v
}
