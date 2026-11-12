package io.humansignal.sdk.internal

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Bağımlılıksız, hızlı JSON yazıcı. `org.json` Android'de var ama JVM birim testlerinde yok;
 * ayrıca sayı biçimlendirmesini (§3.8: koordinat 1 ondalık, sensör 5 anlamlı basamak) kontrol etmek istiyoruz.
 */
internal class JsonWriter(val sb: StringBuilder = StringBuilder(256)) {
    private var needComma = false

    fun beginObject(): JsonWriter { sep(); sb.append('{'); needComma = false; return this }
    fun endObject(): JsonWriter { sb.append('}'); needComma = true; return this }
    fun beginArray(): JsonWriter { sep(); sb.append('['); needComma = false; return this }
    fun endArray(): JsonWriter { sb.append(']'); needComma = true; return this }

    fun name(n: String): JsonWriter { sep(); quote(n); sb.append(':'); needComma = false; return this }

    fun value(s: String?): JsonWriter { sep(); if (s == null) sb.append("null") else quote(s); needComma = true; return this }
    fun value(b: Boolean): JsonWriter { sep(); sb.append(b); needComma = true; return this }
    fun value(l: Long): JsonWriter { sep(); sb.append(l); needComma = true; return this }
    fun value(i: Int): JsonWriter { sep(); sb.append(i); needComma = true; return this }
    fun nullValue(): JsonWriter { sep(); sb.append("null"); needComma = true; return this }

    /** Genel ondalık değer (tam hassasiyet; NaN/Inf → null). */
    fun value(d: Double): JsonWriter {
        sep()
        if (d.isNaN() || d.isInfinite()) sb.append("null") else NumFmt.appendPlain(sb, d)
        needComma = true; return this
    }

    /** Sensör değeri: 5 anlamlı basamak. */
    fun sig5(f: Float): JsonWriter { sep(); NumFmt.appendSig(sb, f.toDouble(), 5); needComma = true; return this }

    /** Koordinat: 1 ondalık. */
    fun dec1(d: Double): JsonWriter { sep(); NumFmt.appendFixed1(sb, d); needComma = true; return this }

    /** Önceden kodlanmış ham JSON parçası ekler (dikkatli kullanın). */
    fun raw(json: String): JsonWriter { sep(); sb.append(json); needComma = true; return this }

    fun field(n: String, v: String?) = name(n).value(v)
    fun field(n: String, v: Long) = name(n).value(v)
    fun field(n: String, v: Int) = name(n).value(v)
    fun field(n: String, v: Boolean) = name(n).value(v)
    fun field(n: String, v: Double) = name(n).value(v)

    /** Map/List/String/Number/Boolean/null ağacını yazar. */
    fun any(v: Any?): JsonWriter {
        when (v) {
            null -> nullValue()
            is String -> value(v)
            is Boolean -> value(v)
            is Int -> value(v)
            is Long -> value(v)
            is Float -> value(v.toDouble())
            is Double -> value(v)
            is Number -> value(v.toDouble())
            is Map<*, *> -> {
                beginObject()
                for ((k, x) in v) { name(k.toString()); any(x) }
                endObject()
            }
            is Iterable<*> -> { beginArray(); for (x in v) any(x); endArray() }
            is Array<*> -> { beginArray(); for (x in v) any(x); endArray() }
            else -> value(v.toString())
        }
        return this
    }

    override fun toString(): String = sb.toString()

    private fun sep() { if (needComma) sb.append(','); }

    private fun quote(s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    companion object {
        fun obj(block: JsonWriter.() -> Unit): String =
            JsonWriter().apply { beginObject(); block(); endObject() }.toString()

        fun encode(v: Any?): String = JsonWriter().any(v).toString()
    }
}

internal object NumFmt {
    fun appendSig(sb: StringBuilder, d: Double, digits: Int) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == 0.0) { sb.append('0'); return }
        val bd = BigDecimal(d).round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        sb.append(bd.toPlainString())
    }

    fun appendFixed1(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        val r = Math.round(d * 10.0)
        if (r < 0) sb.append('-')
        val a = Math.abs(r)
        sb.append(a / 10)
        val frac = a % 10
        if (frac != 0L) sb.append('.').append(frac)
    }

    fun appendPlain(sb: StringBuilder, d: Double) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong())
        else sb.append(BigDecimal(d.toString()).toPlainString())
    }
}

/**
 * Minimal JSON ayrıştırıcı: yerel kuyruk/oturum dosyalarını ve (M3'te) `/v1/config` yanıtını okumak için.
 * Nesneler → LinkedHashMap<String, Any?>, diziler → ArrayList<Any?>, sayılar → Long ya da Double.
 */
internal class JsonParser private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(s: String): Any? {
            val p = JsonParser(s)
            p.ws()
            val v = p.value()
            p.ws()
            if (p.i != s.length) p.fail("trailing characters")
            return v
        }

        @Suppress("UNCHECKED_CAST")
        fun parseObject(s: String): Map<String, Any?> =
            parse(s) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON object expected")
    }

    private fun value(): Any? {
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (peek() == '}') { i++; return m }
        while (true) {
            ws(); if (peek() != '"') fail("key expected")
            val k = str(); ws(); expect(':'); ws()
            m[k] = value(); ws()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> fail("',' or '}' expected")
            }
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; ws()
        if (peek() == ']') { i++; return l }
        while (true) {
            ws(); l.add(value()); ws()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return l }
                else -> fail("',' or ']' expected")
            }
        }
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    val e = s[i++]
                    when (e) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> fail("bad escape")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("bad literal")
        i += word.length; return v
    }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    private fun expect(c: Char) { if (peek() != c) fail("'$c' expected"); i++ }
    private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON parse error at $i: $msg")
}

/** Ayrıştırılmış JSON üzerinde küçük yardımcılar. */
internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String
internal fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()
internal fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt()
internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(k: String): List<Any?>? = this[k] as? List<Any?>
