# HumanSignal: Android SDK + Veri Toplama API'si (Teknik Şartname)

> Sürüm: 1.0 · Tarih: 2026-11-12 · Durum: Geliştirmeye hazır
>
> "HumanSignal" geçici bir proje adıdır. Paket adları (`io.humansignal.*`) istenirse değiştirilebilir.

---

## 0. Özet

Bu şartname üç parçayı tanımlar:

1. **Android SDK** (`humansignal-sdk`, Kotlin kütüphanesi / `.aar`): Bir mobil uygulamanın içine eklenir. Hareket sensörlerini, dokunmaları, klavye zamanlamalarını ve cihaz bütünlüğü sinyallerini **yüksek frekanslı ve zaman damgalı** toplar; veriyi şifreli olarak telefonda tamponlar ve API'ye yükler.
2. **Araştırma uygulaması** (`research-app`): SDK'yı kullanan basit bir Android uygulaması. Gönüllülerden (insan) ve otomasyon araçlarından (bot) **etiketli** veri toplamak için görevler içerir: dokunma, kaydırma, PIN girme, sahte para transferi.
3. **Node.js API** (`api`) + **Supabase**: Oturumları ve veri parçalarını (chunk) kabul eder. Meta veriyi Postgres'e, ham veriyi Supabase Storage'a yazar.

**Bu sürümün (Faz 1) amacı:** İnsan / bot sınıflandırma modelini eğitmek için kaliteli, etiketli ham veri toplamak.

**Faz 1'in kapsamı dışında kalanlar** (Faz 2'de yapılacak, bkz. §11):
- Cihaz üzerinde özellik çıkarma
- `/v1/score` ile gerçek zamanlı risk skoru
- Play Integrity doğrulaması
- iOS SDK

### 0.1 BrainRun deneyinden çıkan dersler

Bu şartnamedeki birçok karar, BrainRun veri setiyle yapılan önceki deneyin sonuçlarına dayanıyor:

| BrainRun'daki sorun | Bu şartnamedeki çözüm |
|---|---|
| Sensörler ~3 Hz'de örneklenmiş | Hareket sensörleri **100 Hz** (§3.4) |
| Sensör ölçümlerinde zaman damgası yok | Her ölçüm **nanosaniye** zaman damgalı (§3.3) |
| Dokunma ve sensör farklı saatlerde | Tek bir **ortak zaman tabanı** (§3.3) |
| Dokunmada basınç ve alan yok; tek nokta | Tüm ara noktalar, basınç, alan, `toolType` (§3.5) |
| Emülatör kullanıcıları "insan" diye etiketlenmiş | Cihaz bütünlüğü sinyalleri + açık etiketleme (§3.7, §5) |
| Tek kaynak (dokunma ya da sensör) yetersiz | İkisi birlikte ve senkronize toplanır |

---

## 1. Mimari

```
┌──────────────────────── Android cihaz ────────────────────────┐
│  research-app (ya da ileride bankacılık uygulaması)           │
│    └── humansignal-sdk                                        │
│          ├── SensorCollector   (HandlerThread, 100 Hz)        │
│          ├── TouchCollector    (Window.Callback sarmalayıcı)  │
│          ├── KeyCollector      (SecureKeypadView, TextWatcher)│
│          ├── DeviceCollector   (cihaz, bütünlük, batarya)     │
│          ├── EventBus → ChunkWriter (5 sn, gzip NDJSON)       │
│          ├── LocalStore (noBackupFilesDir, AES-GCM/Keystore)  │
│          └── Uploader (WorkManager)                           │
└───────────────────────────────┬───────────────────────────────┘
                                │ HTTPS + X-Api-Key
                                ▼
┌──────────────────────── Sunucu ───────────────────────────────┐
│  api (Node.js 20+, TypeScript, Fastify)                       │
│    ├── Postgres (Supabase): participants, devices, sessions,  │
│    │                        chunks, api_keys                  │
│    └── Storage (Supabase, private bucket "raw-chunks")        │
│          sessions/{session_id}/{seq:000000}.ndjson.gz         │
└───────────────────────────────┬───────────────────────────────┘
                                │ (toplu indirme)
                                ▼
          Eğitim makinesi (GX10): Python dönüştürücü → parquet → model
```

### 1.1 Depo yapısı (monorepo)

```
humansignal/
├── SPEC.md
├── android/
│   ├── settings.gradle.kts
│   ├── sdk/                 # com.android.library  → humansignal-sdk.aar
│   └── research-app/        # com.android.application
├── api/                     # Node.js + TypeScript
│   ├── src/
│   ├── test/
│   ├── package.json
│   └── .env.example
├── supabase/
│   └── migrations/0001_init.sql
└── tools/
    ├── bots/                # adb / Appium bot script'leri (§9)
    └── ingest/              # (GX10 tarafı) Supabase → parquet dönüştürücü
```

---

## 2. Terimler

| Terim | Anlamı |
|---|---|
| **Oturum (session)** | Bir katılımcının (ya da bir botun) tek seferde yaptığı, baştan sona kaydedilen kullanım. 5–15 dakika. |
| **Parça (chunk)** | Bir oturumun ~5 saniyelik veri dosyası. Sıra numarası (`seq`) 0'dan başlar, boşluksuz artar. |
| **Akış (stream)** | Parça içindeki veri türü: `sensor`, `touch`, `key`, `text`, `marker`, `lifecycle`, `battery`. |
| **Katılımcı kodu** | `P001` gibi takma ad. Gerçek isim sistemde **hiçbir yerde** tutulmaz. |
| **install_id** | SDK'nın ilk açılışta ürettiği rastgele UUID. Cihazı tanımlar; donanım kimliği (IMEI, Android ID) **kullanılmaz**. |
| **Monotonik zaman** | `SystemClock.elapsedRealtimeNanos()` tabanlı, oturum başına göre ns cinsinden süre. Tüm olaylar bu zamanla kaydedilir. |

---

## 3. Android SDK

### 3.1 Teknik temel

| | Değer |
|---|---|
| Dil | Kotlin 2.x, Coroutines |
| minSdk | 26 (Android 8.0): kalibre edilmemiş ivmeölçer bu sürümde geldi |
| targetSdk / compileSdk | 35 |
| Bağımlılıklar | `androidx.core`, `androidx.work:work-runtime-ktx`, `kotlinx-coroutines-android`, OkHttp 4. **Başka bağımlılık eklenmeyecek** (SDK hafif kalmalı). |
| Çıktı | `humansignal-sdk-<version>.aar` (Faz 1'de `mavenLocal` ya da dosya olarak dağıtılır) |
| Proguard | `consumer-rules.pro` ile public API korunur |
| İzinler (manifest) | `INTERNET`, `ACCESS_NETWORK_STATE`. **Başka izin istenmez.** |

> 100 Hz için `HIGH_SAMPLING_RATE_SENSORS` izni gerekmez. Bu izin yalnızca 200 Hz üzeri için gereklidir.

### 3.2 Public API

```kotlin
package io.humansignal.sdk

object HumanSignal {
    /** Application.onCreate içinde bir kez çağrılır. */
    fun init(app: Application, config: HumanSignalConfig)

    /** Rıza alınmadan hiçbir veri toplanmaz. Rıza geri alınırsa açık oturum durdurulur. */
    fun setConsent(given: Boolean, consentVersion: String)

    /** Sunucuda oturum açar, toplayıcıları başlatır. Rıza yoksa IllegalStateException. */
    suspend fun startSession(meta: SessionMeta): SessionInfo

    /** Toplayıcıları durdurur, son parçayı yazar, yükleme kuyruğuna 'complete' ekler. */
    suspend fun stopSession(reason: StopReason = StopReason.USER)

    /** Zaman çizelgesine işaret koyar: görev başlangıcı/bitişi, ekran değişimi vb. */
    fun mark(name: String, attrs: Map<String, String> = emptyMap())

    /** Sistem klavyesi kullanılan bir alanı izler (sadece zamanlama ve uzunluk, içerik ASLA). */
    fun trackTextField(view: EditText, fieldId: String)
    fun untrackTextField(view: EditText)

    /** Canlı durum: toplanan Hz değerleri, kuyruktaki parça sayısı, hata. */
    val status: StateFlow<CollectorStatus>

    /** Telefondaki tüm yüklenmemiş veriyi siler (rıza geri alma / test). */
    fun deleteLocalData()
}

data class HumanSignalConfig(
    val baseUrl: String,                    // ör. "https://api.example.com"
    val apiKey: String,                     // uygulama anahtarı (role = app)
    val mode: Mode = Mode.RESEARCH,         // Faz 1: yalnızca RESEARCH
    val wifiOnlyUpload: Boolean = false,
    val maxLocalStorageMb: Int = 200,
    val recordKeyIdentity: Boolean = false, // §3.6: SADECE sahte PIN ile araştırmada true
    val debugLogging: Boolean = false,
)

enum class Mode { RESEARCH, PRODUCTION }

data class SessionMeta(
    val participantCode: String?,           // bot oturumlarında null olabilir
    val label: Label,
    val context: UsageContext,
    val notes: String? = null,
)

data class Label(
    val kind: Kind,                          // HUMAN | BOT
    val botTool: BotTool? = null,            // kind = BOT ise zorunlu
    val botHost: BotHost? = null,            // EMULATOR | REAL_DEVICE
) { enum class Kind { HUMAN, BOT } }

data class UsageContext(
    val placement: Placement,                // HAND | TABLE | HOLDER | LAP | OTHER
    val posture: Posture,                    // SITTING | STANDING | WALKING | LYING | NA
    val hands: Hands,                        // ONE_LEFT | ONE_RIGHT | TWO | NA
)

data class SessionInfo(val sessionId: String, val configVersion: Int)
```

Enum değerlerinin tam listesi §5'te.

**Kurallar:**
- Bütün public metotlar ana thread'den çağrılabilir; ağır iş arka planda yapılır.
- Aynı anda yalnızca **bir** açık oturum olabilir. İkinci `startSession` çağrısı hata verir.
- Uygulama arka plana geçince toplama **duraklatılır**, `lifecycle` olayı yazılır. 10 dakikadan uzun arka planda kalırsa oturum `StopReason.TIMEOUT` ile kapatılır.
- `startSession` ağ hatası alırsa oturum **yerelde** açılır: geçici bir `local_session_id` (UUID) kullanılır, sunucu kaydı bağlantı gelince yapılır. Toplama ağ yüzünden asla engellenmez.

### 3.3 Zaman tabanı (kritik)

Android'de farklı olay kaynakları farklı saatler kullanır:

| Kaynak | Saat |
|---|---|
| `SensorEvent.timestamp` | ns; pratikte `elapsedRealtimeNanos` tabanı (bazı eski cihazlarda farklı olabilir) |
| `MotionEvent.getEventTime()` | ms, `SystemClock.uptimeMillis()` tabanı |
| `MotionEvent.getEventTimeNanos()` | ns, uptime tabanı (API 34+) |
| `KeyEvent` ve dokunma | uptime tabanı |

**Kural:** Tüm kayıtlar **oturum başına göre ns cinsinden monotonik zaman** (`t`) olarak yazılır:

```
t = elapsedRealtimeNanos_tabanli_olay_zamani − session_t0_elapsed_ns
```

**Dönüşüm:**
1. Oturum başında ve sonra **her 1 saniyede** bir şu ölçülür: `offset_ns = SystemClock.elapsedRealtimeNanos() − SystemClock.uptimeMillis() × 1_000_000`. İkisi art arda okunur; okuma süresinin yarısı düzeltme olarak eklenir. Uptime ve elapsed saatleri cihaz uykuya girince birbirinden ayrışır; bu yüzden ölçüm tekrarlanır.
2. Dokunma olayları için `elapsed_ns = event_uptime_ns + offset_ns`. API 34+ cihazlarda `getEventTimeNanos()`, öncekilerde `getEventTime() × 1e6` kullanılır.
3. **Sensör saati doğrulaması:** Her sensörün ilk olayında `SensorEvent.timestamp` ile `elapsedRealtimeNanos()` karşılaştırılır. Fark 1 saniyeden büyükse o sensör için `clock_basis = "uptime"` ya da `"unknown"` işaretlenir, ölçülen fark `sensor_clock_offset_ns` olarak oturum meta verisine yazılır.
4. Oturum meta verisine `t0_elapsed_ns`, `t0_uptime_ns` ve `t0_wall_ms` (`System.currentTimeMillis()`) yazılır. Duvar saati yalnızca bilgi amaçlıdır; sıralamada kullanılmaz.

**Kabul kriteri:** Telefonun ekranına parmakla sertçe vurulduğunda, ivmeölçerdeki sıçrama ile `touch DOWN` olayı arasındaki fark **±20 ms** içinde olmalı. Bu, araştırma uygulamasındaki "Saat Testi" ekranıyla doğrulanır (§4.4).

### 3.4 SensorCollector

Dinleyiciler **ayrı bir `HandlerThread`** üzerinde kaydedilir (`registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs = 0, handler)`). `SensorEvent` nesnesi Android tarafından yeniden kullanıldığı için değerler **hemen** kopyalanır.

| Akış adı | Android tipi | Hedef hız | Değerler |
|---|---|---|---|
| `acc` | `TYPE_ACCELEROMETER` | 100 Hz | x, y, z (m/s²) |
| `acc_u` | `TYPE_ACCELEROMETER_UNCALIBRATED` | 100 Hz | x, y, z, bias_x, bias_y, bias_z |
| `gyr` | `TYPE_GYROSCOPE` | 100 Hz | x, y, z (rad/s) |
| `gyr_u` | `TYPE_GYROSCOPE_UNCALIBRATED` | 100 Hz | x, y, z, drift_x, drift_y, drift_z |
| `mag` | `TYPE_MAGNETIC_FIELD` | 50 Hz | x, y, z (µT) |
| `mag_u` | `TYPE_MAGNETIC_FIELD_UNCALIBRATED` | 50 Hz | x, y, z, bias_x, bias_y, bias_z |
| `grav` | `TYPE_GRAVITY` | 50 Hz | x, y, z |
| `lin` | `TYPE_LINEAR_ACCELERATION` | 100 Hz | x, y, z |
| `rot` | `TYPE_ROTATION_VECTOR` | 50 Hz | x, y, z, w, [heading_accuracy] |
| `grot` | `TYPE_GAME_ROTATION_VECTOR` | 50 Hz | x, y, z, w |
| `light` | `TYPE_LIGHT` | değişimde | lux |
| `prox` | `TYPE_PROXIMITY` | değişimde | cm |
| `press` | `TYPE_PRESSURE` | 10 Hz | hPa |

- Hedef hızlar `/v1/config`'ten gelir; yukarıdakiler varsayılanlardır.
- Cihazda olmayan sensör atlanır, oturum meta verisinde `sensors_missing` listesine yazılır.
- `onAccuracyChanged` olayları da kaydedilir: `{"k":"sensor_acc","s":"mag","t":...,"a":2}`.
- **Gerçekleşen hız** her sensör için saniyede bir hesaplanır ve `status` ile yayınlanır. Araştırma uygulaması bunu ekranda gösterir.
- Yalnızca **oturum açıkken ve uygulama ön plandayken** dinleyiciler kayıtlıdır (pil).

### 3.5 TouchCollector

**Yakalama yöntemi:** `ActivityLifecycleCallbacks` ile her Activity'nin `window.callback`'i bir sarmalayıcıyla değiştirilir. Sarmalayıcı `dispatchTouchEvent`'i **gözlemler, olayı tüketmez** ve orijinal callback'e iletir. Mevcut bir sarmalayıcı varsa zincir korunur. Bu yöntem View sistemi ve Jetpack Compose için aynı şekilde çalışır.

> **Bilinen sınırlama:** Dialog, PopupWindow ve BottomSheetDialog ayrı pencerelerdir. Faz 1'de bunlar yakalanmaz. Araştırma uygulaması görevleri bu yüzden tam ekran Activity/Fragment içinde olacak. Faz 2'de `HumanSignal.attach(window)` eklenecek.

**Her MotionEvent için** önce **geçmiş (historical) örnekler**, sonra güncel örnek kaydedilir:

```json
{"k":"touch","t":123456789,"h":0,"a":"MOVE","pc":1,
 "p":[{"id":0,"x":512.3,"y":1204.8,"pr":0.43,"sz":0.12,"ma":38.1,"mi":31.0,"o":-0.21,"tool":"finger"}],
 "src":"touchscreen","dev":3,"fl":0,"scr":"PinEntryActivity"}
```

| Alan | Kaynak | Not |
|---|---|---|
| `t` | §3.3'e göre ns | historical örnekte `getHistoricalEventTime(Nanos)` |
| `h` | 1 = historical, 0 = güncel | |
| `a` | `actionMasked` | DOWN, MOVE, UP, CANCEL, POINTER_DOWN, POINTER_UP, HOVER_* |
| `pc` | `pointerCount` | |
| `p[].id` | `getPointerId` | |
| `x`, `y` | `getRawX/Y(i)` (API 29+), öncekilerde `getX/Y` + pencere offset'i | **Ekran pikseli** |
| `pr` | `getPressure` | |
| `sz` | `getSize` | |
| `ma`, `mi` | `getTouchMajor/Minor` | px |
| `o` | `getOrientation` | rad |
| `tool` | `getToolType` | finger, stylus, mouse, eraser, unknown. **Fare = güçlü emülatör sinyali.** |
| `src` | `getSource` | touchscreen, mouse, stylus, keyboard, unknown (bit maskesinden çözülür) |
| `dev` | `getDeviceId` | Gerçek dokunmatik ekran genellikle pozitif bir ID'dir; enjekte edilen olaylar farklı ID'lerle gelebilir |
| `fl` | `getFlags` | Bit 0: `FLAG_WINDOW_IS_OBSCURED`, bit 1: `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29+). **Overlay saldırısı sinyali.** |
| `scr` | Aktif Activity'nin sınıf adı | Son `mark("screen", ...)` ile ezilebilir |

Ayrıca oturum başında bir kez ve değiştikçe `InputDevice` bilgisi yazılır: `{"k":"input_device","id":3,"name":"...","vendor":...,"product":...,"sources":"...","virtual":false}`. `InputDevice.isVirtual()` true olan cihazlardan gelen dokunmalar önemli bir sinyaldir.

### 3.6 KeyCollector

#### 3.6.1 SecureKeypadView (PIN / şifre)

SDK, PIN ve şifre girişi için kendi sayısal klavye View'ını sağlar:

```kotlin
class SecureKeypadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var fieldId: String                       // ör. "pin"
    var listener: ((KeyPress) -> Unit)?       // uygulamaya basılan rakamı bildirir (uygulamanın PIN'e ihtiyacı var)
    var shuffleKeys: Boolean = false          // rakam yerlerini karıştırma (opsiyonel)
}
```

- Tuşlar 3×4 ızgara: 1–9, ⌫, 0, ✓.
- Her tuş basışı için SDK şunu kaydeder:

```json
{"k":"key","f":"pin","td":123000000,"tu":123085000,"dx":-4.2,"dy":6.1,"pr":0.38,"sz":0.11,
 "ma":35.0,"kc":"digit","kid":null}
```

| Alan | Anlamı |
|---|---|
| `td`, `tu` | Tuşa basma ve bırakma zamanı (ns, §3.3) |
| `dx`, `dy` | Dokunma noktasının tuş merkezine uzaklığı (px) |
| `pr`, `sz`, `ma` | Basınç, boyut, touchMajor (DOWN anındaki) |
| `kc` | Tuş sınıfı: `digit`, `delete`, `submit` |
| `kid` | **Hangi rakam olduğu.** Varsayılan olarak **her zaman `null`**. |

> ⚠️ **Gizlilik kuralı:** Tuşun kimliği (hangi rakam) ve tuşun ekrandaki satır/sütunu PIN'i ele verir. Bu yüzden `kid` yalnızca `recordKeyIdentity = true` iken doldurulur. Bu bayrak **sadece** araştırma uygulamasında, katılımcıya **sahte PIN** yazdırılırken açılır. `dx`/`dy` tuşa göre görece olduğu için PIN'i ele vermez. Mutlak x/y bu akışta kaydedilmez; ama aynı dokunma `touch` akışında mutlak koordinatlarla zaten var. Bu yüzden **`SecureKeypadView` üzerindeki dokunmalar `touch` akışında maskelenir**: `x`, `y` yerine `"masked":true` yazılır. Diğer tüm alanlar korunur.

#### 3.6.2 Sistem klavyesi alanları (`trackTextField`)

Sistem klavyesinden tuş olayları alınamaz. Yalnızca metin değişimi izlenir:

```json
{"k":"text","f":"amount","t":456000000,"d":1,"len":4,"paste":false,"ime":true}
```

| Alan | Anlamı |
|---|---|
| `d` | Uzunluk değişimi (+1 yazma, −1 silme, +N toplu ekleme) |
| `len` | Yeni toplam uzunluk |
| `paste` | `d > 1` ve tek bir değişimde gerçekleştiyse `true` (yapıştırma ya da enjeksiyon) |
| `ime` | Değişim bir IME oturumu içinde mi gerçekleşti |

> **Metnin içeriği ASLA kaydedilmez.** `TextWatcher` içinde yalnızca `count`, `before` ve `s.length` kullanılır.

Ek olarak odak olayları da kaydedilir: `{"k":"focus","f":"amount","t":...,"on":true}`.

### 3.7 DeviceCollector

Oturum başında bir **cihaz anlık görüntüsü** alınır ve `POST /v1/sessions` gövdesinde gönderilir (§6.3):

| Grup | Alanlar |
|---|---|
| Cihaz | `manufacturer`, `brand`, `model`, `device`, `product`, `hardware`, `board`, `fingerprint`, `android_sdk`, `android_release`, `security_patch` |
| Ekran | `width_px`, `height_px`, `density`, `xdpi`, `ydpi`, `refresh_rate` |
| Sensör listesi | Her sensör için `type`, `name`, `vendor`, `version`, `resolution`, `max_range`, `min_delay_us`, `max_delay_us`, `power_ma`, `fifo_max`. **Emülatörlerde vendor/name çoğu zaman belirgindir.** |
| Bütünlük | `adb_enabled` (`Settings.Global.ADB_ENABLED`), `dev_options_enabled` (`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`), `accessibility_services` (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`; RESEARCH modunda bileşen adları, PRODUCTION modunda SHA-256 özetleri + sayı), `is_user_a_monkey` (`ActivityManager.isUserAMonkey()`), `running_in_test_harness` (`ActivityManager.isRunningInUserTestHarness()`, API 29+), `emulator_hints` (aşağıda), `displays` (`DisplayManager` ile ekran sayısı; yansıtma göstergesi) |
| Uygulama | `app_package`, `app_version`, `sdk_version`, `installer_package` |

**`emulator_hints`:** Sadece bilgi toplanır, karar verilmez. Şu kontrollerin sonuçları bir liste olarak yazılır:
- `Build.FINGERPRINT`, `MODEL`, `PRODUCT`, `HARDWARE` içinde `generic`, `emulator`, `sdk_gphone`, `goldfish`, `ranchu`, `vbox`, `genymotion`, `nox`, `bluestacks` geçiyor mu?
- Sensör `vendor`/`name` içinde `Goldfish`, `AOSP`, `Genymotion` geçiyor mu?

**Oturum boyunca periyodik kayıtlar:**
- `battery` (30 saniyede bir, `ACTION_BATTERY_CHANGED` sticky intent'inden): `{"k":"battery","t":...,"lvl":0.83,"plug":"ac|usb|wireless|none","temp_c":31.2,"volt_mv":4120,"status":"charging"}`
- `lifecycle`: `foreground`, `background`, `screen_off`, `screen_on`, `orientation_change`, `config_change`
- `integrity_change`: Oturum sırasında `adb_enabled` ya da erişilebilirlik servisleri değişirse yazılır

> **Toplanmayanlar:** IMEI, Android ID, reklam kimliği, telefon numarası, konum, Wi-Fi/Bluetooth taramaları, mikrofon, kamera, yüklü uygulama listesi, rehber, metin içerikleri.

### 3.8 Parça (chunk) formatı

**Dosya:** gzip ile sıkıştırılmış **NDJSON** (her satır bir JSON nesnesi). Seçilme nedenleri: bağımlılık gerektirmiyor, hata ayıklaması kolay, Python/pandas ile kolay okunuyor. Gzip ile boyut makul (dakikada ~0,5–1 MB).

**Kurallar:**
- Her parça ~**5 saniyelik** veri ya da en fazla **2 MB** (sıkıştırılmamış) içerir; hangisi önce dolarsa.
- İlk satır her zaman `header`:

```json
{"k":"header","v":1,"session_id":"…","local_session_id":"…","seq":17,"t_start":85000000000,"t_end":90000000000,"sdk":"1.0.0"}
```

- **Sensörler sütun düzeninde** yazılır. Parça başına, her sensör için bir satır:

```json
{"k":"sensor","s":"acc","t":[85000123000,85010119000,…],"v":[[0.12,9.71,0.33],[0.13,9.70,0.31],…]}
```

`t` dizisi ns cinsinden tam sayılardır. JSON'da 2^53'ü aşmaz: oturum başına göre görece olduğu için 104 günü aşmadıkça güvenli.

- Diğer akışlar olay başına bir satırdır (`touch`, `key`, `text`, `focus`, `marker`, `lifecycle`, `battery`, `sensor_acc`, `input_device`, `integrity_change`).
- Satırlar parça içinde `t`'ye göre sıralı olmak **zorunda değil**. Okuyucu sıralar.
- Sayılar: koordinatlar 1 ondalık, sensör değerleri 5 anlamlı basamak. Gereksiz hassasiyet boyutu şişirir.

**Marker olayları** etiketleme için çok önemlidir. Araştırma uygulaması her görev için şunları yazar:

```json
{"k":"marker","t":…,"name":"task_start","attrs":{"task":"pin_entry","trial":"3"}}
{"k":"marker","t":…,"name":"task_end","attrs":{"task":"pin_entry","trial":"3","result":"ok"}}
```

### 3.9 LocalStore: telefonda saklama

| | |
|---|---|
| Klasör | `context.noBackupFilesDir/humansignal/` (yedeklemeye dahil olmaz) |
| Dosya adı | `{local_session_id}/{seq:000000}.ndjson.gz.enc` |
| Şifreleme | AES-256-GCM. Anahtar **Android Keystore**'da üretilir, cihazdan çıkarılamaz (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT|DECRYPT`, `BLOCK_MODE_GCM`). Dosya: `[12 bayt IV][şifreli metin + 16 bayt etiket]`. |
| Kuyruk | Küçük bir SQLite tablosu (`android.database.sqlite` ya da düz JSON dosyası; Room **kullanılmayacak**, bağımlılık azaltmak için): `local_session_id, server_session_id, seq, path, sha256, state(pending/uploaded), attempts` |
| Kota | Toplam boyut `maxLocalStorageMb`'yi aşarsa **en eski yüklenmemiş parçalar** silinir ve `dropped_chunks` sayacı artırılır. Sayaç oturumu tamamlarken sunucuya bildirilir. |
| Silme | Sunucu 200/201 dönünce dosya hemen silinir. |

> Yüklemeden önce dosya çözülür. Gzip baytları **TLS üzerinden** gönderilir. Sunucu Keystore anahtarına erişemez; disk şifrelemesi yalnızca telefondaki veriyi korur.

### 3.10 Uploader

- **WorkManager** `OneTimeWorkRequest` zinciri, benzersiz iş adı `humansignal-upload`, politika `APPEND_OR_REPLACE`.
- Kısıtlar: `NetworkType.CONNECTED` (ya da `wifiOnlyUpload` ise `UNMETERED`).
- Sıra:
  1. Sunucu kaydı yoksa `POST /v1/sessions`, dönen `session_id` kuyruğa işlenir.
  2. Parçalar `seq` sırasıyla `PUT /v1/sessions/{id}/chunks/{seq}`.
  3. Oturum kapandıysa ve tüm parçalar yüklendiyse `POST /v1/sessions/{id}/complete`.
- Yanıtlara göre davranış:

| Yanıt | Davranış |
|---|---|
| 200 / 201 | Parça yüklendi; yerel dosya silinir |
| 409 `CHUNK_CONFLICT` | Aynı seq farklı içerikle zaten var. Dosya `conflict/` klasörüne taşınır, hata loglanır, devam edilir. |
| 400 / 413 / 422 | Kalıcı hata: dosya `failed/` klasörüne taşınır, sayaç artırılır |
| 401 / 403 | Yükleme durdurulur, `status`'ta hata gösterilir |
| 429 / 5xx / ağ hatası | `Result.retry()`; üstel geri çekilme (30 sn başlangıç, en fazla 1 saat) |

- Tek istek için zaman aşımı 30 saniye. Parçalar sırayla yüklenir; paralel yükleme yok.

### 3.11 Performans bütçesi

| Metrik | Hedef (orta seviye cihaz, ör. 2022 orta segment Samsung) |
|---|---|
| CPU | Ortalama < %5 (tek çekirdek) |
| Bellek | < 25 MB ek heap |
| Ana thread | Dokunma gözleminde olay başına < 0,2 ms. Ağır iş ana thread'de yapılmaz. |
| Disk yazma | 5 saniyede bir toplu yazma |
| Pil | 10 dakikalık oturumda < %2 ek tüketim |

Ölçüm Android Studio Profiler ile yapılır; sonuçlar `android/PERF.md` dosyasına yazılır.

### 3.12 Hata yönetimi ve loglama

- SDK hiçbir durumda host uygulamayı **çökertmemeli**. Tüm toplayıcılar `try/catch` ile korunur; hata `status.lastError`'a yazılır ve o toplayıcı devre dışı kalır.
- `debugLogging = true` iken Logcat etiketi `HumanSignal`. Loglarda **asla** metin içeriği, PIN ya da API anahtarı yazılmaz.

### 3.13 Testler

- **Birim testleri:** saat dönüşümü (§3.3), NDJSON yazıcı, sütunlu sensör kodlaması, AES-GCM şifreleme/çözme döngüsü, kota ve silme mantığı, TextWatcher'ın içerik kaydetmediği (test, kaydedilen JSON'da yazılan metnin geçmediğini doğrular).
- **Enstrümantasyon testleri:** `SecureKeypadView` maskelemesi, Window.Callback zincirinin korunması (olayın orijinal hedefine ulaştığı), uçak modunda toplanıp bağlantı gelince yüklenmesi.

---

## 4. Araştırma Uygulaması (`research-app`)

Amaç: gönüllülerden ve botlardan **etiketli, karşılaştırılabilir** oturumlar toplamak. Tasarım sade olmalı; görsel cila gerekmez.

### 4.1 Ekran akışı

```
Ayarlar (ilk açılış) → Rıza → Oturum Kurulumu → Görevler (sırayla) → Özet → (yeni oturum)
                                       ↘ Saat Testi (geliştirici)
                                       ↘ Canlı Sensör (geliştirici)
```

### 4.2 Ekranlar

1. **Ayarlar:** API adresi, API anahtarı, "Sadece Wi-Fi'da yükle", `recordKeyIdentity` (varsayılan açık; uygulama sadece sahte PIN kullandığı için). QR kod ile doldurma isteğe bağlı.
2. **Rıza:** Rıza metni (sürüm numarasıyla). "Kabul ediyorum" → `HumanSignal.setConsent(true, "2026-11-v1")`. Metin, uygulamanın `assets/consent_tr.md` dosyasından gelir.
3. **Oturum Kurulumu:**
   - **Etiket:** İnsan / Bot. Bot seçilirse araç listesi (§5) ve host (emülatör / gerçek cihaz).
   - **Katılımcı kodu:** İnsan için zorunlu (`P` + 3 rakam). Uygulama bir sonraki numarayı önerir.
   - **Katılımcı bilgisi:** yalnızca ilk oturumda; el tercihi (sağ/sol/ikisi), yaş aralığı (18–24, 25–34, 35–44, 45–54, 55–64, 65+), cinsiyet (opsiyonel, "belirtmek istemiyorum" seçeneğiyle).
   - **Ortam:** telefonun konumu (elde, masada, tutucuda, kucakta), duruş (oturuyor, ayakta, yürüyor, uzanıyor), el kullanımı (tek sol, tek sağ, iki el).
4. **Görevler:** §4.3'teki görevler sırayla gösterilir. Üstte ilerleme çubuğu ve görev talimatı yer alır.
5. **Özet:** Oturum süresi, toplanan parça sayısı, gerçekleşen sensör Hz değerleri, yükleme durumu.
6. **Canlı Sensör (geliştirici):** Her sensör için anlık Hz değeri ve son değerler, dokunma olay hızı, kuyruk durumu.
7. **Saat Testi (geliştirici):** "Ekrana 10 kez sertçe vurun." Her vuruş için `touch DOWN` zamanı ile en yakın ivme sıçraması arasındaki fark hesaplanıp gösterilir. Kabul kriteri: medyan |fark| < 20 ms.

### 4.3 Görevler

Her görev `mark("task_start"/"task_end")` ile işaretlenir. Bir oturum yaklaşık **8–10 dakika** sürer.

| # | Görev | Ayrıntı | Tekrar |
|---|---|---|---|
| T1 | **Dokunma hedefleri** | Ekranda rastgele yerlerde 60–120 dp çaplı daireler belirir. Kullanıcı her birine dokunur. Hedef konumları ve boyutu `marker` ile yazılır. | 30 hedef |
| T2 | **Liste kaydırma** | 200 öğelik bir listede belirtilen öğeyi bulup ona dokunmak ("47 numaralı işlemi bulun"). | 5 deneme |
| T3 | **Kart kaydırma** | Kartları sağa/sola kaydırmak; ok yönü ekranda gösterilir. | 20 kart |
| T4 | **PIN girişi** | Ekranda gösterilen **sahte** 4–6 haneli PIN `SecureKeypadView` ile girilir. | 10 deneme (her biri farklı sahte PIN) |
| T5 | **Metin yazma** | Sistem klavyesiyle sahte bir IBAN, tutar ve açıklama yazmak (`trackTextField`). | 3 deneme |
| T6 | **Sahte para transferi** | Alıcı seç → tutar yaz → açıklama → onay ekranı → PIN ile onayla. Gerçek bir bankacılık akışını taklit eder. | 3 deneme |
| T7 | **Serbest kullanım** | 60 saniye boyunca bir haber listesinde serbest gezinme. | 1 |

- Görev sırası sabittir (T1 → T7). Bu, oturumları karşılaştırılabilir kılar.
- Her görev "Atla" düğmesine sahiptir; atlanan görev `result:"skipped"` olarak işaretlenir.
- **Bot erişilebilirliği:** Tüm etkileşimli öğelerin sabit `resource-id`'leri ve `contentDescription`'ları olmalı (ör. `task_t1_target`, `keypad_key_5`). Böylece Appium ve UI Automator script'leri uygulamayı kolayca sürebilir. Hedef konumları ayrıca `marker`'larda bulunduğu için adb script'leri de konum bilgisini alabilir (§9).

### 4.4 Bot modunda kullanım

- Bot etiketi seçilip oturum başlatılır. Ardından bot script'i çalıştırılır. Script bitince oturum uygulamadan ya da adb ile durdurulur: `adb shell am broadcast -a io.humansignal.research.STOP`. Bu broadcast receiver **yalnızca debug build'de** bulunur.
- Bot oturumlarında katılımcı kodu boş kalabilir.

---

## 5. Etiket ve bağlam sözlüğü

Bu değerler SDK enum'larında, API doğrulamasında ve veritabanı `check` kısıtlarında **aynen** kullanılır.

| Alan | Değerler |
|---|---|
| `label_kind` | `human`, `bot` |
| `bot_tool` | `adb_input`, `appium`, `uiautomator`, `espresso`, `maestro`, `autoclicker_app`, `accessibility_app`, `scrcpy_remote`, `replay`, `other` |
| `bot_host` | `emulator_android_studio`, `emulator_genymotion`, `emulator_bluestacks`, `emulator_other`, `real_device` |
| `placement` | `hand`, `table`, `holder`, `lap`, `other` |
| `posture` | `sitting`, `standing`, `walking`, `lying`, `na` |
| `hands` | `one_left`, `one_right`, `two`, `na` |
| `handedness` | `left`, `right`, `both`, `unknown` |
| `age_range` | `18-24`, `25-34`, `35-44`, `45-54`, `55-64`, `65+`, `unknown` |

`scrcpy_remote`: bilgisayardan fareyle telefon kontrolü. Bu bir insan, ama uzaktan kontrol ediyor; ayrı sınıf olarak tutulur.

---

## 6. Node.js API

### 6.1 Teknik temel

| | |
|---|---|
| Çalışma ortamı | Node.js 20 LTS ya da 22 LTS |
| Dil | TypeScript (strict) |
| Framework | Fastify 4/5 |
| Doğrulama | zod |
| Veritabanı ve depolama | `@supabase/supabase-js` v2, **service role** anahtarıyla, yalnızca sunucu tarafında |
| Loglama | pino (Fastify'ın kendi logger'ı). IP adresleri loglanmaz. |
| Rate limit | `@fastify/rate-limit` |
| Testler | vitest + Fastify `inject` |

### 6.2 Ortam değişkenleri (`.env.example`)

```
PORT=8080
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_SERVICE_ROLE_KEY=...        # ASLA mobil uygulamaya konmaz
STORAGE_BUCKET=raw-chunks
MAX_CHUNK_BYTES=5242880              # 5 MB
RATE_LIMIT_PER_MIN=600               # anahtar başına
LOG_LEVEL=info
```

### 6.3 Genel kurallar

- Tüm uçlar `/v1` altındadır (`/health` hariç).
- **Kimlik doğrulama:** `X-Api-Key` başlığı. Sunucu anahtarın SHA-256 özetini `api_keys` tablosunda arar. `role = app` uygulamalar içindir; `role = admin` silme ve listeleme içindir. İptal edilmiş anahtar (`revoked_at` dolu) 401 döner. Doğrulanmış anahtarlar 60 saniye bellekte önbelleğe alınır.
- **Hata formatı:**

```json
{ "error": { "code": "VALIDATION_ERROR", "message": "label.bot_tool is required when label.kind = bot", "details": {} } }
```

| HTTP | `code` örnekleri |
|---|---|
| 400 | `VALIDATION_ERROR`, `BAD_CHECKSUM` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` (yanlış rol) |
| 404 | `SESSION_NOT_FOUND` |
| 409 | `CHUNK_CONFLICT`, `SESSION_CLOSED` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 429 | `RATE_LIMITED` |
| 500 | `INTERNAL` |

- JSON gövde limiti 256 KB. Parça yükleme limiti `MAX_CHUNK_BYTES`.
- CORS kapalı (istemci mobil uygulama).

### 6.4 Uç noktalar

#### `GET /health`
Kimlik doğrulama yok. `200 {"ok":true,"version":"1.0.0"}`. Supabase'e basit bir sorgu atar; erişilemezse `503`.

#### `GET /v1/config`  *(role: app)*

```json
{
  "config_version": 1,
  "chunk_seconds": 5,
  "max_chunk_bytes": 5242880,
  "sensors": {
    "acc": {"enabled": true, "rate_hz": 100},
    "acc_u": {"enabled": true, "rate_hz": 100},
    "gyr": {"enabled": true, "rate_hz": 100},
    "gyr_u": {"enabled": true, "rate_hz": 100},
    "mag": {"enabled": true, "rate_hz": 50},
    "mag_u": {"enabled": true, "rate_hz": 50},
    "grav": {"enabled": true, "rate_hz": 50},
    "lin": {"enabled": true, "rate_hz": 100},
    "rot": {"enabled": true, "rate_hz": 50},
    "grot": {"enabled": true, "rate_hz": 50},
    "light": {"enabled": true},
    "prox": {"enabled": true},
    "press": {"enabled": true, "rate_hz": 10}
  },
  "upload": {"wifi_only": false, "max_local_mb": 200}
}
```

Faz 1'de bu yapılandırma `app_config` tablosundaki tek satırdan okunur (§7). SDK, yapılandırmayı oturum başında çeker; alamazsa son bilinen ya da gömülü varsayılanları kullanır.

#### `POST /v1/sessions`  *(role: app)*

İstek:

```json
{
  "local_session_id": "6f0c…",
  "install_id": "b1e2…",
  "participant": {
    "code": "P007",
    "handedness": "right",
    "age_range": "25-34",
    "gender": "female"
  },
  "consent_version": "2026-11-v1",
  "label": {"kind": "human", "bot_tool": null, "bot_host": null},
  "context": {"placement": "hand", "posture": "sitting", "hands": "one_right"},
  "notes": null,
  "device": { "...§3.7 cihaz anlık görüntüsü..." : "" },
  "clock": {"t0_elapsed_ns": 912345678901, "t0_uptime_ns": 812345678901, "t0_wall_ms": 1762934400000,
            "sensor_clock": {"acc": {"basis": "elapsed", "offset_ns": 0}}},
  "sdk_version": "1.0.0",
  "app_version": "1.0.0 (12)",
  "config_version": 1
}
```

Doğrulama kuralları:
- `label.kind = "human"` ise `participant.code` zorunlu, `bot_tool` ve `bot_host` null.
- `label.kind = "bot"` ise `bot_tool` ve `bot_host` zorunlu.
- `participant` objesi yalnızca katılımcının ilk oturumunda tam doldurulur; sonrakilerde `code` yeterli.
- `participant.code` deseni: `^P\d{3,5}$`.

Davranış:
1. `install_id` ile `devices` tablosuna upsert yapılır; `last_seen` ve cihaz anlık görüntüsü güncellenir.
2. Katılımcı yoksa oluşturulur, varsa `consent_version` güncellenir.
3. **İdempotent:** Aynı `local_session_id` ile daha önce oturum oluşturulmuşsa yenisi açılmaz; mevcut oturum `200` ile döner.

Yanıt `201` (ya da idempotent tekrar için `200`):

```json
{ "session_id": "a3d1…", "status": "open", "max_chunk_bytes": 5242880 }
```

#### `PUT /v1/sessions/:sessionId/chunks/:seq`  *(role: app)*

- `Content-Type: application/octet-stream`. Gövde = gzip baytları. `Content-Encoding` başlığı **kullanılmaz**; sunucu gövdeyi açmaz, olduğu gibi saklar.
- Başlıklar:

| Başlık | Zorunlu | Anlamı |
|---|---|---|
| `X-Chunk-Sha256` | ✅ | Gövdenin hex SHA-256 özeti |
| `X-Chunk-T-Start` | ✅ | Parçanın ilk olay zamanı (ns, oturuma göre) |
| `X-Chunk-T-End` | ✅ | Son olay zamanı |
| `X-Chunk-Streams` | ✅ | Virgülle ayrılmış akış listesi, ör. `sensor,touch,marker` |
| `X-Chunk-Records` | ❌ | Satır sayısı |

Davranış:
1. Oturum yoksa `404`. Oturum `complete` durumundaysa ve bu `seq` yoksa `409 SESSION_CLOSED`.
2. Gövdenin SHA-256'sı hesaplanır, başlıkla uyuşmazsa `400 BAD_CHECKSUM`.
3. `(session_id, seq)` zaten varsa: aynı sha → `200 {"status":"duplicate"}`; farklı sha → `409 CHUNK_CONFLICT`.
4. Storage'a yazılır: `sessions/{session_id}/{seq:000000}.ndjson.gz`, `upsert: false`, `contentType: application/gzip`.
5. `chunks` tablosuna satır eklenir, `sessions.received_chunks` artırılır.
   - Storage yazımı başarılı olup DB yazımı başarısız olursa istemci tekrar dener. Storage'daki dosya aynı sha ile zaten olacağı için `upsert: false` hata verir; bu hata "zaten var" olarak ele alınır ve DB satırı tekrar yazılmaya çalışılır.
6. Yanıt `201 {"status":"stored","seq":17}`.

Fastify'da ham gövde için: `app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: MAX_CHUNK_BYTES }, (req, body, done) => done(null, body))`.

#### `POST /v1/sessions/:sessionId/complete`  *(role: app)*

İstek:

```json
{
  "total_chunks": 112,
  "ended_at_wall_ms": 1762935000000,
  "stop_reason": "user",
  "stats": {
    "duration_s": 563.2,
    "sensor_hz": {"acc": 99.6, "gyr": 99.8, "mag": 49.9},
    "touch_events": 18234,
    "dropped_chunks": 0,
    "failed_chunks": 0,
    "sensors_missing": ["press"]
  }
}
```

Davranış: Eksik `seq` numaraları hesaplanır (`0 .. total_chunks-1` ile alınanlar karşılaştırılır).
- Eksik yoksa `status = complete`.
- Eksik varsa `status = incomplete` ve `200` ile `missing` listesi döner. İstemci eksikleri tekrar yükleyebilir; tüm parçalar gelince oturum otomatik olarak `complete` olur.
- Tekrar çağrılabilir (idempotent).

```json
{ "status": "incomplete", "missing": [44, 45] }
```

#### `GET /v1/admin/sessions`  *(role: admin)*

Query parametreleri: `label_kind`, `bot_tool`, `participant_code`, `status`, `from`, `to`, `limit` (≤ 500), `cursor`. Oturum listesini özet bilgilerle döner. (Supabase panelinden de bakılabilir; bu uç nokta `tools/ingest` için kolaylık sağlar.)

#### `GET /v1/admin/sessions/:sessionId/chunks`  *(role: admin)*

Parça listesini ve her biri için 1 saat geçerli **imzalı indirme URL'sini** döner (`storage.createSignedUrl`).

#### `DELETE /v1/admin/participants/:code`  *(role: admin)*

KVKK silme talebi için. Katılımcının **tüm oturumlarının** Storage dosyaları silinir, ardından DB satırları cascade ile silinir. `audit_log`'a bir kayıt yazılır (silinen oturum sayısı; kişisel veri yok). Yanıt: `200 {"deleted_sessions": 7, "deleted_chunks": 812}`.

#### `POST /v1/score`  *(Faz 2, şimdilik stub)*

`501 {"error":{"code":"NOT_IMPLEMENTED"}}` döner. Sözleşme §11'de.

### 6.5 Güvenlik

- Service role anahtarı yalnızca API sunucusundadır. Mobil uygulama Supabase'e **doğrudan** bağlanmaz.
- API anahtarları DB'de sadece SHA-256 özetiyle tutulur. Anahtar üretimi için bir CLI script'i: `npm run key:create -- --name research-app --role app` (anahtarı bir kez ekrana yazar).
- HTTPS zorunlu. Faz 1'de API, Caddy reverse proxy arkasında (otomatik TLS) ya da Tailscale ağı içinde çalışır.
- Rate limit: anahtar başına dakikada 600 istek; admin uçları için dakikada 60.

---

## 7. Supabase şeması

`supabase/migrations/0001_init.sql`:

```sql
create extension if not exists pgcrypto;

create table api_keys (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  key_hash    text not null unique,                 -- sha256 hex
  role        text not null check (role in ('app','admin')),
  created_at  timestamptz not null default now(),
  revoked_at  timestamptz
);

create table app_config (
  id              int primary key default 1 check (id = 1),
  config_version  int not null,
  config          jsonb not null,
  updated_at      timestamptz not null default now()
);

create table participants (
  id               uuid primary key default gen_random_uuid(),
  code             text not null unique check (code ~ '^P\d{3,5}$'),
  handedness       text check (handedness in ('left','right','both','unknown')),
  age_range        text check (age_range in ('18-24','25-34','35-44','45-54','55-64','65+','unknown')),
  gender           text,
  consent_version  text not null,
  consent_at       timestamptz not null default now(),
  created_at       timestamptz not null default now()
);

create table devices (
  id             uuid primary key default gen_random_uuid(),
  install_id     text not null unique,
  manufacturer   text,
  model          text,
  android_sdk    int,
  snapshot       jsonb not null,           -- §3.7 tam anlık görüntü (son hali)
  first_seen     timestamptz not null default now(),
  last_seen      timestamptz not null default now()
);

create table sessions (
  id                uuid primary key default gen_random_uuid(),
  local_session_id  text not null unique,
  participant_id    uuid references participants(id) on delete cascade,
  device_id         uuid not null references devices(id),
  label_kind        text not null check (label_kind in ('human','bot')),
  bot_tool          text check (bot_tool in ('adb_input','appium','uiautomator','espresso','maestro',
                                             'autoclicker_app','accessibility_app','scrcpy_remote','replay','other')),
  bot_host          text check (bot_host in ('emulator_android_studio','emulator_genymotion',
                                             'emulator_bluestacks','emulator_other','real_device')),
  placement         text not null check (placement in ('hand','table','holder','lap','other')),
  posture           text not null check (posture in ('sitting','standing','walking','lying','na')),
  hands             text not null check (hands in ('one_left','one_right','two','na')),
  notes             text,
  device_snapshot   jsonb not null,        -- oturum anındaki cihaz durumu (bütünlük sinyalleri dahil)
  clock             jsonb not null,
  sdk_version       text not null,
  app_version       text,
  config_version    int,
  status            text not null default 'open' check (status in ('open','incomplete','complete')),
  stop_reason       text,
  total_chunks      int,
  received_chunks   int not null default 0,
  stats             jsonb,
  started_at        timestamptz not null default now(),
  ended_at          timestamptz,
  constraint human_needs_participant check (label_kind = 'bot' or participant_id is not null),
  constraint bot_needs_tool check (label_kind = 'human' or (bot_tool is not null and bot_host is not null))
);
create index on sessions (label_kind, bot_tool);
create index on sessions (participant_id);
create index on sessions (started_at);

create table chunks (
  session_id    uuid not null references sessions(id) on delete cascade,
  seq           int  not null check (seq >= 0),
  storage_path  text not null,
  size_bytes    int  not null,
  sha256        text not null,
  t_start_ns    bigint not null,
  t_end_ns      bigint not null,
  streams       text[] not null,
  records       int,
  received_at   timestamptz not null default now(),
  primary key (session_id, seq)
);

create table audit_log (
  id          bigserial primary key,
  at          timestamptz not null default now(),
  action      text not null,
  detail      jsonb
);

-- Tüm tablolarda RLS açık, hiçbir politika yok:
-- anon/authenticated rolleri hiçbir şey göremez; yalnızca service role (API) erişir.
alter table api_keys      enable row level security;
alter table app_config    enable row level security;
alter table participants  enable row level security;
alter table devices       enable row level security;
alter table sessions      enable row level security;
alter table chunks        enable row level security;
alter table audit_log     enable row level security;

-- received_chunks sayacını atomik artırmak için
create or replace function increment_received(p_session uuid) returns void
language sql as $$
  update sessions set received_chunks = received_chunks + 1 where id = p_session;
$$;
```

**Storage:** `raw-chunks` adında **private** bir bucket. Public erişim yok; dosyalara yalnızca service role ya da imzalı URL ile erişilir.

> ⚠️ **KVKK notu:** Supabase'in barındırılan (cloud) bölgeleri Türkiye dışındadır. Yurt dışına veri aktarımı KVKK'da özel şartlara bağlıdır. Faz 1 için iki seçenek var:
> 1. Rıza metnine yurt dışına aktarımı açıkça ekleyip katılımcıdan **ayrıca açık rıza** almak, ya da
> 2. **Supabase'i self-host etmek** (Docker ile, ör. GX10 üzerinde ya da Türkiye'deki bir sunucuda).
>
> Bankacılık (Faz 2+) için veri bankanın altyapısında kalmalıdır. Nihai karar bir hukuk danışmanıyla verilmelidir.

---

## 8. Gizlilik ve KVKK kontrol listesi

- [ ] Rıza metni: hangi veri, ne amaçla, nerede, ne kadar süre saklanacak, nasıl silinecek. Yurt dışı aktarım varsa ayrıca belirtilmeli.
- [ ] Rıza metni sürümlü; her oturumda `consent_version` kaydediliyor.
- [ ] Sistemde gerçek isim, e-posta, telefon, IMEI, Android ID, konum **yok**.
- [ ] Katılımcı kodu ↔ isim eşleşmesi sistemin **dışında**, kilitli bir dosyada (ör. şifreli bir tablo; araştırma sorumlusunda).
- [ ] Metin içerikleri ve gerçek PIN'ler hiçbir zaman kaydedilmiyor (birim testiyle doğrulanır, §3.13).
- [ ] PIN tuşlarındaki dokunmalar `touch` akışında maskeleniyor (§3.6.1).
- [ ] Silme talebi tek API çağrısıyla uygulanabiliyor (§6.4).
- [ ] Saklama süresi tanımlı (ör. araştırma bitiminden 2 yıl sonra silinir).
- [ ] Telefonda veri şifreli ve yedeklemeye dahil değil.

---

## 9. Bot verisi toplama (`tools/bots/`)

Her bot oturumu, araştırma uygulamasında **bot etiketiyle** başlatılır; ardından script çalıştırılır.

### 9.1 adb (`bot_tool = adb_input`)

```bash
# T1: hedeflere dokunma (hedef koordinatlarını logcat'teki marker'lardan ya da sabit bir listeden al)
adb shell input tap 540 1200
# T3: kart kaydırma (x1 y1 x2 y2 süre_ms)
adb shell input swipe 200 1200 900 1200 250
# Android 10+: daha ince kontrol
adb shell input motionevent DOWN 540 1200
adb shell input motionevent MOVE 600 1210
adb shell input motionevent UP 600 1210
```

`tools/bots/adb_bot.py` script'i:
- Görevleri sırayla yürütür.
- **Üç zorluk seviyesi** içerir: `--mode naive` (sabit süreler), `--mode jitter` (rastgele gecikme ve konum sapması), `--mode humanlike` (eğri yollar; `motionevent` ile insan benzeri hız profili).

### 9.2 Appium / UI Automator (`bot_tool = appium | uiautomator`)

`tools/bots/appium_bot.py`, `resource-id`'ler üzerinden aynı görevleri yürütür (§4.3).

### 9.3 Matris

Her kombinasyon için **en az 20 oturum** hedeflenir:

| Host \ Araç | adb (3 seviye) | Appium | Auto-clicker uygulaması | Accessibility uygulaması | scrcpy (insan, uzaktan) |
|---|---|---|---|---|---|
| Android Studio emülatörü | ✅ | ✅ | ✅ | ✅ | – |
| Genymotion | ✅ | ✅ | – | – | – |
| BlueStacks | ✅ | – | ✅ | – | – |
| Gerçek cihaz: masada | ✅ | ✅ | ✅ | ✅ | ✅ |
| Gerçek cihaz: tutucuda | ✅ | – | ✅ | – | – |
| Gerçek cihaz: elde (insan tutarken script çalışır) | ✅ | – | – | ✅ | – |

Son satır, bankacılık zararlısı senaryosudur: kullanıcı telefonu tutuyor, dokunmayı zararlı yazılım enjekte ediyor.

---

## 10. Kabul kriterleri

| # | Kriter | Nasıl doğrulanır |
|---|---|---|
| A1 | Test cihazlarının ≥ %90'ında `acc` ve `gyr` gerçekleşen hızı ≥ 90 Hz | Oturum `stats.sensor_hz` |
| A2 | Saat hizalama hatası medyan < 20 ms | Saat Testi ekranı (§4.2) |
| A3 | Uçak modunda 10 dakikalık oturum, bağlantı gelince **eksiksiz** yükleniyor | `status = complete`, `missing = []` |
| A4 | Aynı parçanın tekrar yüklenmesi veri çiftlemiyor | API testi (`duplicate`) |
| A5 | Uygulama arka plana atılıp geri getirildiğinde toplama devam ediyor, `lifecycle` olayları yazılıyor | Elle test |
| A6 | Kaydedilen hiçbir dosyada yazılan metin ya da gerçek PIN yok | Birim testi + rastgele parça incelemesi |
| A7 | SDK hiçbir hata senaryosunda host uygulamayı çökertmiyor | Sensörsüz emülatör, ağsız cihaz, dolu disk testleri |
| A8 | Performans bütçesi (§3.11) karşılanıyor | `PERF.md` |
| A9 | Emülatörde `tool = mouse` ya da `emulator_hints` dolu geliyor | Emülatör oturumu |
| A10 | `DELETE /v1/admin/participants/:code` tüm Storage dosyalarını ve satırları siliyor | API entegrasyon testi |

---

## 11. Faz 2 (bilgi amaçlı; bu sürümde yapılmayacak)

- **Cihaz üzerinde özellik çıkarma:** BrainRun deneyindeki özellikler (süre, düzlük, hız profili, sensör hareketliliği vb.) Kotlin'de, Python eğitim koduyla **aynı tanımlarla** hesaplanacak. Tanımlar sürümlü bir dokümanda tutulacak; Python ve Kotlin için ortak test vektörleriyle doğrulanacak.
- **`POST /v1/score`** sözleşmesi (taslak):

```json
// istek
{ "install_id": "…", "feature_version": 1, "window": {"touch": {...}, "sensor": {...}, "device": {...}} }
// yanıt
{ "risk": 0.03, "decision": "allow", "model_version": "v2-both-2026-12", "reasons": ["sensor_static", "tool_mouse"] }
```

`decision` değerleri: `allow` | `step_up` (OTP iste) | `deny`. Karar eşikleri bankada yapılandırılır.
- Production modunda ham veri saklanmaz; sadece RAM'de birkaç saniyelik halka tampon tutulur.
- Play Integrity API entegrasyonu (sunucu tarafı doğrulama).
- Dialog ve PopupWindow desteği (`HumanSignal.attach(window)`).
- iOS SDK.

---

## 12. Uygulama sırası (önerilen)

| Kilometre taşı | İçerik | Tahmini süre |
|---|---|---|
| **M1** | SDK iskeleti, saat tabanı, SensorCollector, ChunkWriter, LocalStore, Canlı Sensör ekranı | 1 hafta |
| **M2** | TouchCollector, SecureKeypadView, trackTextField, DeviceCollector, Saat Testi ekranı | 1 hafta |
| **M3** | Supabase şeması, Node.js API (tüm Faz 1 uçları ve testleri), Uploader | 1 hafta |
| **M4** | Araştırma uygulaması: rıza, kurulum, T1–T7 görevleri, özet ekranı | 1–1,5 hafta |
| **M5** | Kabul testleri (§10), performans ölçümü, bot script'leri (§9) | 1 hafta |
| **M6** | `tools/ingest`: Supabase'den indirme → parquet dönüştürme (GX10'da; Python) | 2–3 gün |

Her kilometre taşının sonunda çalışan bir demo olmalı. M1 sonunda, Canlı Sensör ekranında 100 Hz'i görüyor olmalıyız.

---

## Ek A: Örnek parça (açılmış hâli, kısaltılmış)

```json
{"k":"header","v":1,"session_id":"a3d1…","local_session_id":"6f0c…","seq":17,"t_start":85000000000,"t_end":90000000000,"sdk":"1.0.0"}
{"k":"sensor","s":"acc","t":[85000123000,85010119000],"v":[[0.124,9.71,0.331],[0.131,9.702,0.309]]}
{"k":"sensor","s":"gyr","t":[85000456000,85010450000],"v":[[0.0012,-0.0031,0.0004],[0.0019,-0.0027,0.0002]]}
{"k":"marker","t":85500000000,"name":"task_start","attrs":{"task":"pin_entry","trial":"3"}}
{"k":"touch","t":86012345000,"h":0,"a":"DOWN","pc":1,"p":[{"id":0,"masked":true,"pr":0.41,"sz":0.12,"ma":37.0,"mi":30.5,"o":-0.2,"tool":"finger"}],"src":"touchscreen","dev":3,"fl":0,"scr":"PinEntryActivity"}
{"k":"key","f":"pin","td":86012345000,"tu":86097800000,"dx":-4.2,"dy":6.1,"pr":0.41,"sz":0.12,"ma":37.0,"kc":"digit","kid":"5"}
{"k":"text","f":"amount","t":88100000000,"d":1,"len":3,"paste":false,"ime":true}
{"k":"battery","t":89000000000,"lvl":0.83,"plug":"none","temp_c":31.2,"volt_mv":4120,"status":"discharging"}
```

## Ek B: Okuma kolaylığı için Python örneği (GX10 tarafı)

```python
import gzip, json
def read_chunk(path):
    with gzip.open(path, "rt") as f:
        for line in f:
            yield json.loads(line)
```
