package io.humansignal.sdk.internal

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/** §3.4 tablosundaki bir akış. `defaultHz = null` → değişimde (on-change) sensör. */
internal data class SensorSpec(val stream: String, val type: Int, val maxDims: Int, val defaultHz: Int?)

internal object SensorSpecs {
    val ALL: List<SensorSpec> = listOf(
        SensorSpec("acc", Sensor.TYPE_ACCELEROMETER, 3, 100),
        SensorSpec("acc_u", Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, 6, 100),
        SensorSpec("gyr", Sensor.TYPE_GYROSCOPE, 3, 100),
        SensorSpec("gyr_u", Sensor.TYPE_GYROSCOPE_UNCALIBRATED, 6, 100),
        SensorSpec("mag", Sensor.TYPE_MAGNETIC_FIELD, 3, 50),
        SensorSpec("mag_u", Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, 6, 50),
        SensorSpec("grav", Sensor.TYPE_GRAVITY, 3, 50),
        SensorSpec("lin", Sensor.TYPE_LINEAR_ACCELERATION, 3, 100),
        SensorSpec("rot", Sensor.TYPE_ROTATION_VECTOR, 5, 50),   // x,y,z,w,[heading_accuracy]
        SensorSpec("grot", Sensor.TYPE_GAME_ROTATION_VECTOR, 4, 50),
        SensorSpec("light", Sensor.TYPE_LIGHT, 1, null),
        SensorSpec("prox", Sensor.TYPE_PROXIMITY, 1, null),
        SensorSpec("press", Sensor.TYPE_PRESSURE, 1, 10),
    )

    fun byStream(s: String) = ALL.firstOrNull { it.stream == s }
}

/** Akış başına etkin/kapalı ve hedef hız (`/v1/config` → `sensors`). */
internal data class SensorSetting(val enabled: Boolean, val rateHz: Int?)

/**
 * Hareket sensörlerini ayrı bir HandlerThread üzerinde toplar (§3.4).
 *
 * **Thread modeli:** `start/stop/drain/tickRates` ve tüm `onSensorChanged` çağrıları [handler]'ın
 * thread'inde çalışır; bu yüzden tamponlar kilitsizdir.
 */
internal class SensorCollector(
    private val sm: SensorManager,
    private val handler: Handler,
    private val clock: SessionClock,
    private val settings: Map<String, SensorSetting>,
    private val onEvent: (EventLine) -> Unit,
    private val onSampleBudget: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private class Active(
        val spec: SensorSpec,
        val sensor: Sensor,
        val targetHz: Int?,
        var clock: SensorClock? = null,
        var buffer: ColumnBuffer? = null,
        var countWindow: Int = 0,
        var countTotal: Long = 0,
        val last: FloatArray = FloatArray(spec.maxDims) { Float.NaN },
        var lastDims: Int = 0,
        var rateHz: Double = 0.0,
    )

    private val active = LinkedHashMap<String, Active>()

    /** Ham örnek dinleyicisi (Saat Testi için). Sensör thread'inde çağrılır; `values` kopyalanmalıdır. */
    @Volatile var sampleTap: ((String, Long, FloatArray) -> Unit)? = null
    private val listeners = HashMap<Sensor, SensorEventListener>()
    val missing = ArrayList<String>()

    private var registered = false
    private var lastRateTickNs = 0L
    private var activeSinceNs = 0L
    private var activeAccumNs = 0L
    private var samplesSinceCheck = 0

    /** Cihazdaki sensörleri bulur. Bir kez, oturum başında çağrılır. */
    fun discover() {
        for (spec in SensorSpecs.ALL) {
            val s = settings[spec.stream]
            if (s != null && !s.enabled) continue
            val sensor = try { sm.getDefaultSensor(spec.type) } catch (e: Exception) { null }
            if (sensor == null) { missing.add(spec.stream); continue }
            active[spec.stream] = Active(spec, sensor, s?.rateHz ?: spec.defaultHz)
        }
    }

    fun register() {
        if (registered) return
        for (a in active.values) {
            try {
                val periodUs = a.targetHz?.let { 1_000_000 / it } ?: SensorManager.SENSOR_DELAY_NORMAL
                val l = listenerFor(a)
                // maxReportLatencyUs = 0 → batching yok (§3.4).
                val ok = sm.registerListener(l, a.sensor, periodUs, 0, handler)
                if (!ok) onError("register ${a.spec.stream}", IllegalStateException("registerListener=false"))
            } catch (e: Exception) {
                onError("register ${a.spec.stream}", e)
            }
        }
        registered = true
        val now = SystemClock.elapsedRealtimeNanos()
        activeSinceNs = now
        lastRateTickNs = now
        for (a in active.values) a.countWindow = 0
    }

    fun unregister() {
        if (!registered) return
        for (l in listeners.values) try { sm.unregisterListener(l) } catch (_: Exception) {}
        registered = false
        activeAccumNs += SystemClock.elapsedRealtimeNanos() - activeSinceNs
        for (a in active.values) a.rateHz = 0.0
    }

    val isRegistered: Boolean get() = registered

    /** Biriken tamponları devralır, yerlerine boş tampon koyar. */
    fun drain(): List<ColumnBuffer> {
        val out = ArrayList<ColumnBuffer>(active.size)
        for (a in active.values) {
            val b = a.buffer ?: continue
            if (b.size == 0) continue
            out.add(b)
            a.buffer = ColumnBuffer(b.stream, b.dims, maxOf(64, b.size + b.size / 4))
        }
        samplesSinceCheck = 0
        return out
    }

    fun estimatedBytes(): Int = active.values.sumOf { it.buffer?.estimatedBytes() ?: 0 }

    /** Saniyede bir çağrılır: gerçekleşen hızı hesaplar. */
    fun tickRates() {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = (now - lastRateTickNs) / 1e9
        lastRateTickNs = now
        if (!registered || dt <= 0) return
        for (a in active.values) {
            a.rateHz = a.countWindow / dt
            a.countWindow = 0
        }
    }

    fun snapshot(): List<io.humansignal.sdk.SensorStatus> = active.values.map { a ->
        io.humansignal.sdk.SensorStatus(
            stream = a.spec.stream,
            targetHz = a.targetHz,
            actualHz = a.rateHz,
            lastValues = (0 until a.lastDims).map { a.last[it] },
            clockBasis = a.clock?.basis,
        )
    }

    /** Oturum boyunca ortalama gerçekleşen hız (yalnızca ön planda geçen süreye göre). */
    fun meanHz(): Map<String, Double> {
        val activeNs = activeAccumNs + if (registered) SystemClock.elapsedRealtimeNanos() - activeSinceNs else 0
        val secs = activeNs / 1e9
        if (secs <= 0) return emptyMap()
        return active.values.filter { it.targetHz != null }.associate { a ->
            a.spec.stream to Math.round(a.countTotal / secs * 10.0) / 10.0
        }
    }

    private fun listenerFor(a: Active): SensorEventListener = listeners.getOrPut(a.sensor) {
        object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                try {
                    var c = a.clock
                    if (c == null) { c = clock.classifySensor(a.spec.stream, e.timestamp); a.clock = c }
                    val t = clock.fromSensor(c, e.timestamp)
                    var b = a.buffer
                    if (b == null) {
                        b = ColumnBuffer(a.spec.stream, minOf(e.values.size, a.spec.maxDims).coerceAtLeast(1),
                            (a.targetHz ?: 4) * 6)
                        a.buffer = b
                    }
                    // SensorEvent yeniden kullanılır: değerler hemen kopyalanır.
                    b.add(t, e.values)
                    val n = minOf(e.values.size, a.last.size)
                    System.arraycopy(e.values, 0, a.last, 0, n)
                    a.lastDims = minOf(n, b.dims)
                    a.countWindow++
                    a.countTotal++
                    sampleTap?.invoke(a.spec.stream, t, e.values)
                    if (++samplesSinceCheck >= 512) { samplesSinceCheck = 0; onSampleBudget() }
                } catch (ex: Exception) {
                    onError("sensor ${a.spec.stream}", ex)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                try {
                    val t = clock.now()
                    onEvent(EventLine(t, "sensor_acc", JsonWriter.obj {
                        field("k", "sensor_acc"); field("s", a.spec.stream); field("t", t); field("a", accuracy)
                    }))
                } catch (_: Exception) {}
            }
        }
    }
}
