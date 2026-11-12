package io.humansignal.sdk.internal

import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import io.humansignal.sdk.SensorStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Kayıt ilerledikçe SDK'ya bildirilen anlık durum. */
internal data class RecorderStats(
    val sensors: List<SensorStatus>,
    val sensorsMissing: List<String>,
    val chunksWritten: Int,
    val droppedChunks: Int,
    val paused: Boolean,
)

internal data class StopSummary(
    val totalChunks: Int,
    val durationS: Double,
    val sensorHz: Map<String, Double>,
    val sensorsMissing: List<String>,
    val sensorClock: Map<String, Any?>,
)

/**
 * Tek bir açık oturumun kaydını yürütür: sensör toplama, olay tamponu, 5 sn'lik parça kesme (ChunkWriter),
 * şifreli diske yazma.
 *
 * Thread'ler:
 *  - `HumanSignal-sensors` (HandlerThread): sensör callback'leri, saniyelik tik, parça kesme.
 *  - `HumanSignal-io` (tek thread): gzip + SHA-256 + AES-GCM + disk yazma.
 *  - Diğer thread'ler yalnızca [addEvent] ile olay ekler (kilitli liste).
 */
internal class SessionRecorder(
    private val localSessionId: String,
    private val sdkVersion: String,
    private val clock: SessionClock,
    private val store: LocalStore,
    sensorManager: SensorManager,
    sensorSettings: Map<String, SensorSetting>,
    private val chunkSeconds: Int,
    private val maxChunkUncompressedBytes: Int,
    private val onStats: (RecorderStats) -> Unit,
    private val onError: (String, Throwable) -> Unit,
    private val onStoreChanged: () -> Unit,
) {
    private val thread = HandlerThread("HumanSignal-sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
    private val handler = Handler(thread.looper)
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "HumanSignal-io") }

    private val evLock = Any()
    private var events = ArrayList<EventLine>(256)
    private var eventBytes = 0

    // Yalnızca sensör thread'inde:
    private var seq = 0
    private var chunkStartT = 0L
    private var lastChunkCutNs = 0L
    private var paused = false
    private var stopped = false

    @Volatile private var chunksWritten = 0
    @Volatile private var droppedChunks = 0

    private val sensors = SensorCollector(
        sensorManager, handler, clock, sensorSettings,
        onEvent = ::addEvent,
        onSampleBudget = { if (sizeExceeded()) cutChunk() },
        onError = onError,
    )

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                clock.refreshOffset()          // §3.3: her 1 saniyede offset yeniden ölçülür
                sensors.tickRates()
                val now = clock.now()
                if (now - lastChunkCutNs >= chunkSeconds * 1_000_000_000L || sizeExceeded()) cutChunk()
                publish()
            } catch (e: Exception) {
                onError("tick", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Sensörleri bulur ve kaydı başlatır. Çağıran thread'i bloklar (kısa). */
    fun start(foreground: Boolean): List<String> {
        runOnHandlerBlocking {
            sensors.discover()
            chunkStartT = 0
            lastChunkCutNs = clock.now()
            if (foreground) sensors.register() else paused = true
            handler.postDelayed(tick, 1000)
            publish()
        }
        return ArrayList(sensors.missing)
    }

    fun addEvent(e: EventLine) {
        val needCut: Boolean
        synchronized(evLock) {
            events.add(e)
            eventBytes += e.json.length + 1
            needCut = eventBytes > maxChunkUncompressedBytes / 2
        }
        if (needCut) handler.post { if (sizeExceeded()) cutChunk() }
    }

    /** Uygulama arka plana geçti: dinleyiciler kaldırılır (§3.2, §3.4). */
    fun pause() = handler.post {
        if (stopped || paused) return@post
        paused = true
        sensors.unregister()
        publish()
    }

    fun resume() = handler.post {
        if (stopped || !paused) return@post
        paused = false
        sensors.register()
        publish()
    }

    var sampleTap: ((String, Long, FloatArray) -> Unit)?
        get() = sensors.sampleTap
        set(v) { sensors.sampleTap = v }

    /** Zamanlanmış iş (ör. arka plan zaman aşımı) sensör thread'inde çalıştırılır. */
    fun postDelayed(delayMs: Long, r: Runnable) = handler.postDelayed(r, delayMs)
    fun cancel(r: Runnable) = handler.removeCallbacks(r)

    /**
     * Toplayıcıları durdurur, son parçayı yazar ve tüm disk işleri bitene kadar bekler.
     * Ana thread'den çağrılmamalı.
     */
    fun stop(): StopSummary {
        var summary: StopSummary? = null
        runOnHandlerBlocking {
            handler.removeCallbacks(tick)
            sensors.unregister()
            sensors.tickRates()
            cutChunk()
            stopped = true
            summary = StopSummary(
                totalChunks = seq,
                durationS = Math.round(clock.now() / 1e8) / 10.0,
                sensorHz = sensors.meanHz(),
                sensorsMissing = ArrayList(sensors.missing),
                sensorClock = clock.toMetaMap(),
            )
        }
        io.shutdown()
        io.awaitTermination(30, TimeUnit.SECONDS)
        thread.quitSafely()
        return summary!!
    }

    /** Diski silmeden önce: kaydı yazmadan durdurur. */
    fun abort() {
        runOnHandlerBlocking {
            handler.removeCallbacks(tick)
            sensors.unregister()
            stopped = true
        }
        io.shutdownNow()
        io.awaitTermination(5, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---- sensör thread'i ----

    private fun sizeExceeded(): Boolean {
        val ev = synchronized(evLock) { eventBytes }
        return sensors.estimatedBytes() + ev >= maxChunkUncompressedBytes
    }

    /** ChunkWriter: tamponları devralır ve IO thread'inde kodlayıp yazar. */
    private fun cutChunk() {
        val sensorBufs = sensors.drain()
        val evs = synchronized(evLock) {
            val e = events
            events = ArrayList(maxOf(64, e.size))
            eventBytes = 0
            e
        }
        val tEnd = clock.now()
        lastChunkCutNs = tEnd
        val content = ChunkContent(seq, chunkStartT, tEnd, sensorBufs, evs)
        chunkStartT = tEnd
        if (content.isEmpty) return     // boş parça yazılmaz; seq boşluksuz kalır
        seq++
        io.execute {
            try {
                val enc = ChunkEncoder.encode(content, localSessionId, null, sdkVersion)
                val r = store.writeChunk(localSessionId, null, enc)
                // Süreç ölürse oturum init'te bu sayıyla kapatılır.
                store.updateSessionMeta(localSessionId) { it["chunks_written"] = content.seq + 1 }
                chunksWritten++
                droppedChunks += r.droppedEntries.count { it.localSessionId == localSessionId }
                onStoreChanged()
            } catch (e: Throwable) {
                onError("chunk ${content.seq}", e)
            }
        }
    }

    private fun publish() {
        onStats(RecorderStats(sensors.snapshot(), ArrayList(sensors.missing), chunksWritten, droppedChunks, paused))
    }

    private fun runOnHandlerBlocking(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post {
            try { block() } catch (e: Throwable) { err = e } finally { latch.countDown() }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("sensor thread timeout")
        err?.let { throw it }
    }
}
