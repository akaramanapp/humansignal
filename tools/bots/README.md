# Bot verisi toplama (SPEC §9)

Her bot oturumu araştırma uygulamasında **Bot** etiketiyle başlar. Script'ler bunu kendileri yapar (Oturum Kurulumu → Bot → araç/host),
T1–T7'yi yürütür ve Özet ekranında yüklemenin bitmesini bekler. Uygulama Oturum Kurulumu ya da Özet ekranında olmalı
(ilk kurulumda `adb_bot.py --first-run` Ayarlar + Rıza'yı da geçer).

## adb (`bot_tool = adb_input`) — `adb_bot.py`

```bash
python3 tools/bots/adb_bot.py --mode naive|jitter|humanlike [--host real_device] [--tasks t1,t3] [--seed 7] [-s SERIAL]
```

| Mod | Dokunma | Zamanlama |
|---|---|---|
| `naive` | `input tap/swipe`, tam merkez | sabit |
| `jitter` | merkezden Gauss sapma (σ = 0.3·yarıçap), swipe süresi ×0.6–1.8 | log-normal |
| `humanlike` | `injector/` (app_process, ~120 Hz): Bezier yol, minimum-jerk hız, titreme, basınç/boyut eğrisi, `finger` | log-normal tepki, karakter karakter yazma |

`humanlike` önce enjektörü derler: `tools/bots/injector/build.sh` (JDK 17 + Android SDK build-tools 35).

Konum bilgisi: T1 hedef koordinatları debug build'in logcat marker'larından okunur (`adb logcat -s HS-MARK`, §9.1);
diğer öğeler resource-id / contentDescription ile bulunur (§4.3). Oturumu dışarıdan durdurmak (yalnızca debug build):

```bash
adb shell am broadcast -a io.humansignal.research.STOP -p io.humansignal.research
```

### Emülatörde gözlenen ayırt edici sinyaller (Supabase'teki oturumlardan, `npm run inspect`)

| | naive / jitter | humanlike |
|---|---|---|
| `tool` | `unknown` | `finger` |
| `dev` | -1 | **-1** (sistem enjekte olayın deviceId'sini korumuyor) |
| MOVE / dokunma (medyan) | 0 | 13 |
| DOWN→UP (medyan) | 0 ms | 86 ms |
| basınç (farklı değer) | 2 (0/1) | 427, sd 0.12 |

## Appium / UI Automator2 (`bot_tool = appium`) — `appium_bot.py`

```bash
npm i -g appium && appium driver install uiautomator2
python3 -m pip install Appium-Python-Client
appium &
python3 tools/bots/appium_bot.py [--host emulator_android_studio]
```

Aynı görev mantığı; dokunmalar W3C Actions ile, metin `send_keys` ile (tek seferde — `text.paste=true` beklenir).
**Durum:** yazıldı, bu makinede Appium kurulu olmadığı için henüz çalıştırılmadı.

## Matris (§9.3)

Her kombinasyon için en az 20 oturum. Gerçek cihaz / tutucu / elde satırları ve auto-clicker, accessibility, scrcpy
sütunları elle (uygulama + etiket seçimiyle) toplanır.
