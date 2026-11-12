package io.humansignal.sdk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * PIN / şifre girişi için SDK'nın sayısal klavyesi (§3.6.1). 3×4 ızgara: 1–9, ⌫, 0, ✓.
 *
 * - Her tuş basışı `key` akışına yazılır: td/tu, tuş merkezine göre dx/dy, basınç/boyut/touchMajor.
 *   Tuş kimliği (`kid`) yalnızca `HumanSignalConfig.recordKeyIdentity = true` iken yazılır.
 * - Bu View üzerinde başlayan dokunmalar `touch` akışında **maskelenir** (`"masked":true`, x/y yok).
 * - Bot erişilebilirliği (§4.3): her tuş bir alt View'dır; sabit resource-id (`keypad_key_5`, `keypad_key_delete`,
 *   `keypad_key_submit`) ve contentDescription taşır. Dokunmaları alt View'lar değil, bu View işler.
 *   Erişilebilirlik servisinin dokunmasız tıklaması (`performClick`) da kaydedilir (`"a11y":true`).
 */
class SecureKeypadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Kayıtlarda alan adı, ör. "pin". */
    var fieldId: String = "pin"

    /** Uygulamaya basılan tuşu bildirir (uygulamanın PIN'e ihtiyacı var). */
    var listener: ((KeyPress) -> Unit)? = null

    /** Rakam yerlerini karıştırır (opsiyonel). Değiştirildiğinde tuşlar yeniden dizilir. */
    var shuffleKeys: Boolean = false
        set(value) { field = value; arrangeKeys() }

    /** Izgaradaki 12 hücre sırasıyla: indeks → tuş. */
    private val cells = arrayOfNulls<KeyView>(12)
    private val keys: List<KeyView>

    // Etkin basış (tek parmak)
    private var activeKey: KeyView? = null
    private var activePointerId = -1
    private var downNs: Long? = null
    private var downDx = 0f
    private var downDy = 0f
    private var downPr = 0f
    private var downSz = 0f
    private var downMa = 0f

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val digits = (0..9).map { d -> KeyView(context, KeyClass.DIGIT, d) }
        val del = KeyView(context, KeyClass.DELETE, null)
        val ok = KeyView(context, KeyClass.SUBMIT, null)
        keys = digits + del + ok
        keys.forEach { addView(it) }
        arrangeKeys()
    }

    private fun arrangeKeys() {
        val digitKeys = keys.filter { it.keyClass == KeyClass.DIGIT }.sortedBy { it.digit }
        val order = if (shuffleKeys) digitKeys.shuffled() else listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0).map { d -> digitKeys[d] }
        // Hücreler: 0..8 → ilk 9 rakam, 9 → ⌫, 10 → son rakam, 11 → ✓
        for (i in 0 until 9) cells[i] = order[i]
        cells[9] = keys.first { it.keyClass == KeyClass.DELETE }
        cells[10] = order[9]
        cells[11] = keys.first { it.keyClass == KeyClass.SUBMIT }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val defaultKeyH = dp(64f).toInt()
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(heightMeasureSpec), defaultKeyH * 4)
            else -> defaultKeyH * 4
        }
        val cw = w / 3
        val ch = h / 4
        for (k in keys) k.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / 3
        val ch = (b - t) / 4
        for (i in 0 until 12) {
            val k = cells[i] ?: continue
            val col = i % 3
            val row = i / 3
            k.layout(col * cw, row * ch, col * cw + cw, row * ch + ch)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HumanSignal.registerMaskView(this)
    }

    override fun onDetachedFromWindow() {
        HumanSignal.unregisterMaskView(this)
        super.onDetachedFromWindow()
    }

    // Tüm dokunmaları bu View işler (alt View'lar yalnızca görsel + erişilebilirlik içindir).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = keyAt(ev.x, ev.y) ?: return true
                activeKey = k
                activePointerId = ev.getPointerId(0)
                k.isPressed = true
                downNs = HumanSignal.eventSessionTime(ev)
                downDx = ev.x - (k.left + k.width / 2f)
                downDy = ev.y - (k.top + k.height / 2f)
                downPr = ev.pressure
                downSz = ev.size
                downMa = ev.touchMajor
            }
            MotionEvent.ACTION_UP -> {
                val k = activeKey ?: return true
                k.isPressed = false
                activeKey = null
                val td = downNs
                val tu = HumanSignal.eventSessionTime(ev)
                if (td != null && tu != null) {
                    HumanSignal.recordKey(
                        fieldId, td, tu, downDx, downDy, downPr, downSz, downMa, k.keyClass, k.digit, a11y = false,
                    )
                }
                listener?.invoke(KeyPress(k.keyClass, k.digit))
            }
            MotionEvent.ACTION_CANCEL -> {
                activeKey?.isPressed = false
                activeKey = null
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): KeyView? =
        keys.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }

    /** Erişilebilirlik servisi tuşa dokunmadan tıkladı. */
    private fun onA11yClick(k: KeyView) {
        HumanSignal.recordKeyWithoutTouch(fieldId, k.keyClass, k.digit)
        listener?.invoke(KeyPress(k.keyClass, k.digit))
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** Tek bir tuş. Dokunma almaz; yalnızca çizim ve erişilebilirlik için. */
    private inner class KeyView(ctx: Context, val keyClass: KeyClass, val digit: Int?) : TextView(ctx) {
        init {
            val (label, idRes, desc) = when (keyClass) {
                KeyClass.DIGIT -> Triple(digit.toString(), DIGIT_IDS[digit!!], "keypad_key_$digit")
                KeyClass.DELETE -> Triple("⌫", R.id.keypad_key_delete, "keypad_key_delete")
                KeyClass.SUBMIT -> Triple("✓", R.id.keypad_key_submit, "keypad_key_submit")
            }
            id = idRes
            text = label
            contentDescription = desc
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Color.rgb(0x21, 0x21, 0x21))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), roundRect(Color.rgb(0xbd, 0xbd, 0xbd)))
                addState(intArrayOf(), roundRect(Color.rgb(0xee, 0xee, 0xee)))
            }
            // performClick yalnızca erişilebilirlik / programatik tıklamada çağrılır (dokunmaları üst View işler).
            setOnClickListener { onA11yClick(this) }
        }

        private fun roundRect(c: Int) = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { cornerRadius = dp(10f); setColor(c) },
            dp(4f).toInt(),
        )
    }

    private companion object {
        val DIGIT_IDS = intArrayOf(
            R.id.keypad_key_0, R.id.keypad_key_1, R.id.keypad_key_2, R.id.keypad_key_3, R.id.keypad_key_4,
            R.id.keypad_key_5, R.id.keypad_key_6, R.id.keypad_key_7, R.id.keypad_key_8, R.id.keypad_key_9,
        )
    }
}
