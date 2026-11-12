# Performans (SPEC §3.11)

> Ölçümler 2026-11-12: **gerçek cihaz Huawei YAL-L21** (Kirin 980, Android 10, EMUI 11, 8 çekirdek), emülatör
> (Pixel_5 AVD, API 30) ve JVM. Pil ölçümü henüz yapılmadı.

## Gerçek cihaz (Huawei YAL-L21) — belirleyici sonuç

| Metrik | Hedef | Ölçüm | Durum |
|---|---|---|---|
| CPU — SDK (tek çekirdek) | < %5 | **~%19**: sensör thread %8,8 + IO thread %5,1 + GC ~%5,3 (`/proc/<pid>/task/*/stat`, 30 sn) | ❌ |
| Ana thread, dokunma gözlemi | < 0,2 ms / olay | p50 **0,47 ms**, p99 **2,7 ms** (n = 140) | ❌ |
| Bellek (ek heap) | < 25 MB | Java heap ~15,7 MB oturumda | ✅ |
| Sensör hızı | — | acc 100,0 / gyr 100,0 Hz, istenen hızlara uyuyor | ✅ |

Veri kalitesini etkilemiyor; Faz 1 araştırma oturumlarında (10 dk) kabul edilebilir. Faz 2 (bankacılık uygulaması) öncesi:
1. `NumFmt.appendSig`: `BigDecimal` yerine ayırmasız (allocation-free) 5 anlamlı basamak biçimlendirme — IO thread ve GC payının çoğu.
2. Dokunma satırını ana thread'de JSON'a kodlamak yerine alanları ilkel bir diziye kopyalayıp kodlamayı sensör/IO thread'ine taşımak.
3. Sensör thread'i: olay başına iş zaten az; `maxReportLatencyUs` > 0 (donanım FIFO batching) uyanma sayısını düşürür, ama
   SPEC §3.4 `0` istiyor — karar gerektirir.

## Emülatör


| Metrik | Hedef | Ölçüm (emülatör) | Durum |
|---|---|---|---|
| CPU — SDK (tek çekirdek) | < %5 | **~%4** (`HumanSignal-sensors` thread'i; IO thread ölçüm çözünürlüğünün altında) | ✅ emülatörde |
| CPU — tüm süreç | — | ort. %11,3 (en çok %80 anlık) | ℹ️ çoğu uygulama arayüzü: `RenderThread` ~%12, ana thread ~%4 (bot liste kaydırırken) |
| Bellek (ek heap) | < 25 MB | Java heap 9,9 MB (oturumsuz) → 14,8–20,3 MB (oturumda): **+5…10 MB** | ✅ |
| Ana thread, dokunma gözlemi | < 0,2 ms / olay | p50 **0,10 ms**, p99 **0,29 ms**, en çok 3,1 ms (n = 403) | ⚠️ p50 içinde, p99 aşıyor |
| Disk yazma | 5 sn'de bir toplu | 5 sn'de bir parça (gzip + AES-GCM, tek IO thread) | ✅ |
| Pil (10 dk) | < %2 | emülatörde ölçülemez | ⏳ gerçek cihaz |

## Ayrıntı

**CPU.** `top -H` ile thread bazında, oturum açıkken ve bot görevleri yürütürken üç örnek: `HumanSignal-sensors` her
örnekte %4 (12 sensör, ~1.100 olay/sn). Uygulamanın kendi ekranları (liste, kartlar) çizim sırasında `RenderThread`'i
%12'ye çıkarıyor; bu SDK'nın değil araştırma uygulamasının maliyeti. Oturumsuz uygulama: %0.

**Parça kodlama (JVM, `EncodePerfTest`).** Gerçekçi 5 sn'lik parça (10 sensör akışı, emülatörde gözlenen hızlar;
300 dokunma satırı): kodlama + gzip + SHA-256 + AES-GCM **17 ms / parça** (~%0,34 çekirdek). 265 KB ham → 74 KB gzip
(3,6×) ≈ 0,9 MB/dk; gerçek oturumlarda parça başına ~15–25 KB görüldü (sensör verisi rastgele değerlerden iyi sıkışır).

**Ana thread.** SDK her `MotionEvent` için satırı ana thread'de JSON'a kodlar (historical örnekler dahil). p99'u
0,2 ms'nin altına indirmek için: olay alanlarını küçük bir diziye kopyalayıp kodlamayı sensör thread'ine taşımak
(Faz 1 veri toplamayı engellemez; gerçek cihaz ölçümüne göre karar verilecek). En yüksek değer (3,1 ms) büyük
olasılıkla ilk olay: JIT ısınması ve oturum başında bir kez yazılan `input_device` satırları.

## Gerçek cihazda yapılacak

1. Android Studio Profiler (CPU + Memory) ile 10 dk'lık tam oturum, hedef cihaz sınıfında.
2. Pil: oturum öncesi/sonrası `dumpsys batterystats` ya da `lvl` farkı (oturum `battery` satırları 30 sn'de bir).
3. Logcat'teki `perf: touch observe` satırı (debug build, oturum sonunda) ile ana thread süreleri.
