# HumanSignal Android (M1–M4)

`sdk/` (humansignal-sdk `.aar`) ve `research-app/`. JDK 17 gerekir.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew :sdk:testDebugUnitTest          # birim testleri (JVM)
./gradlew :sdk:connectedDebugAndroidTest  # enstrümantasyon testleri (emülatör/cihaz)
./gradlew :sdk:assembleRelease            # sdk/build/outputs/aar/humansignal-sdk-1.0.0-release.aar
./gradlew :research-app:installDebug      # Canlı Sensör ekranı
```

## Durum

| Bileşen | Dosya | Durum |
|---|---|---|
| Public API (§3.2) | `HumanSignal.kt`, `Models.kt` | ✅ |
| Zaman tabanı (§3.3) | `internal/Clock.kt` | ✅ offset her 1 sn yenilenir, sensör saat tabanı doğrulanır |
| SensorCollector (§3.4) | `internal/SensorCollector.kt` | ✅ ayrı HandlerThread, `maxReportLatencyUs = 0` |
| ChunkWriter (§3.8) | `internal/Chunk.kt`, `internal/SessionRecorder.kt` | ✅ 5 sn / 2 MB, gzip NDJSON, sütunlu sensör |
| LocalStore (§3.9) | `internal/LocalStore.kt`, `internal/Crypto.kt` | ✅ AES-256-GCM (Keystore), `queue.json`, kota |
| Yaşam döngüsü | `HumanSignal.kt` | ✅ foreground/background, screen_on/off, orientation/config; 10 dk → TIMEOUT |
| Canlı Sensör ekranı (§4.2) | `research-app/.../LiveSensorActivity.kt` | ✅ |
| **M2** TouchCollector (§3.5) | `internal/TouchCollector.kt` | ✅ Window.Callback gözlemcisi, historical örnekler, `input_device` |
| **M2** SecureKeypadView (§3.6.1) | `SecureKeypadView.kt` | ✅ `touch` maskeleme, `kid` yalnızca `recordKeyIdentity`; sabit resource-id'ler |
| **M2** trackTextField (§3.6.2) | `internal/TextFieldWatcher.kt` | ✅ yalnızca d/len/paste/ime + `focus` |
| **M2** DeviceCollector (§3.7) | `internal/DeviceCollector.kt` | ✅ anlık görüntü → `session.json`, `battery` 30 sn, `integrity_change` |
| **M2** Saat Testi (§4.2) | `internal/ClockTest.kt`, `research-app/.../ClockTestActivity.kt` | ✅ (kabul ölçümü gerçek cihazda yapılmalı) |
| Giriş Testi (geliştirici) | `research-app/.../InputTestActivity.kt` | ✅ |
| **M3** `/v1/config` | `HumanSignal.kt` | ✅ 3 sn zaman aşımı → son bilinen → gömülü varsayılan |
| **M3** Uploader (§3.10) | `internal/UploadEngine.kt`, `internal/UploadWorker.kt`, `internal/ApiClient.kt` | ✅ kayıt → sıralı PUT → complete; 30 sn–1 sa geri çekilme |
| **M4** Ekran akışı (§4.1) | `research-app/.../LauncherActivity.kt` | ✅ Ayarlar → Rıza → Kurulum → T1–T7 → Özet |
| **M4** Rıza (§4.2) | `ConsentActivity.kt`, `assets/consent_tr.md` | ✅ yurt dışı aktarım için ayrı onay kutusu; metin **taslak** (hukuki onay gerekli) |
| **M4** Görevler (§4.3) | `TaskActivity.kt`, `tasks/*.kt` | ✅ sabit sıra, `task_start`/`task_end` + `trial`, "Atla" → `skipped` |
| **M4** Özet (§4.2) | `SummaryActivity.kt` | ✅ süre, parça, ortalama Hz, canlı yükleme durumu |
| **M4** Bot modu (§4.4) | `src/debug/.../DebugStopReceiver.kt` | ✅ yalnızca debug: `adb shell am broadcast -a io.humansignal.research.STOP -p io.humansignal.research` |

`startSession` toplayıcıları başlatır, ardından sunucuya kaydolmayı 3 sn dener; ağ yoksa yerel id ile devam eder ve Uploader bağlantı gelince kaydeder (A3 emülatörde doğrulandı).

API adresi/anahtarı (M4'te Ayarlar ekranına taşınacak): `./gradlew :research-app:installDebug -Phumansignal.apiUrl=https://… -Phumansignal.apiKey=…`
Varsayılan: `http://10.0.2.2:8787` + `dev-app-key` (`api/` bellek modu). Düz HTTP yalnızca debug'da ve yalnızca 10.0.2.2/localhost için açıktır.

Canlı API'ye sözleşme testi: `HS_API_URL=http://localhost:8787 HS_API_KEY=dev-app-key ./gradlew :sdk:testDebugUnitTest --tests '*LiveApiContractTest*'`

### Spesifikasyondan bilinçli sapmalar

- Uptime saati `System.nanoTime()` ile okunur (Android'de `uptimeMillis` ile aynı `CLOCK_MONOTONIC`, ama ns çözünürlüklü).
- Yerel kuyruk SQLite yerine `queue.json` (§3.9 her ikisine de izin veriyor); oturum meta verisi `{local_session_id}/session.json`.
- Boş parça (veri yok, ör. arka planda) yazılmaz; `seq` yine de boşluksuz artar.
- Android istenen örnekleme hızını yalnızca öneri olarak alır; bazı sensörler hedefin üstünde (ör. emülatörde `mag` 100 Hz, `gyr_u` 200 Hz) gelir. Bilinçli olarak örnek atılmaz (karar: 2026-11-12).
- `SecureKeypadView` bir `ViewGroup`'tur: her tuş sabit resource-id'li bir alt View'dır (Appium/UI Automator için). Erişilebilirlik servisinin dokunmasız tıklaması `key` satırına `"a11y":true` ile yazılır (spesifikasyonda yok; güçlü bot sinyali).
- Yükleme ağ kısıtı (API 28+): `VALIDATED` ve `NOT_VPN` aranmaz; internetsiz laboratuvar Wi-Fi'ı ve Tailscale arkasındaki API'ye de yüklenir. Uygulama açılışında bekleyen yükleme işi `REPLACE` ile yenilenir (eski kısıtlarla takılı kalmasın).
- Süreç oturum açıkken ölürse oturum bir sonraki `init`'te `stop_reason = "process_died"` ile kapatılır ve yüklenir.
- `complete` gövdesine `clock` (kesinleşen `sensor_clock`) eklenir; `stats.failed_chunks` = failed + conflict.
- `SessionMeta.participantInfo` (el tercihi, yaş aralığı, cinsiyet) eklendi: §6.4 ilk oturumda bunları istiyor, §3.2'de alan yoktu.
- `lifecycle` satırı: `{"k":"lifecycle","t":…,"e":"background"}`; `integrity_change`: `{"field","old","new"}`; `emulator_hints` biçimi `build.<alan>:<belirteç>` / `sensor:<belirteç>`.
- Görev marker'ları debug build'de logcat'e de yazılır (`adb logcat -s HS-MARK`; §9.1 adb bot'ları T1 hedef koordinatlarını buradan okur).
- T5/T6 doğruluk kontrolü Türkçe karakterleri katlar (ç→c, ı→i …); `adb input text` ASCII dışını yazamaz. Tutmayan alan adı `task_end.fields`'a yazılır (içerik değil).
- Ayar değişikliği süreci yeniden başlatır: SDK yapılandırması `init`'te sabitlenir.

## Bot (M4 ilk sürüm)

`python3 tools/bots/adb_bot.py [--first-run] [--tasks t1,t3] [--host real_device]` — Oturum Kurulumu'ndan (ya da Özet'ten) başlar, T1–T7'yi yürütür, yüklemenin bitmesini bekler. Şimdilik yalnızca `--mode naive`; `jitter`/`humanlike` M5'te.
