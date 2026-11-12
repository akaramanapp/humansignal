package io.humansignal.sdk

/**
 * SDK yapılandırması (§3.2).
 */
data class HumanSignalConfig(
    val baseUrl: String,                    // ör. "https://api.example.com"
    val apiKey: String,                     // uygulama anahtarı (role = app)
    val mode: Mode = Mode.RESEARCH,         // Faz 1: yalnızca RESEARCH
    val wifiOnlyUpload: Boolean = false,
    val maxLocalStorageMb: Int = 200,
    val recordKeyIdentity: Boolean = false, // §3.6: SADECE sahte PIN ile araştırmada true
    val debugLogging: Boolean = false,
) {
    override fun toString(): String =
        // API anahtarı asla loglanmaz (§3.12).
        "HumanSignalConfig(baseUrl=$baseUrl, apiKey=***, mode=$mode, wifiOnlyUpload=$wifiOnlyUpload, " +
            "maxLocalStorageMb=$maxLocalStorageMb, recordKeyIdentity=$recordKeyIdentity, debugLogging=$debugLogging)"
}

enum class Mode { RESEARCH, PRODUCTION }

data class SessionMeta(
    val participantCode: String?,           // bot oturumlarında null olabilir
    val label: Label,
    val context: UsageContext,
    val notes: String? = null,
    /** Yalnızca katılımcının ilk oturumunda doldurulur (§4.2, §6.4); sonrakilerde `code` yeterli. */
    val participantInfo: ParticipantInfo? = null,
)

data class ParticipantInfo(
    val handedness: Handedness? = null,
    val ageRange: AgeRange? = null,
    val gender: String? = null,             // opsiyonel; "belirtmek istemiyorum" için null
)

data class Label(
    val kind: Kind,                          // HUMAN | BOT
    val botTool: BotTool? = null,            // kind = BOT ise zorunlu
    val botHost: BotHost? = null,
) {
    enum class Kind(override val wire: String) : WireEnum { HUMAN("human"), BOT("bot") }
}

data class UsageContext(
    val placement: Placement,
    val posture: Posture,
    val hands: Hands,
)

data class SessionInfo(val sessionId: String, val configVersion: Int)

/** Sözlük değerleri §5 ile birebir aynıdır. */
interface WireEnum { val wire: String }

enum class BotTool(override val wire: String) : WireEnum {
    ADB_INPUT("adb_input"), APPIUM("appium"), UIAUTOMATOR("uiautomator"), ESPRESSO("espresso"),
    MAESTRO("maestro"), AUTOCLICKER_APP("autoclicker_app"), ACCESSIBILITY_APP("accessibility_app"),
    SCRCPY_REMOTE("scrcpy_remote"), REPLAY("replay"), OTHER("other"),
}

enum class BotHost(override val wire: String) : WireEnum {
    EMULATOR_ANDROID_STUDIO("emulator_android_studio"), EMULATOR_GENYMOTION("emulator_genymotion"),
    EMULATOR_BLUESTACKS("emulator_bluestacks"), EMULATOR_OTHER("emulator_other"), REAL_DEVICE("real_device"),
}

enum class Placement(override val wire: String) : WireEnum {
    HAND("hand"), TABLE("table"), HOLDER("holder"), LAP("lap"), OTHER("other"),
}

enum class Posture(override val wire: String) : WireEnum {
    SITTING("sitting"), STANDING("standing"), WALKING("walking"), LYING("lying"), NA("na"),
}

enum class Hands(override val wire: String) : WireEnum {
    ONE_LEFT("one_left"), ONE_RIGHT("one_right"), TWO("two"), NA("na"),
}

enum class Handedness(override val wire: String) : WireEnum {
    LEFT("left"), RIGHT("right"), BOTH("both"), UNKNOWN("unknown"),
}

enum class AgeRange(override val wire: String) : WireEnum {
    A18_24("18-24"), A25_34("25-34"), A35_44("35-44"), A45_54("45-54"), A55_64("55-64"), A65_PLUS("65+"),
    UNKNOWN("unknown"),
}

enum class StopReason(override val wire: String) : WireEnum {
    USER("user"), TIMEOUT("timeout"), CONSENT_REVOKED("consent_revoked"), ERROR("error"),
}

/** Tek bir sensör akışının canlı durumu. */
data class SensorStatus(
    val stream: String,          // ör. "acc"
    val targetHz: Int?,          // null = değişimde (on-change)
    val actualHz: Double,        // son 1 saniyede gerçekleşen hız
    val lastValues: List<Float>, // son ölçüm
    val clockBasis: String?,     // "elapsed" | "uptime" | "unknown" | null (henüz olay yok)
)

/** Canlı durum (§3.2 `status`). */
data class CollectorStatus(
    val initialized: Boolean = false,
    val consentGiven: Boolean = false,
    val sessionActive: Boolean = false,
    val paused: Boolean = false,
    val localSessionId: String? = null,
    val sensors: List<SensorStatus> = emptyList(),
    val sensorsMissing: List<String> = emptyList(),
    val touchEvents: Long = 0,            // oturumdaki toplam touch satırı (historical dahil)
    val touchEventsPerSec: Double = 0.0,  // son 1 saniyede
    val chunksWritten: Int = 0,
    val pendingChunks: Int = 0,
    val droppedChunks: Int = 0,
    val localStorageBytes: Long = 0,
    val serverSessionId: String? = null,  // açık oturum sunucuya kaydolduysa
    val uploadError: String? = null,      // ör. 401/403; null = sorun yok
    val uploadRetryInSec: Long? = null,   // ağ/sunucu hatası sonrası bir sonraki deneme
    val lastError: String? = null,
)

/** [SecureKeypadView] üzerindeki bir tuş basışı. Uygulamaya bildirilir; SDK'nın kaydı `kid`'i ayrıca maskeler. */
data class KeyPress(val keyClass: KeyClass, val digit: Int?)

enum class KeyClass(override val wire: String) : WireEnum { DIGIT("digit"), DELETE("delete"), SUBMIT("submit") }

/** Saat Testi durumu (§4.2 madde 7). Kabul: medyan |fark| < 20 ms. */
data class ClockTestState(
    val targetTaps: Int,
    val tapsAnalyzed: Int,
    val tapsWithoutSpike: Int,
    val diffsMs: List<Double>,      // t_ivme_sıçraması − t_touch_DOWN (ms), işaretli
    val medianAbsMs: Double?,
    val passed: Boolean?,           // null = henüz bitmedi
)

/** Son kapanan oturumun özeti (Özet ekranı için, §4.2 madde 5). */
data class SessionSummary(
    val localSessionId: String,
    val serverSessionId: String?,
    val stopReason: StopReason,
    val durationS: Double,
    val totalChunks: Int,
    val sensorHz: Map<String, Double>,   // oturum ortalaması (ön planda geçen süreye göre)
    val sensorsMissing: List<String>,
    val touchEvents: Long,
)
