package io.humansignal.sdk.internal

import io.humansignal.sdk.ClockTestState
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Saat Testi analizi (§3.3 kabul kriteri, §4.2 madde 7).
 *
 * Her `touch DOWN` zamanı için, `[DOWN − window, DOWN + window]` aralığındaki ivme örneklerinde en büyük
 * **sarsıntı** (ardışık iki örnek arasındaki ivme farkının büyüklüğü / dt) bulunur; fark = t_sıçrama − t_DOWN.
 * Analiz, pencerenin sonu geçtikten sonra yapılır.
 *
 * Thread güvenli: ivme örnekleri sensör thread'inden, DOWN'lar ana thread'den gelir.
 */
internal class ClockTestAnalyzer(
    private val targetTaps: Int = 10,
    private val windowNs: Long = 150_000_000L,
    /** Bu değerin altındaki sarsıntı (m/s³) "sıçrama yok" sayılır. */
    private val minJerk: Double = 50.0,
    capacity: Int = 1024,
) {
    private val t = LongArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private var head = 0
    private var size = 0
    private val pendingDowns = ArrayDeque<Long>()
    private val diffsMs = ArrayList<Double>()
    private var missed = 0

    @Synchronized
    fun onAcc(tNs: Long, v: FloatArray) {
        t[head] = tNs; x[head] = v[0]; y[head] = v[1]; z[head] = v[2]
        head = (head + 1) % t.size
        if (size < t.size) size++
        // Penceresi kapanan DOWN'ları analiz et.
        while (pendingDowns.isNotEmpty() && tNs >= pendingDowns.first() + windowNs) {
            analyze(pendingDowns.removeFirst())
        }
    }

    @Synchronized
    fun onDown(tNs: Long) {
        if (diffsMs.size + missed + pendingDowns.size >= targetTaps) return
        pendingDowns.addLast(tNs)
    }

    @Synchronized
    fun state(): ClockTestState {
        val sorted = diffsMs.map { abs(it) }.sorted()
        val median = if (sorted.isEmpty()) null
        else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return ClockTestState(
            targetTaps = targetTaps,
            tapsAnalyzed = diffsMs.size,
            tapsWithoutSpike = missed,
            diffsMs = ArrayList(diffsMs),
            medianAbsMs = median,
            passed = if (diffsMs.size + missed >= targetTaps && median != null) median < 20.0 else null,
        )
    }

    private fun analyze(down: Long) {
        var bestJerk = 0.0
        var bestT = 0L
        var prev = -1
        // Halka tamponu kronolojik sırayla dolaş.
        for (k in 0 until size) {
            val i = (head - size + k + t.size) % t.size
            if (t[i] < down - windowNs - 20_000_000L) { prev = i; continue }
            if (t[i] > down + windowNs) break
            if (prev >= 0) {
                val dt = (t[i] - t[prev]) / 1e9
                if (dt > 0) {
                    val dx = (x[i] - x[prev]).toDouble(); val dy = (y[i] - y[prev]).toDouble(); val dz = (z[i] - z[prev]).toDouble()
                    val jerk = sqrt(dx * dx + dy * dy + dz * dz) / dt
                    if (t[i] >= down - windowNs && jerk > bestJerk) { bestJerk = jerk; bestT = t[i] }
                }
            }
            prev = i
        }
        if (bestJerk < minJerk) missed++ else diffsMs.add((bestT - down) / 1e6)
    }
}
