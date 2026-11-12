package io.humansignal.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    /** Okuma başına ilerleyen sahte saat: elapsed = uptime + gap. */
    private class FakeTime(var uptime: Long, var gap: Long, val stepNs: Long = 0) : TimeSource {
        override fun elapsedNanos(): Long { val v = uptime + gap; uptime += stepNs; return v }
        override fun uptimeNanos(): Long { val v = uptime; uptime += stepNs; return v }
        override fun wallMillis(): Long = 1_762_934_400_000L
    }

    @Test fun offsetIsElapsedMinusUptime() {
        val ts = FakeTime(uptime = 5_000_000_000L, gap = 2_000_000_000L)
        assertEquals(2_000_000_000L, SessionClock.measureOffset(ts))
    }

    @Test fun offsetCorrectsForHalfOfReadDuration() {
        // Her okuma 1000 ns sürüyor: e1 = u0+gap, u = u0+1000, e2 = u0+2000+gap → ortalama e = u0+1000+gap
        val ts = FakeTime(uptime = 0, gap = 7_000_000L, stepNs = 1000)
        assertEquals(7_000_000L, SessionClock.measureOffset(ts))
    }

    @Test fun convertsUptimeAndElapsedToSessionTime() {
        val ts = FakeTime(uptime = 10_000_000_000L, gap = 3_000_000_000L)
        val c = SessionClock(ts)
        assertEquals(13_000_000_000L, c.t0ElapsedNs)
        assertEquals(10_000_000_000L, c.t0UptimeNs)
        // Elapsed tabanlı olay 250 ms sonra
        assertEquals(250_000_000L, c.fromElapsed(13_250_000_000L))
        // Uptime tabanlı dokunma olayı 250 ms sonra → aynı oturum zamanı
        assertEquals(250_000_000L, c.fromUptime(10_250_000_000L))
        assertEquals(250_000_000L, c.fromUptimeMillis(10_250L))
    }

    @Test fun refreshTracksSleepDivergence() {
        val ts = FakeTime(uptime = 10_000_000_000L, gap = 3_000_000_000L)
        val c = SessionClock(ts)
        // Cihaz 5 sn uyudu: elapsed ilerledi, uptime ilerlemedi.
        ts.gap += 5_000_000_000L
        c.refreshOffset()
        assertEquals(8_000_000_000L, c.offsetNs)
        // Uyku sonrası uptime=10.1 s'deki dokunma, elapsed tabanında t0'dan 5.1 s sonradır.
        assertEquals(5_100_000_000L, c.fromUptime(10_100_000_000L))
    }

    @Test fun classifiesSensorClockBasis() {
        val elapsed = 20_000_000_000L
        val uptime = 12_000_000_000L
        assertEquals(SensorClock("elapsed", 0), SessionClock.classify(elapsed - 5_000_000, elapsed, uptime))
        val up = SessionClock.classify(uptime - 5_000_000, elapsed, uptime)
        assertEquals("uptime", up.basis)
        assertEquals(-8_005_000_000L, up.offsetNs)
        val unk = SessionClock.classify(999_000_000_000L, elapsed, uptime)
        assertEquals("unknown", unk.basis)
        assertEquals(979_000_000_000L, unk.offsetNs)
    }

    @Test fun sensorTimestampConversionPerBasis() {
        val ts = FakeTime(uptime = 10_000_000_000L, gap = 3_000_000_000L)
        val c = SessionClock(ts)
        assertEquals(1_000_000L, c.fromSensor(SensorClock("elapsed", 0), 13_001_000_000L))
        assertEquals(1_000_000L, c.fromSensor(SensorClock("uptime", -3_000_000_000L), 10_001_000_000L))
        assertEquals(1_000_000L, c.fromSensor(SensorClock("unknown", 500_000_000_000L), 513_001_000_000L))
    }

    @Test fun classificationIsCachedPerStream() {
        val ts = FakeTime(uptime = 10_000_000_000L, gap = 3_000_000_000L)
        val c = SessionClock(ts)
        val first = c.classifySensor("acc", 13_000_000_000L)
        val second = c.classifySensor("acc", 999_999_000_000_000L)
        assertEquals(first, second)
        assertEquals(mapOf("acc" to first), c.sensorClocks())
    }
}
