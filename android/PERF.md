# Performans (SPEC §3.11)

> Ölçümler **emülatörde** (Pixel_5 AVD, API 30, arm64, 4 çekirdek, Apple Silicon) ve JVM'de yapıldı, 2026-11-12.
> §3.11'in hedef cihazı (2022 orta segment Samsung) ve pil ölçümü için gerçek cihazda Android Studio Profiler gerekir.

## Özet

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
