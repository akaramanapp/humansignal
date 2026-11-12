# Performans (SPEC §3.11)

> **Durum:** Ölçümler yalnızca **emülatörde** (Pixel_5 AVD, API 30, arm64, Apple Silicon) ve JVM'de yapıldı.
> §3.11'in hedef cihazı (2022 orta segment Samsung) ve pil ölçümü için gerçek cihazda Android Studio Profiler gerekir.

## Bütçe

| Metrik | Hedef | Ölçüm | Durum |
|---|---|---|---|
| CPU (tek çekirdek, ortalama) | < %5 | _A3 koşusu sonrası doldurulacak_ | |
| Bellek (ek heap) | < 25 MB | _A3 koşusu sonrası doldurulacak_ | |
| Ana thread, dokunma gözlemi | < 0,2 ms / olay | _A3 koşusu sonrası doldurulacak_ | |
| Disk yazma | 5 sn'de bir toplu | 5 sn'de bir parça (gzip + AES-GCM, tek IO thread) | ✅ |
| Pil (10 dk) | < %2 | emülatörde ölçülemez | ⏳ gerçek cihaz |

## Parça kodlama (JVM, `EncodePerfTest`)

Gerçekçi 5 sn'lik parça (10 sensör akışı, emülatörde gözlenen hızlarla; 300 dokunma satırı):

- Kodlama + gzip + SHA-256 + AES-GCM: **17 ms / parça** (bir çekirdeğin ~%0,34'ü, M-serisi Mac JVM)
- Boyut: 265 KB ham → 74 KB gzip (3,6×) ≈ **0,9 MB/dk** (SPEC tahmini 0,5–1 MB/dk). Rastgele değerler gerçek
  sensör verisinden kötü sıkışır; gerçek oturumlarda Supabase'te parça başına ~15 KB gözlendi.
