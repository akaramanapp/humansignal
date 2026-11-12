# Kabul Raporu — Faz 1 (SPEC §10)

Tarih: 2026-11-12 · Ortam: Pixel_5 emülatörü (API 30); **gerçek cihaz: Huawei YAL-L21** (Kirin 980, Android 10, EMUI 11); Supabase (eu-west-1); API: Vercel (`humansignal-fawn.vercel.app`) ve yerel.

| # | Kriter | Durum | Kanıt |
|---|---|---|---|
| A1 | ≥ %90 cihazda `acc`/`gyr` ≥ 90 Hz | ✅ 1/1 cihaz | Huawei YAL-L21: acc 100,0 / gyr 99,7–100,0 Hz (2 oturum); emülatör 100 Hz. Kriter cihaz filosu ister: her yeni cihazda ilk oturumun `stats.sensor_hz`'ine bakın. |
| A2 | Saat hizalama hatası medyan < 20 ms | ✅ | Huawei YAL-L21, Saat Testi: 10/10 vuruş algılandı, **medyan \|fark\| 9,5 ms**; işaretli farklar −17,7…+23,3 ms, ortalama ≈ +0,2 ms (sistematik kayma yok). |
| A3 | Uçak modunda oturum, bağlantı gelince **eksiksiz** yükleniyor | ✅ (kısmi süre) | Uçak modunda 31 sn (7/7), ~3 dk (37 parça) ve ~6 dk (68 parça) oturumlar bağlantı gelince 45–75 sn içinde yüklendi; 1.486 parçalık oturum `complete`. Kesintisiz 10 dk'lık tek koşu bot hatası yüzünden tamamlanmadı. |
| A4 | Aynı parçanın tekrarı veri çiftlemiyor | ✅ | API testi (`duplicate`, sayaç artmıyor) + gerçek API'ye SDK sözleşme testi. |
| A5 | Arka plan/ön plan: toplama devam ediyor, `lifecycle` yazılıyor | 🟡 | M1'de elle: arka planda sensörler durdu, `lifecycle` olayları parçalarda. Otomatik test hazır, çalıştırılmadı: `tools/acceptance/a5_background.py`. |
| A6 | Hiçbir dosyada yazılan metin ya da gerçek PIN yok | ✅ | Birim + cihaz testleri; Supabase'teki tam oturumda 110/110 parça tarandı: yazılan sahte IBAN/açıklama/alıcı yok, PIN dokunmaları maskeli (`npm run inspect`). |
| A7 | SDK hiçbir hata senaryosunda host'u çökertmiyor | 🟡 | Ağsız, geçersiz adres, sunucu hataları, 401, bozuk kuyruk: birim testli. Disk/aşırı büyük olay/yanlış kullanım cihaz testleri yazıldı (`RobustnessTest`), çalıştırılmadı. |
| A8 | Performans bütçesi | ❌ gerçek cihazda | `android/PERF.md`: Huawei'de SDK ~%19 tek çekirdek (hedef < %5), ana thread p50 0,47 / p99 2,7 ms (hedef < 0,2 ms); ek heap ✅. Veri kalitesini etkilemiyor (Hz hedefte, kayıp yok); Faz 2 (bankacılık) öncesi optimize edilmeli. |
| A9 | Emülatörde `tool = mouse` ya da `emulator_hints` dolu | ✅ | `emulator_hints`: `build.hardware:ranchu`, `sensor:goldfish`, `sensor:aosp`, … |
| A10 | Katılımcı silme tüm Storage dosyalarını ve satırları siliyor | ✅ | `npm run a10` gerçek Supabase'te 13/13 (yetim nesne dahil, audit'te kişisel veri yok). |

## Gerçek cihazdan veri notları (model için)

- `dev` gerçek dokunmatik ekranda pozitif (5); enjekte olaylarda -1 (`humanlike` bot dahil).
- Huawei basıncı 0–1 aralığında **normalize etmiyor** (ortalama 5,2, sd 2,6): basınç cihaz başına normalize edilmeli.
- `emulator_hints` bu gerçek cihazda da `sensor:aosp` içeriyor (AOSP yazılım sensörleri): tek başına bot sinyali değildir.

## Bot verisi (§9)

`tools/bots/adb_bot.py` (`naive`, `jitter`, `humanlike`) emülatörde çalışıyor; `appium_bot.py` yazıldı, Appium kurulu
olmadığı için denenmedi. Bot'larla toplanan oturumlarda gözlenen ayırt edici sinyaller `tools/bots/README.md`'de.

## Veri toplamaya başlamadan önce

**Zorunlu**
1. Rıza metni (`android/research-app/src/main/assets/consent_tr.md`) hukuk danışmanı onayından geçmeli; araştırma
   sorumlusu bilgileri doldurulmalı. Veri AB'de saklandığı için yurt dışı aktarım için ayrı açık rıza alınıyor.
2. ~~API'nin canlı adresi~~ ✅ `https://humansignal-fawn.vercel.app` (Vercel koruması kapalı, anahtar 200 / anahtarsız 401,
   SDK yükleme sözleşme testi geçti). research-app'in varsayılan adresi artık bu.
3. ~~Bir gerçek telefonda pilot oturum~~ ✅ Huawei YAL-L21: A1, A2 geçti; oturum Vercel üzerinden Supabase'e eksiksiz
   geldi (84/84). Her **yeni cihaz modelinde** ilk oturumdan önce Saat Testi'ni tekrarlayın.
4. Katılımcı kodu ↔ isim eşleşmesi sistem dışında, şifreli bir dosyada (§8).

**Önerilen**
- Katılımcılara release build verin (debug build'deki STOP receiver'ı ve logcat marker'larını içermez); imzalama anahtarı gerekli.
- Kalan emülatör testleri (A5, A7) — ilk pilot verisiyle birlikte de doğrulanabilir.
