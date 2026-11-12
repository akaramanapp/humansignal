package io.humansignal.sdk.internal

import android.os.SystemClock
import kotlin.math.abs

/** Saat kaynakları; testlerde sahtesi verilir. */
internal interface TimeSource {
    /** `SystemClock.elapsedRealtimeNanos()`: uykuda da ilerler. */
    fun elapsedNanos(): Long
    /**
     * Uptime tabanı, ns. Android'de `System.nanoTime()` ve `SystemClock.uptimeMillis()` ikisi de
     * `CLOCK_MONOTONIC`'tir; `nanoTime` aynı saati ms yerine ns çözünürlükle verdiği için
     * §3.3'teki `uptimeMillis × 1e6` yerine bunu kullanıyoruz (aynı taban, daha az kuantalama hatası).
     */
    fun uptimeNanos(): Long
    fun wallMillis(): Long
}

internal object AndroidTimeSource : TimeSource {
    override fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun uptimeNanos(): Long = System.nanoTime()
    override fun wallMillis(): Long = System.currentTimeMillis()
}

/** Bir sensörün saat tabanı doğrulamasının sonucu (§3.3 adım 3). */
internal data class SensorClock(val basis: String, val offsetNs: Long)

/**
 * Oturum zaman tabanı (§3.3). Tüm kayıtlar `t = elapsed_tabanli_olay_zamani − t0_elapsed_ns` olarak yazılır.
 *
 * Thread güvenliği: `offsetNs` volatile; `refreshOffset()` herhangi bir thread'den çağrılabilir.
 */
internal class SessionClock(private val ts: TimeSource) {
    val t0ElapsedNs: Long
    val t0UptimeNs: Long
    val t0WallMs: Long

    /** elapsed − uptime (ns). Cihaz uyuyunca büyür; bu yüzden düzenli ölçülür. */
    @Volatile var offsetNs: Long
        private set

    private val sensorClocks = LinkedHashMap<String, SensorClock>()

    init {
        val m = measureOffset(ts)
        offsetNs = m
        t0WallMs = ts.wallMillis()
        t0ElapsedNs = ts.elapsedNanos()
        t0UptimeNs = t0ElapsedNs - m
    }

    fun refreshOffset() { offsetNs = measureOffset(ts) }

    /** Şu anki oturum zamanı (ns). */
    fun now(): Long = ts.elapsedNanos() - t0ElapsedNs

    fun fromElapsed(elapsedNs: Long): Long = elapsedNs - t0ElapsedNs

    /** Uptime tabanlı olay zamanı (ör. `MotionEvent.getEventTimeNanos()`) → oturum zamanı. */
    fun fromUptime(uptimeNs: Long): Long = uptimeNs + offsetNs - t0ElapsedNs

    /** `MotionEvent.getEventTime()` (ms) → oturum zamanı. */
    fun fromUptimeMillis(uptimeMs: Long): Long = fromUptime(uptimeMs * 1_000_000L)

    /**
     * Sensörün ilk olayında saat tabanını belirler. Sonraki çağrılar önbellekteki sonucu döner.
     * `eventTimestampNs` = `SensorEvent.timestamp`.
     */
    @Synchronized
    fun classifySensor(stream: String, eventTimestampNs: Long): SensorClock {
        sensorClocks[stream]?.let { return it }
        val c = classify(eventTimestampNs, ts.elapsedNanos(), ts.uptimeNanos())
        sensorClocks[stream] = c
        return c
    }

    @Synchronized
    fun sensorClocks(): Map<String, SensorClock> = LinkedHashMap(sensorClocks)

    /** Sensör zaman damgası → oturum zamanı, sınıflandırmaya göre. */
    fun fromSensor(c: SensorClock, eventTimestampNs: Long): Long = when (c.basis) {
        BASIS_ELAPSED -> fromElapsed(eventTimestampNs)
        BASIS_UPTIME -> fromUptime(eventTimestampNs)
        // Bilinmeyen taban: ilk olayda ölçülen farkla elapsed'a hizala.
        else -> fromElapsed(eventTimestampNs - c.offsetNs)
    }

    fun toMetaMap(): Map<String, Any?> = linkedMapOf(
        "t0_elapsed_ns" to t0ElapsedNs,
        "t0_uptime_ns" to t0UptimeNs,
        "t0_wall_ms" to t0WallMs,
        "sensor_clock" to sensorClocks().mapValues { (_, c) ->
            linkedMapOf("basis" to c.basis, "offset_ns" to c.offsetNs)
        },
    )

    companion object {
        const val BASIS_ELAPSED = "elapsed"
        const val BASIS_UPTIME = "uptime"
        const val BASIS_UNKNOWN = "unknown"
        private const val ONE_SECOND_NS = 1_000_000_000L

        /**
         * `offset = elapsed − uptime`. İki saat art arda okunur; elapsed iki kez (önce/sonra) okunup
         * ortalaması alınır — bu, okuma süresinin yarısını düzeltme olarak eklemekle eşdeğerdir.
         * En kısa okuma penceresine sahip 3 denemeden biri seçilir.
         */
        fun measureOffset(ts: TimeSource): Long {
            var best = Long.MAX_VALUE
            var bestOffset = 0L
            repeat(3) {
                val e1 = ts.elapsedNanos()
                val u = ts.uptimeNanos()
                val e2 = ts.elapsedNanos()
                val window = e2 - e1
                if (window < best) {
                    best = window
                    bestOffset = e1 + window / 2 - u
                }
            }
            return bestOffset
        }

        fun classify(eventTs: Long, elapsedNow: Long, uptimeNow: Long): SensorClock {
            val dElapsed = eventTs - elapsedNow
            if (abs(dElapsed) <= ONE_SECOND_NS) return SensorClock(BASIS_ELAPSED, 0)
            val dUptime = eventTs - uptimeNow
            if (abs(dUptime) <= ONE_SECOND_NS) return SensorClock(BASIS_UPTIME, dElapsed)
            return SensorClock(BASIS_UNKNOWN, dElapsed)
        }
    }
}
