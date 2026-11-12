package io.humansignal.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    private fun sig5(f: Float) = StringBuilder().also { NumFmt.appendSig(it, f.toDouble(), 5) }.toString()
    private fun dec1(d: Double) = StringBuilder().also { NumFmt.appendFixed1(it, d) }.toString()

    @Test fun fiveSignificantDigits() {
        assertEquals("9.71", sig5(9.71f))
        assertEquals("0.12346", sig5(0.123456789f))
        assertEquals("-0.0031", sig5(-0.0031f))
        assertEquals("1013.2", sig5(1013.25f))   // HALF_EVEN, float 1013.25 tam temsil edilir
        assertEquals("123460", sig5(123456f))
        assertEquals("0", sig5(0f))
        assertEquals("null", sig5(Float.NaN))
    }

    @Test fun oneDecimalCoordinates() {
        assertEquals("512.3", dec1(512.34))
        assertEquals("1204.8", dec1(1204.75))
        assertEquals("512", dec1(512.0))
        assertEquals("-4.2", dec1(-4.2))
        assertEquals("0", dec1(0.04))
    }

    @Test fun writesEscapedObject() {
        val s = JsonWriter.obj {
            field("k", "marker"); field("t", 123L); field("name", "a\"b\\c\n")
            name("attrs").any(mapOf("task" to "pin_entry", "n" to 3, "x" to null, "l" to listOf(true, 1.5)))
        }
        assertEquals("""{"k":"marker","t":123,"name":"a\"b\\c\n","attrs":{"task":"pin_entry","n":3,"x":null,"l":[true,1.5]}}""", s)
    }

    @Test fun parserRoundTrip() {
        val src = linkedMapOf(
            "a" to 1L, "b" to "x\"y\u0001", "c" to listOf(1L, 2.5, null, true), "d" to linkedMapOf("e" to -9007199254740991L),
        )
        val parsed = JsonParser.parse(JsonWriter.encode(src))
        assertEquals(src, parsed)
    }

    @Test fun parsesWhitespaceAndUnicode() {
        val m = JsonParser.parseObject(""" { "k" : "ça\n" , "n": [ 1 , -2e3 ] } """)
        assertEquals("ça\n", m["k"])
        assertEquals(listOf(1L, -2000.0), m["n"])
    }
}
