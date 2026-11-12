package io.humansignal.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M2UnitTest {
    private class FixedTime(var now: Long = 1_000_000_000L) : TimeSource {
        override fun elapsedNanos() = now
        override fun uptimeNanos() = now
        override fun wallMillis() = 0L
    }

    private class ListSink(override val clock: SessionClock) : EventSink {
        val lines = ArrayList<EventLine>()
        override fun add(e: EventLine) { lines.add(e) }
    }

    // ---------------------------------------------------------------- §3.6.2 / A6

    @Test fun textWatcherNeverRecordsContent() {
        val ts = FixedTime()
        val sink = ListSink(SessionClock(ts))
        val w = TextFieldWatcher("iban", { sink }, { true })
        val secret = "TR330006100519786457841326"
        var text = ""
        // Tek tek yazma
        for (ch in secret.take(6)) {
            val before = text
            text += ch
            ts.now += 120_000_000
            w.beforeTextChanged(before, before.length, 0, 1)
            w.onTextChanged(text, before.length, 0, 1)
        }
        // Silme
        text = text.dropLast(1); w.onTextChanged(text, text.length, 1, 0)
        // Yapıştırma
        val pasted = text + secret.drop(6)
        w.onTextChanged(pasted, text.length, 0, secret.length - 6)

        val all = sink.lines.joinToString("\n") { it.json }
        // İçerikten hiçbir parça (2+ karakterlik alt dizi) kayıtta geçmemeli.
        for (i in 0 until secret.length - 1) {
            val sub = secret.substring(i, i + 2)
            if (sub.all { it.isDigit() }) continue // sayılar uzunluk/zaman olarak geçebilir
            assertFalse("leaked '$sub'", all.contains(sub))
        }
        assertFalse(all.contains("TR33"))
        assertEquals(8, sink.lines.size)

        val first = JsonParser.parseObject(sink.lines[0].json)
        assertEquals(mapOf("k" to "text", "f" to "iban", "t" to 120_000_000L, "d" to 1L, "len" to 1L, "paste" to false, "ime" to true), first)
        val del = JsonParser.parseObject(sink.lines[6].json)
        assertEquals(-1L, del["d"]); assertEquals(5L, del["len"])
        val paste = JsonParser.parseObject(sink.lines[7].json)
        assertEquals(20L, paste["d"]); assertEquals(true, paste["paste"]); assertEquals(25L, paste["len"])
        assertEquals(setOf("k", "f", "t", "d", "len", "paste", "ime"), paste.keys)
    }

    @Test fun textWatcherIgnoresNoOpAndNoSession() {
        val w = TextFieldWatcher("amount", { null }, { false })
        w.onTextChanged("123", 0, 0, 3) // oturum yok → çökmez, yazmaz
        val sink = ListSink(SessionClock(FixedTime()))
        val w2 = TextFieldWatcher("amount", { sink }, { false })
        w2.onTextChanged("", 0, 0, 0)
        assertTrue(sink.lines.isEmpty())
    }

    // ---------------------------------------------------------------- Saat Testi

    /** 100 Hz düz sinyal, belirtilen zamanlarda sert bir sıçrama. */
    private fun feed(a: ClockTestAnalyzer, spikesNs: List<Long>, untilNs: Long) {
        var t = 0L
        while (t <= untilNs) {
            val spike = spikesNs.any { t >= it && t < it + 10_000_000L }
            a.onAcc(t, if (spike) floatArrayOf(3f, 14f, 2f) else floatArrayOf(0.1f, 9.8f, 0.2f))
            t += 10_000_000L
        }
    }

    @Test fun clockTestMeasuresSpikeToDownOffset() {
        val a = ClockTestAnalyzer(targetTaps = 3)
        val downs = listOf(1_000_000_000L, 2_000_000_000L, 3_000_000_000L)
        downs.forEach { a.onDown(it) }
        // Sıçramalar DOWN'dan sırasıyla +10, −20, +30 ms sonra
        feed(a, listOf(1_010_000_000L, 1_980_000_000L, 3_030_000_000L), 4_000_000_000L)
        val s = a.state()
        assertEquals(3, s.tapsAnalyzed)
        assertEquals(listOf(10.0, -20.0, 30.0), s.diffsMs)
        assertEquals(20.0, s.medianAbsMs!!, 1e-9)
        assertEquals(false, s.passed) // 20 < 20 değil
    }

    @Test fun clockTestCountsTapsWithoutSpike() {
        val a = ClockTestAnalyzer(targetTaps = 2)
        a.onDown(1_000_000_000L); a.onDown(2_000_000_000L)
        a.onDown(2_500_000_000L) // hedef aşıldı → yok sayılır
        feed(a, listOf(2_005_000_000L), 3_000_000_000L)
        val s = a.state()
        assertEquals(1, s.tapsWithoutSpike)
        assertEquals(1, s.tapsAnalyzed)
        assertEquals(true, s.passed)
    }

    @Test fun clockTestPendingUntilWindowCloses() {
        val a = ClockTestAnalyzer(targetTaps = 1)
        a.onDown(1_000_000_000L)
        feed(a, emptyList(), 1_100_000_000L)
        assertNull(a.state().passed)
        assertEquals(0, a.state().tapsAnalyzed + a.state().tapsWithoutSpike)
    }

    // ---------------------------------------------------------------- §3.7 emulator_hints

    @Test fun emulatorHintsMatchBuildAndSensorTokens() {
        val hints = DeviceCollector.emulatorHints(
            mapOf("fingerprint" to "google/sdk_gphone64_arm64/emu64a:14/...", "model" to "sdk_gphone64_arm64",
                "product" to "sdk_gphone64_arm64", "hardware" to "ranchu"),
            listOf("The Android Open Source Project" to "Goldfish 3-axis Accelerometer", "AOSP" to "Gyro"),
        )
        assertTrue("build.fingerprint:sdk_gphone" in hints)
        assertTrue("build.hardware:ranchu" in hints)
        assertTrue("sensor:goldfish" in hints)
        assertTrue("sensor:aosp" in hints)
        assertEquals(hints.size, hints.toSet().size)
    }

    @Test fun emulatorHintsEmptyOnRealDevice() {
        val hints = DeviceCollector.emulatorHints(
            mapOf("fingerprint" to "samsung/a54xnseea/a54x:14/UP1A/...", "model" to "SM-A546B", "product" to "a54xnseea", "hardware" to "s5e8835"),
            listOf("STMicro" to "LSM6DSO Accelerometer", "AKM" to "AK09918 Magnetometer"),
        )
        assertEquals(emptyList<String>(), hints)
    }
}
