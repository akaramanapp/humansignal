# Kabul Raporu — Faz 1 (SPEC §10)

Tarih: 2026-11-12 · Ortam: Pixel_5 emülatörü (API 30), Supabase (eu-west-1), yerel API. Gerçek cihaz testi henüz yapılmadı.

| # | Kriter | Durum | Kanıt |
|---|---|---|---|
| A1 | ≥ %90 cihazda `acc`/`gyr` ≥ 90 Hz | ⏳ **gerçek cihaz** | Emülatör: acc 100,0 / gyr 100,0 Hz (`stats.sensor_hz`, tüm oturumlar). Cihaz filosunda ölçülmeli. |
| A2 | Saat hizalama hatası medyan < 20 ms | ⏳ **gerçek cihaz** | Saat Testi ekranı ve analizi hazır (birim testli). Emülatörde fiziksel ivme sıçraması olmadığı için ölçülemez. |
| A3 | Uçak modunda oturum, bağlantı gelince **eksiksiz** yükleniyor | ✅ (kısmi süre) | Uçak modunda 31 sn (7/7), ~3 dk (37 parça) ve ~6 dk (68 parça) oturumlar bağlantı gelince 45–75 sn içinde yüklendi; 1.486 parçalık oturum `complete`. Kesintisiz 10 dk'lık tek koşu bot hatası yüzünden tamamlanmadı. |
| A4 | Aynı parçanın tekrarı veri çiftlemiyor | ✅ | API testi (`duplicate`, sayaç artmıyor) + gerçek API'ye SDK sözleşme testi. |
| A5 | Arka plan/ön plan: toplama devam ediyor, `lifecycle` yazılıyor | 🟡 | M1'de elle: arka planda sensörler durdu, `lifecycle` olayları parçalarda. Otomatik test hazır, çalıştırılmadı: `tools/acceptance/a5_background.py`. |
| A6 | Hiçbir dosyada yazılan metin ya da gerçek PIN yok | ✅ | Birim + cihaz testleri; Supabase'teki tam oturumda 110/110 parça tarandı: yazılan sahte IBAN/açıklama/alıcı yok, PIN dokunmaları maskeli (`npm run inspect`). |
| A7 | SDK hiçbir hata senaryosunda host'u çökertmiyor | 🟡 | Ağsız, geçersiz adres, sunucu hataları, 401, bozuk kuyruk: birim testli. Disk/aşırı büyük olay/yanlış kullanım cihaz testleri yazıldı (`RobustnessTest`), çalıştırılmadı. |
| A8 | Performans bütçesi | 🟡 | `android/PERF.md`: SDK CPU ~%4, ek heap +5…10 MB ✅; ana thread p99 0,29 ms ⚠️; pil ⏳ gerçek cihaz. |
| A9 | Emülatörde `tool = mouse` ya da `emulator_hints` dolu | ✅ | `emulator_hints`: `build.hardware:ranchu`, `sensor:goldfish`, `sensor:aosp`, … |
| A10 | Katılımcı silme tüm Storage dosyalarını ve satırları siliyor | ✅ | `npm run a10` gerçek Supabase'te 13/13 (yetim nesne dahil, audit'te kişisel veri yok). |

## Bot verisi (§9)

`tools/bots/adb_bot.py` (`naive`, `jitter`, `humanlike`) emülatörde çalışıyor; `appium_bot.py` yazıldı, Appium kurulu
olmadığı için denenmedi. Bot'larla toplanan oturumlarda gözlenen ayırt edici sinyaller `tools/bots/README.md`'de.

## Veri toplamaya başlamadan önce

**Zorunlu**
1. Rıza metni (`android/research-app/src/main/assets/consent_tr.md`) hukuk danışmanı onayından geçmeli; araştırma
   sorumlusu bilgileri doldurulmalı. Veri AB'de saklandığı için yurt dışı aktarım için ayrı açık rıza alınıyor.
2. API'nin canlı adresi çalışıyor olmalı (Vercel düzeltmesi push edilip `GET /health` → `{"ok":true}` doğrulanmalı),
   uygulamada Ayarlar → API adresi bu HTTPS adres olmalı.
3. **Bir gerçek telefonda pilot oturum:** Saat Testi (medyan < 20 ms, A2) ve Canlı Sensör'de acc/gyr ≥ 90 Hz (A1),
   ardından `npm run inspect` ile oturumun eksiksiz geldiğini kontrol et. Saat hizalaması bozuksa tüm veri kullanılamaz.
4. Katılımcı kodu ↔ isim eşleşmesi sistem dışında, şifreli bir dosyada (§8).

**Önerilen**
- Katılımcılara release build verin (debug build'deki STOP receiver'ı ve logcat marker'larını içermez); imzalama anahtarı gerekli.
- Kalan emülatör testleri (A5, A7) — ilk pilot verisiyle birlikte de doğrulanabilir.
