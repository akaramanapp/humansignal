package io.humansignal.sdk.internal

import android.text.Editable
import android.text.TextWatcher

/**
 * Sistem klavyesi alanı izleyici (§3.6.2).
 *
 * **Metnin içeriği ASLA kaydedilmez.** Yalnızca `before`, `count` ve `s.length` kullanılır; `s`'nin karakterlerine
 * hiçbir yerde erişilmez (birim testiyle doğrulanır).
 */
internal class TextFieldWatcher(
    val fieldId: String,
    private val sink: () -> EventSink?,
    private val imeActive: () -> Boolean,
) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (before == 0 && count == 0) return
        val es = sink() ?: return
        val len = s?.length ?: 0
        val t = es.clock.now()
        es.add(EventLine(t, "text", encodeText(fieldId, t, count - before, len, imeActive())))
    }

    override fun afterTextChanged(s: Editable?) {}

    companion object {
        fun encodeText(fieldId: String, t: Long, d: Int, len: Int, ime: Boolean): String = JsonWriter.obj {
            field("k", "text"); field("f", fieldId); field("t", t); field("d", d); field("len", len)
            field("paste", d > 1); field("ime", ime)
        }

        fun encodeFocus(fieldId: String, t: Long, on: Boolean): String = JsonWriter.obj {
            field("k", "focus"); field("f", fieldId); field("t", t); field("on", on)
        }
    }
}
