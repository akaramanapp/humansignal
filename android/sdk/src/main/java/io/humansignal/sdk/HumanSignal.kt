package io.humansignal.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import io.humansignal.sdk.internal.AesGcmCipher
import io.humansignal.sdk.internal.ApiClient
import io.humansignal.sdk.internal.JsonParser
import io.humansignal.sdk.internal.UploadEngine
import io.humansignal.sdk.internal.UploadOutcome
import io.humansignal.sdk.internal.UploadWorker
import io.humansignal.sdk.internal.AndroidTimeSource
import io.humansignal.sdk.internal.ClockTestAnalyzer
import io.humansignal.sdk.internal.DeviceCollector
import io.humansignal.sdk.internal.EventLine
import io.humansignal.sdk.internal.EventSink
import io.humansignal.sdk.internal.JsonWriter
import io.humansignal.sdk.internal.KeystoreKey
import io.humansignal.sdk.internal.LocalStore
import io.humansignal.sdk.internal.SensorSetting
import io.humansignal.sdk.internal.SensorSpecs
import io.humansignal.sdk.internal.SessionClock
import io.humansignal.sdk.internal.SessionRecorder
import io.humansignal.sdk.internal.TextFieldWatcher
import io.humansignal.sdk.internal.TouchCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.WeakHashMap

/**
 * HumanSignal SDK giriş noktası (§3.2).
 *
 * M1: saat tabanı, SensorCollector, ChunkWriter, LocalStore, yaşam döngüsü, `mark`.
 * M2: TouchCollector, SecureKeypadView, trackTextField, DeviceCollector (anlık görüntü, batarya, bütünlük), Saat Testi.
 * M3: `/v1/config` (son bilinen/gömülü varsayılana düşer), sunucu kaydı ve Uploader (WorkManager).
 * Ağ yoksa oturum yerelde açılır; kayıt bağlantı gelince yapılır. Toplama ağ yüzünden asla engellenmez (§3.2).
 */
object HumanSignal {
    const val SDK_VERSION: String = BuildConfig.SDK_VERSION
    private const val TAG = "HumanSignal"
    private const val PREFS = "io.humansignal.sdk"
    private const val BACKGROUND_TIMEOUT_MS = 10 * 60 * 1000L
    private const val BATTERY_INTERVAL_MS = 30_000L
    private const val CHUNK_SECONDS = 5
    private const val MAX_CHUNK_UNCOMPRESSED = 2 * 1024 * 1024
    private const val CONFIG_VERSION_EMBEDDED = 1
    private const val NET_TIMEOUT_AT_START_SEC = 3L
    private const val UPLOAD_KICK_INTERVAL_MS = 30_000L

    private val _status = MutableStateFlow(CollectorStatus())
    /** Canlı durum: toplanan Hz değerleri, kuyruktaki parça sayısı, hata. */
    val status: StateFlow<CollectorStatus> = _status.asStateFlow()

    private val _lastSummary = MutableStateFlow<SessionSummary?>(null)
    /** Son kapanan oturumun özeti; henüz oturum kapanmadıysa null. */
    val lastSummary: StateFlow<SessionSummary?> = _lastSummary.asStateFlow()

    private val _clockTest = MutableStateFlow<ClockTestState?>(null)
    /** Saat Testi durumu; [startClockTest] çağrılana kadar null. */
    val clockTest: StateFlow<ClockTestState?> = _clockTest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Application? = null
    @Volatile private var config: HumanSignalConfig? = null
    @VisibleForTesting @Volatile internal var store: LocalStore? = null
        private set
    private lateinit var prefs: SharedPreferences
    private lateinit var device: DeviceCollector

    /** Açık oturum. Toplayıcılar buradan yazar. */
    private class Active(val sid: String, override val clock: SessionClock, val recorder: SessionRecorder) : EventSink {
        override fun add(e: EventLine) = recorder.addEvent(e)
    }
    @Volatile private var active: Active? = null

    private val touch = TouchCollector(
        sink = { active },
        screenName = { currentScreen },
        onError = { w, e -> reportError(w, e) },
    )
    private val textWatchers = WeakHashMap<EditText, TextTracking>()

    private var startedActivities = 0
    @Volatile private var foreground = false
    @Volatile private var currentScreen: String? = null
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var screenReceiver: BroadcastReceiver? = null
    private val integrityObservers = ArrayList<ContentObserver>()
    private var lastIntegrity: Map<String, Any?> = emptyMap()
    private var clockAnalyzer: ClockTestAnalyzer? = null
    @Volatile private var lastUploadKickMs = 0L
    private val api: ApiClient? by lazy {
        val c = config ?: return@lazy null
        if (c.apiKey.isBlank() || c.baseUrl.isBlank()) null else ApiClient(c.baseUrl, c.apiKey, "HumanSignalSDK/$SDK_VERSION")
    }

    /** `/v1/config`'ten (ya da son bilinen / gömülü) çözülmüş ayarlar. */
    private data class RemoteConfig(
        val version: Int,
        val chunkSeconds: Int,
        val sensors: Map<String, SensorSetting>,
        val wifiOnly: Boolean,
    )

    private val backgroundTimeout = Runnable {
        log("background > 10 min → stopping session")
        scope.launch { stopSession(StopReason.TIMEOUT) }
    }

    private val batteryTick = object : Runnable {
        override fun run() {
            val a = active ?: return
            try {
                val t = a.clock.now()
                device.batteryLine(t)?.let { a.add(EventLine(t, "battery", it)) }
            } catch (e: Exception) { reportError("battery", e) }
            a.recorder.postDelayed(BATTERY_INTERVAL_MS, this)
        }
    }

    // ------------------------------------------------------------------ public API

    /** Application.onCreate içinde bir kez çağrılır. */
    fun init(app: Application, config: HumanSignalConfig) = guard("init") {
        if (this.app != null) { log("init called twice; ignored"); return@guard }
        this.app = app
        this.config = config
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        device = DeviceCollector(app, config.mode == Mode.PRODUCTION, SDK_VERSION)
        val root = File(app.noBackupFilesDir, "humansignal")
        store = LocalStore(root, AesGcmCipher { KeystoreKey.getOrCreate() }, config.maxLocalStorageMb * 1024L * 1024L)
        foreground = isProcessForeground()
        lastOrientation = app.resources.configuration.orientation
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        app.registerComponentCallbacks(componentCallbacks)
        (app.getSystemService(Context.INPUT_SERVICE) as? InputManager)?.registerInputDeviceListener(inputDeviceListener, main)
        _status.update { it.copy(initialized = true, consentGiven = consentGiven()) }
        refreshStoreStatus()
        closeOrphanedSessions()
        // Önceki çalıştırmalardan kalan yüklenmemiş veri varsa yüklemeyi başlat.
        if (store!!.sessionIds().isNotEmpty()) kickUpload(force = true, restart = true)
        log("init ok, sdk=$SDK_VERSION, mode=${config.mode}, upload=${if (api != null) "on" else "off (no apiKey)"}")
    }

    /** Rıza alınmadan hiçbir veri toplanmaz. Rıza geri alınırsa açık oturum durdurulur. */
    fun setConsent(given: Boolean, consentVersion: String) = guard("setConsent") {
        requireInit()
        prefs.edit().putBoolean("consent", given).putString("consent_version", consentVersion).apply()
        _status.update { it.copy(consentGiven = given) }
        if (!given && active != null) scope.launch { stopSession(StopReason.CONSENT_REVOKED) }
    }

    /** Oturum açar, toplayıcıları başlatır. Rıza yoksa [IllegalStateException]. */
    suspend fun startSession(meta: SessionMeta): SessionInfo = withContext(Dispatchers.IO) {
        requireInit()
        check(consentGiven()) { "consent not given" }
        validate(meta)
        sessionMutex.withLock {
            check(active == null) { "a session is already open" }
            val app = app!!
            val store = store!!
            val sid = UUID.randomUUID().toString()
            val rc = resolveConfig()
            val c = SessionClock(AndroidTimeSource)
            val snapshot = try { device.snapshot() } catch (e: Exception) { reportError("device snapshot", e); null }
            lastIntegrity = (snapshot?.get("integrity") as? Map<String, Any?>) ?: emptyMap()
            store.writeSessionMeta(sid, initialMeta(sid, meta, c, snapshot, rc.version))
            val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val rec = SessionRecorder(
                localSessionId = sid,
                sdkVersion = SDK_VERSION,
                clock = c,
                store = store,
                sensorManager = sm,
                sensorSettings = rc.sensors,
                chunkSeconds = rc.chunkSeconds,
                maxChunkUncompressedBytes = MAX_CHUNK_UNCOMPRESSED,
                onStats = { s ->
                    touch.tickRate()
                    clockAnalyzer?.let { a -> _clockTest.value = a.state() }
                    _status.update {
                        it.copy(sensors = s.sensors, sensorsMissing = s.sensorsMissing, chunksWritten = s.chunksWritten,
                            droppedChunks = s.droppedChunks, paused = s.paused,
                            touchEvents = touch.touchLines, touchEventsPerSec = touch.ratePerSec)
                    }
                },
                onError = { where, e -> reportError(where, e) },
                onStoreChanged = { refreshStoreStatus(); kickUpload() },
            )
            val a = Active(sid, c, rec)
            val missing = rec.start(foreground)
            store.updateSessionMeta(sid) { it["sensors_missing"] = missing }
            withContext(Dispatchers.Main) {
                touch.resetForSession()
                active = a
                touch.writeAllInputDevices()
                lifecycle(if (foreground) "foreground" else "background")
                registerScreenReceiver(app)
                registerIntegrityObservers(app)
            }
            rec.postDelayed(0, batteryTick)
            _status.update {
                it.copy(sessionActive = true, localSessionId = sid, lastError = null, chunksWritten = 0, droppedChunks = 0,
                    touchEvents = 0, touchEventsPerSec = 0.0)
            }
            log("session started local=$sid missing=$missing config_v=${rc.version}")
            // Sunucu kaydı: toplama zaten başladı; ağ yoksa yerel id ile devam edilir, Uploader sonra kaydeder.
            val serverId = tryRegister(sid)
            _status.update { it.copy(serverSessionId = serverId) }
            kickUpload(force = true)
            SessionInfo(serverId ?: sid, rc.version)
        }
    }

    /** Toplayıcıları durdurur, son parçayı yazar, oturumu kapanmış olarak işaretler. */
    suspend fun stopSession(reason: StopReason = StopReason.USER): Unit = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val a = active ?: return@withLock
            try {
                withContext(Dispatchers.Main) { detachSessionHooks(a) }
                val s = a.recorder.stop()
                val touchEvents = touch.touchLines
                store!!.updateSessionMeta(a.sid) { m ->
                    m["state"] = "closed"
                    m["total_chunks"] = s.totalChunks
                    m["stop_reason"] = reason.wire
                    m["ended_at_wall_ms"] = System.currentTimeMillis()
                    m["clock"] = s.sensorClock
                    m["stats"] = linkedMapOf(
                        "duration_s" to s.durationS,
                        "sensor_hz" to s.sensorHz,
                        "touch_events" to touchEvents,
                        "dropped_chunks" to ((m["dropped_chunks"] as? Number)?.toInt() ?: 0),
                        "failed_chunks" to 0,
                        "sensors_missing" to s.sensorsMissing,
                    )
                }
                _lastSummary.value = SessionSummary(
                    localSessionId = a.sid,
                    serverSessionId = store!!.readSessionMeta(a.sid)?.get("server_session_id") as? String,
                    stopReason = reason,
                    durationS = s.durationS,
                    totalChunks = s.totalChunks,
                    sensorHz = s.sensorHz,
                    sensorsMissing = s.sensorsMissing,
                    touchEvents = touchEvents,
                )
                log("session stopped local=${a.sid} reason=${reason.wire} chunks=${s.totalChunks} touch=$touchEvents hz=${s.sensorHz}")
                touch.observeStats().let { (n, p50, p99, mx) ->
                    log(String.format(java.util.Locale.US, "perf: touch observe n=%.0f p50=%.1fus p99=%.1fus max=%.1fus", n, p50, p99, mx))
                }
                kickUpload(force = true)
            } catch (e: Throwable) {
                reportError("stopSession", e)
            } finally {
                _status.update { it.copy(sessionActive = false, paused = false, localSessionId = null, serverSessionId = null, sensors = emptyList(), touchEventsPerSec = 0.0) }
                refreshStoreStatus()
            }
        }
    }

    /** Zaman çizelgesine işaret koyar: görev başlangıcı/bitişi, ekran değişimi vb. */
    fun mark(name: String, attrs: Map<String, String> = emptyMap()) = guard("mark") {
        val a = active ?: return@guard
        if (name == "screen") attrs["name"]?.let { currentScreen = it }
        val t = a.clock.now()
        a.add(EventLine(t, "marker", JsonWriter.obj {
            field("k", "marker"); field("t", t); field("name", name)
            this.name("attrs").any(attrs)
        }))
    }

    /** Sistem klavyesi kullanılan bir alanı izler (sadece zamanlama ve uzunluk, içerik ASLA). Ana thread'den çağrılır. */
    fun trackTextField(view: EditText, fieldId: String) = guard("trackTextField") {
        if (textWatchers.containsKey(view)) untrackTextField(view)
        val tr = TextTracking(view, fieldId)
        textWatchers[view] = tr
        tr.attach()
    }

    fun untrackTextField(view: EditText) = guard("untrackTextField") {
        textWatchers.remove(view)?.detach()
    }

    /** Telefondaki tüm yüklenmemiş veriyi siler (rıza geri alma / test). Açık oturum kaydedilmeden kapanır. */
    fun deleteLocalData() = guard("deleteLocalData") {
        requireInit()
        scope.launch {
            sessionMutex.withLock {
                active?.let { a ->
                    withContext(Dispatchers.Main) { detachSessionHooks(a) }
                    a.recorder.abort()
                }
                store?.deleteAll()
                _status.update {
                    it.copy(sessionActive = false, paused = false, localSessionId = null, sensors = emptyList(),
                        chunksWritten = 0, droppedChunks = 0, touchEvents = 0, touchEventsPerSec = 0.0)
                }
                refreshStoreStatus()
                log("local data deleted")
            }
        }
    }

    /**
     * Saat Testi'ni başlatır (geliştirici, §4.2): açık oturum gerekir. Kullanıcı ekrana [taps] kez sertçe vurur;
     * sonuç [clockTest] ile yayınlanır.
     */
    fun startClockTest(taps: Int = 10) = guard("startClockTest") {
        val a = active ?: throw IllegalStateException("clock test needs an open session")
        val an = ClockTestAnalyzer(targetTaps = taps)
        clockAnalyzer = an
        a.recorder.sampleTap = { stream, t, v -> if (stream == "acc") an.onAcc(t, v) }
        touch.downListener = { t -> an.onDown(t) }
        _clockTest.value = an.state()
        mark("clock_test_start", mapOf("taps" to taps.toString()))
    }

    fun stopClockTest() = guard("stopClockTest") {
        clockAnalyzer?.let { an ->
            _clockTest.value = an.state()
            mark("clock_test_end", mapOf("median_abs_ms" to (an.state().medianAbsMs?.toString() ?: "")))
        }
        clockAnalyzer = null
        active?.recorder?.sampleTap = null
        touch.downListener = null
    }

    // ------------------------------------------------------------------ Uploader köprüsü

    /** Worker için yükleme motoru; init yapılmadıysa ya da API anahtarı yoksa null. */
    internal fun uploadEngine(): UploadEngine? {
        val s = store ?: return null
        val a = api ?: return null
        return UploadEngine(s, a, onProgress = { refreshStoreStatus() }, log = { log("upload: $it") })
    }

    internal fun wifiOnly(): Boolean = config?.wifiOnlyUpload == true || cachedConfig()?.wifiOnly == true

    internal fun onUploadResult(error: String?, retryInSec: Long? = null) {
        if (error != null) prefs.edit().putString("upload_blocked_key", keyFingerprint()).apply()
        else prefs.edit().remove("upload_blocked_key").apply()
        _status.update { it.copy(uploadError = error, uploadRetryInSec = retryInSec) }
        refreshStoreStatus()
        // Açık oturum bu turda kaydolduysa durumda göster.
        active?.let { a -> store?.readSessionMeta(a.sid)?.get("server_session_id")?.let { id -> _status.update { it.copy(serverSessionId = id as String) } } }
        if (error != null && config?.debugLogging == true) Log.w(TAG, error)
    }

    /** Yükleme işini kuyruğa ekler (oturum sırasında en fazla 30 sn'de bir). */
    private fun kickUpload(force: Boolean = false, restart: Boolean = false) {
        val app = app ?: return
        if (api == null) return
        // 401/403 sonrası aynı anahtarla tekrar denenmez (§3.10); anahtar değişince açılır.
        if (prefs.getString("upload_blocked_key", null) == keyFingerprint()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastUploadKickMs < UPLOAD_KICK_INTERVAL_MS) return
        lastUploadKickMs = now
        try {
            if (restart) UploadWorker.restart(app, wifiOnly()) else UploadWorker.enqueue(app, wifiOnly())
        } catch (e: Exception) { reportError("enqueue upload", e) }
    }

    private fun keyFingerprint(): String =
        // Anahtar ya da adres değişince engel kalkar.
        io.humansignal.sdk.internal.ChunkEncoder.sha256Hex("${config?.baseUrl}|${config?.apiKey}".toByteArray()).take(16)

    /** Oturum başında kısa zaman aşımıyla sunucuya kaydolmayı dener. */
    private fun tryRegister(localSessionId: String): String? {
        val a = api ?: return null
        val s = store ?: return null
        return try {
            val (o, id) = try {
                UploadEngine(s, a.withTimeout(NET_TIMEOUT_AT_START_SEC), log = { log("register: $it") }).register(localSessionId)
            } catch (e: IllegalArgumentException) { UploadOutcome.CONFIG_ERROR to null }
            if (o == UploadOutcome.AUTH_FAILED) onUploadResult("upload: unauthorized (401/403)")
            if (o == UploadOutcome.CONFIG_ERROR) onUploadResult("upload: invalid configuration (baseUrl)")
            id
        } catch (e: Exception) {
            log("register failed (${e.javaClass.simpleName}); continuing with local id")
            null
        }
    }

    /** `/v1/config` → son bilinen → gömülü varsayılan (§6.4). */
    private fun resolveConfig(): RemoteConfig {
        val a = api
        if (a != null) {
            try {
                val r = a.withTimeout(NET_TIMEOUT_AT_START_SEC).getConfig()
                if (r.ok && r.json != null) {
                    parseConfig(r.json)?.let { rc ->
                        prefs.edit().putString("last_config", JsonWriter.encode(r.json)).apply()
                        return rc
                    }
                }
            } catch (e: Exception) { log("config fetch failed (${e.javaClass.simpleName}); using cached/embedded") }
        }
        return cachedConfig() ?: RemoteConfig(CONFIG_VERSION_EMBEDDED, CHUNK_SECONDS, defaultSensorSettings(), false)
    }

    private fun cachedConfig(): RemoteConfig? = try {
        prefs.getString("last_config", null)?.let { parseConfig(JsonParser.parseObject(it)) }
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(j: Map<String, Any?>): RemoteConfig? {
        val version = (j["config_version"] as? Number)?.toInt() ?: return null
        val chunk = ((j["chunk_seconds"] as? Number)?.toInt() ?: CHUNK_SECONDS).coerceIn(1, 60)
        val sensors = LinkedHashMap(defaultSensorSettings())
        (j["sensors"] as? Map<String, Any?>)?.forEach { (name, v) ->
            val m = v as? Map<String, Any?> ?: return@forEach
            if (SensorSpecs.byStream(name) == null) return@forEach
            val enabled = m["enabled"] as? Boolean ?: true
            val hz = (m["rate_hz"] as? Number)?.toInt()?.coerceIn(1, 200)   // 200 Hz üstü izin gerektirir (§3.1)
            sensors[name] = SensorSetting(enabled, hz ?: SensorSpecs.byStream(name)!!.defaultHz)
        }
        val wifi = ((j["upload"] as? Map<String, Any?>)?.get("wifi_only") as? Boolean) ?: false
        return RemoteConfig(version, chunk, sensors, wifi)
    }

    // ------------------------------------------------------------------ SecureKeypadView / TouchCollector köprüsü

    internal fun registerMaskView(v: View) = touch.registerMaskView(v)
    internal fun unregisterMaskView(v: View) = touch.unregisterMaskView(v)

    /** MotionEvent zamanı → oturum zamanı; açık oturum yoksa null. */
    internal fun eventSessionTime(ev: MotionEvent): Long? {
        val c = active?.clock ?: return null
        return if (Build.VERSION.SDK_INT >= 34) c.fromUptime(ev.eventTimeNanos) else c.fromUptimeMillis(ev.eventTime)
    }

    internal fun recordKey(
        fieldId: String, td: Long, tu: Long, dx: Float, dy: Float, pr: Float, sz: Float, ma: Float,
        kc: KeyClass, digit: Int?, a11y: Boolean,
    ) = guard("recordKey") {
        val a = active ?: return@guard
        a.add(EventLine(td, "key", JsonWriter.obj {
            field("k", "key"); field("f", fieldId); field("td", td); field("tu", tu)
            name("dx").dec1(dx.toDouble()); name("dy").dec1(dy.toDouble())
            name("pr").sig5(pr); name("sz").sig5(sz); name("ma").dec1(ma.toDouble())
            field("kc", kc.wire)
            field("kid", keyIdentity(kc, digit))
            if (a11y) field("a11y", true)
        }))
    }

    /** Dokunmasız tıklama (erişilebilirlik servisi / programatik): güçlü bot sinyali. */
    internal fun recordKeyWithoutTouch(fieldId: String, kc: KeyClass, digit: Int?) = guard("recordKey") {
        val a = active ?: return@guard
        val t = a.clock.now()
        a.add(EventLine(t, "key", JsonWriter.obj {
            field("k", "key"); field("f", fieldId); field("td", t); field("tu", t)
            name("dx").nullValue(); name("dy").nullValue(); name("pr").nullValue(); name("sz").nullValue(); name("ma").nullValue()
            field("kc", kc.wire)
            field("kid", keyIdentity(kc, digit))
            field("a11y", true)
        }))
    }

    /** `kid` yalnızca `recordKeyIdentity = true` iken doldurulur (§3.6.1). */
    private fun keyIdentity(kc: KeyClass, digit: Int?): String? =
        if (config?.recordKeyIdentity == true && kc == KeyClass.DIGIT) digit?.toString() else null

    // ------------------------------------------------------------------ iç

    private class TextTracking(view: EditText, val fieldId: String) {
        private val ref = java.lang.ref.WeakReference(view)
        private var hadFocus = view.hasFocus()
        val watcher = TextFieldWatcher(fieldId, sink = { active }, imeActive = {
            val v = ref.get()
            v != null && v.hasFocus() &&
                (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.isActive(v) == true
        })
        private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
            val v = ref.get() ?: return@OnGlobalFocusChangeListener
            val has = v.hasFocus()
            if (has != hadFocus) {
                hadFocus = has
                val a = active ?: return@OnGlobalFocusChangeListener
                val t = a.clock.now()
                a.add(EventLine(t, "focus", TextFieldWatcher.encodeFocus(fieldId, t, has)))
            }
        }
        private var observer: ViewTreeObserver? = null
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = addFocusObserver(v)
            override fun onViewDetachedFromWindow(v: View) = removeFocusObserver()
        }

        fun attach() {
            val v = ref.get() ?: return
            v.addTextChangedListener(watcher)
            v.addOnAttachStateChangeListener(attachListener)
            if (v.isAttachedToWindow) addFocusObserver(v)
        }

        fun detach() {
            val v = ref.get()
            v?.removeTextChangedListener(watcher)
            v?.removeOnAttachStateChangeListener(attachListener)
            removeFocusObserver()
        }

        private fun addFocusObserver(v: View) {
            removeFocusObserver()
            observer = v.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(focusListener) }
        }

        private fun removeFocusObserver() {
            observer?.let { if (it.isAlive) it.removeOnGlobalFocusChangeListener(focusListener) }
            observer = null
        }
    }

    /** Süreç oturum açıkken öldüyse (çökme, sistem öldürmesi) yarım oturumu kapatır; yüklemesi tamamlanabilsin. */
    private fun closeOrphanedSessions() {
        val st = store ?: return
        for (sid in st.sessionIds()) {
            try {
                val m = st.readSessionMeta(sid) ?: continue
                if (m["state"] != "open") continue
                st.updateSessionMeta(sid) {
                    it["state"] = "closed"
                    it["stop_reason"] = "process_died"
                    it["total_chunks"] = (it["chunks_written"] as? Number)?.toInt() ?: 0
                }
                log("closed orphaned session local=$sid")
            } catch (e: Exception) { reportError("orphan session", e) }
        }
    }

    /** Ana thread: oturuma bağlı alıcıları kaldırır ve `active`'i boşaltır (yeni olay yazılmaz). */
    private fun detachSessionHooks(a: Active) {
        a.recorder.cancel(backgroundTimeout)
        a.recorder.cancel(batteryTick)
        unregisterScreenReceiver()
        unregisterIntegrityObservers()
        if (clockAnalyzer != null) stopClockTest()
        active = null
    }

    private fun consentGiven(): Boolean = this::prefs.isInitialized && prefs.getBoolean("consent", false)

    private fun installId(): String {
        prefs.getString("install_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", id).apply()
        return id
    }

    private fun requireInit() = check(app != null) { "HumanSignal.init must be called first" }

    private fun validate(meta: SessionMeta) {
        val l = meta.label
        when (l.kind) {
            Label.Kind.HUMAN -> {
                require(meta.participantCode != null && Regex("^P\\d{3,5}$").matches(meta.participantCode)) {
                    "participantCode (P + 3..5 digits) is required for human sessions"
                }
                require(l.botTool == null && l.botHost == null) { "botTool/botHost must be null for human sessions" }
            }
            Label.Kind.BOT -> require(l.botTool != null && l.botHost != null) { "botTool and botHost are required for bot sessions" }
        }
    }

    private fun initialMeta(sid: String, meta: SessionMeta, c: SessionClock, snapshot: Map<String, Any?>?, configVersion: Int): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val appVersion = (snapshot?.get("app") as? Map<String, Any?>)?.get("app_version")
        return linkedMapOf(
            "v" to 1,
            "state" to "open",
            "local_session_id" to sid,
            "server_session_id" to null,
            "install_id" to installId(),
            "participant" to meta.participantCode?.let { code ->
                linkedMapOf<String, Any?>("code" to code).apply {
                    meta.participantInfo?.let { pi ->
                        put("handedness", pi.handedness?.wire); put("age_range", pi.ageRange?.wire); put("gender", pi.gender)
                    }
                }
            },
            "consent_version" to prefs.getString("consent_version", null),
            "label" to linkedMapOf("kind" to meta.label.kind.wire, "bot_tool" to meta.label.botTool?.wire,
                "bot_host" to meta.label.botHost?.wire),
            "context" to linkedMapOf("placement" to meta.context.placement.wire, "posture" to meta.context.posture.wire,
                "hands" to meta.context.hands.wire),
            "notes" to meta.notes,
            "device" to snapshot,
            "clock" to c.toMetaMap(),
            "sdk_version" to SDK_VERSION,
            "app_version" to appVersion,
            "config_version" to configVersion,
            "mode" to config!!.mode.name.lowercase(),
            "started_at_wall_ms" to c.t0WallMs,
            "dropped_chunks" to 0,
        )
    }

    /** M3'te `/v1/config`'ten gelecek; şimdilik gömülü varsayılanlar (§3.4). */
    private fun defaultSensorSettings(): Map<String, SensorSetting> =
        SensorSpecs.ALL.associate { it.stream to SensorSetting(true, it.defaultHz) }

    private fun lifecycle(event: String, extra: Map<String, Any?> = emptyMap()) {
        val a = active ?: return
        val t = a.clock.now()
        a.add(EventLine(t, "lifecycle", JsonWriter.obj {
            field("k", "lifecycle"); field("t", t); field("e", event)
            for ((k, v) in extra) { name(k).any(v) }
        }))
    }

    private fun onForegroundChanged(fg: Boolean) {
        if (foreground == fg) return
        foreground = fg
        val a = active ?: return
        if (fg) {
            a.recorder.cancel(backgroundTimeout)
            a.recorder.resume()
            lifecycle("foreground")
        } else {
            lifecycle("background")
            a.recorder.pause()
            a.recorder.postDelayed(BACKGROUND_TIMEOUT_MS, backgroundTimeout)
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard("onCreated") {
            touch.install(activity)
        }
        override fun onActivityStarted(activity: Activity) = guard("onStarted") {
            startedActivities++
            if (startedActivities == 1) onForegroundChanged(true)
        }
        override fun onActivityResumed(activity: Activity) = guard("onResumed") {
            currentScreen = activity.javaClass.simpleName
            // Uygulama kendi sarmalayıcısını bizimkinin üstüne koyduysa yeniden sar (çift kayıt TouchCollector'da engellenir).
            touch.install(activity)
        }
        override fun onActivityStopped(activity: Activity) = guard("onStopped") {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            // Yapılandırma değişiminde Activity yeniden yaratılırken arka plana geçmiş sayılmaz.
            if (startedActivities == 0 && !activity.isChangingConfigurations) onForegroundChanged(false)
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = guard("onConfigurationChanged") {
            if (newConfig.orientation != lastOrientation) {
                lastOrientation = newConfig.orientation
                val o = when (newConfig.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                    Configuration.ORIENTATION_PORTRAIT -> "portrait"
                    else -> "undefined"
                }
                lifecycle("orientation_change", mapOf("o" to o))
            } else {
                lifecycle("config_change")
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = guard("inputDevice") { touch.onInputDeviceChanged(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) = guard("inputDevice") { touch.onInputDeviceChanged(deviceId) }
        override fun onInputDeviceChanged(deviceId: Int) = guard("inputDevice") { touch.onInputDeviceChanged(deviceId) }
    }

    private fun registerScreenReceiver(ctx: Context) {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = guard("screenReceiver") {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> lifecycle("screen_off")
                    Intent.ACTION_SCREEN_ON -> lifecycle("screen_on")
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = r
    }

    private fun unregisterScreenReceiver() {
        val r = screenReceiver ?: return
        try { app?.unregisterReceiver(r) } catch (_: Exception) {}
        screenReceiver = null
    }

    /** `integrity_change` (§3.7): adb, geliştirici seçenekleri ya da erişilebilirlik servisleri değişince. */
    private fun registerIntegrityObservers(ctx: Context) {
        val uris = listOf<Uri>(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
            Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        )
        for (u in uris) {
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = guard("integrity") { checkIntegrity() }
            }
            try { ctx.contentResolver.registerContentObserver(u, false, o); integrityObservers.add(o) } catch (e: Exception) { reportError("integrity observer", e) }
        }
    }

    private fun unregisterIntegrityObservers() {
        for (o in integrityObservers) try { app?.contentResolver?.unregisterContentObserver(o) } catch (_: Exception) {}
        integrityObservers.clear()
    }

    private fun checkIntegrity() {
        val a = active ?: return
        val now = linkedMapOf<String, Any?>(
            "adb_enabled" to device.adbEnabled(),
            "dev_options_enabled" to device.devOptionsEnabled(),
            "accessibility_services" to device.accessibilityServices(),
        )
        for ((k, v) in now) {
            val old = lastIntegrity[k]
            if (old != v) {
                val t = a.clock.now()
                a.add(EventLine(t, "integrity_change", JsonWriter.obj {
                    field("k", "integrity_change"); field("t", t); field("field", k)
                    name("old").any(old); name("new").any(v)
                }))
            }
        }
        lastIntegrity = lastIntegrity + now
    }

    private fun isProcessForeground(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (_: Exception) { true }

    private fun refreshStoreStatus() {
        val s = store ?: return
        try {
            _status.update { it.copy(pendingChunks = s.pendingCount(), localStorageBytes = s.pendingBytes()) }
        } catch (e: Exception) { reportError("store status", e) }
    }

    private fun reportError(where: String, e: Throwable) {
        val msg = "$where: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        _status.update { it.copy(lastError = msg) }
        if (config?.debugLogging == true) Log.w(TAG, msg, e)
    }

    private fun log(msg: String) { if (config?.debugLogging == true) Log.d(TAG, msg) }

    /** SDK host uygulamayı asla çökertmez (§3.12). Kullanım hataları (init yok, rıza yok, oturum yok) hariç. */
    private inline fun guard(where: String, block: () -> Unit) {
        try { block() } catch (e: IllegalStateException) {
            if (where in RETHROW) throw e
            reportError(where, e)
        } catch (e: Throwable) { reportError(where, e) }
    }

    private val RETHROW = setOf("setConsent", "deleteLocalData", "startClockTest")
}
