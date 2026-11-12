# Veri Toplama Kılavuzu (Faz 1)

**Toplama başlangıcı: 2026-11-13.** Bu tarihten önceki oturumlar geliştirme/test oturumlarıdır; eğitim verisine
alınmaz (`npm run progress -- --since 2026-11-13`, ingest'te de aynı filtre).

## Başlamadan önce (bir kez)

- [ ] Rıza metni hukuki onaydan geçti, `consent_tr.md`'deki araştırma sorumlusu bilgileri dolduruldu, uygulama yeniden derlendi.
- [ ] Katılımcı kodu ↔ isim eşleşme tablosu **sistem dışında**, şifreli bir dosyada (ör. parola korumalı tablo).
- [ ] API ayakta: `https://humansignal-fawn.vercel.app/health` → `{"ok":true,"version":"1.0.0"}`.

## Telefon hazırlığı (her cihaz)

```bash
cd android && ./gradlew :research-app:assembleDebug
adb -s <SERİ_NO> install -r research-app/build/outputs/apk/debug/research-app-debug.apk
```

- APK'nın içinde uygulama anahtarı vardır: dosyayı herkese açık bir yere koymayın.
- İlk açılış: **Ayarlar** (adres ve anahtar dolu gelir) → **Kaydet** → **Rıza**.
- **Yeni bir telefon modeli** ilk kez kullanılıyorsa: Kurulum → Geliştirici → **Saat Testi** (bir oturum açıkken;
  telefonu masaya koyup 10 kez sertçe vurun). "GEÇTİ (< 20 ms)" görmeden o modelle veri toplamayın.
  Doğrulanmış modeller: Huawei YAL-L21 (medyan 9,5 ms).

## İnsan oturumu (katılımcı başına ~10 dk)

1. Rıza ekranı katılımcıya okutulur; iki kutuyu **katılımcı** işaretler.
2. **Oturum Kurulumu:** Etiket **İnsan** · katılımcı kodu (uygulama sıradakini önerir: P001, P002 …; aynı kişi sonraki
   oturumlarda aynı kodu kullanır) · ilk oturumda el tercihi / yaş aralığı / (isteğe bağlı) cinsiyet ·
   telefonun konumu, duruş, el kullanımı **gerçekte nasılsa** öyle seçilir.
3. Görevler T1 → T7 sırayla; katılımcı bir görevi istemezse **Atla**.
4. **Özet** ekranında "✓ Tüm veriler yüklendi" görünmeden telefonu bir sonraki kişiye vermeyin
   (internet yoksa veri telefonda şifreli bekler ve bağlantı gelince yüklenir).
5. Kod ↔ isim eşleşmesini şifreli tabloya yazın (sisteme **hiçbir zaman** isim girilmez).

Çeşitlilik hedefi: farklı yaş aralıkları, iki el kullanımı da, elde / masada; mümkünse farklı telefon modelleri.

## Bot oturumları (§9.3 matrisi — her hücre ≥ 20 oturum)

Uygulama **Oturum Kurulumu** ya da **Özet** ekranındayken (bilgisayarda, repo kökünde):

```bash
# adb (3 seviye); --host: emulator_android_studio | emulator_genymotion | emulator_bluestacks | real_device
python3 tools/bots/adb_bot.py -s <SERİ_NO> --mode naive     --host real_device --placement table
python3 tools/bots/adb_bot.py -s <SERİ_NO> --mode jitter    --host real_device --seed 1
tools/bots/injector/build.sh   # humanlike için bir kez
python3 tools/bots/adb_bot.py -s <SERİ_NO> --mode humanlike --host real_device --seed 1
```

- Her çalıştırmada farklı `--seed` verin (aynı tohum aynı hareketleri üretir).
- Gerçek cihaz satırları telefonun **konumuyla** ayrılır: `--placement table | holder | hand` (telefon gerçekten
  orada olmalı; "elde" satırı = bir kişi telefonu tutarken script dokunur — bankacılık zararlısı senaryosu).
- Appium: `tools/bots/README.md` (kurulum gerekli, henüz denenmedi).
- Auto-clicker, accessibility uygulaması, scrcpy (uzaktan insan): Kurulum'da **Bot** + ilgili araç + host seçilir,
  görevler o araçla yapılır.
- Oturumu dışarıdan bitirmek (yalnızca debug build): `adb shell am broadcast -a io.humansignal.research.STOP -p io.humansignal.research`

## İzleme

```bash
cd api
npm run progress -- --since 2026-11-13     # insan/bot dağılımı, §9.3 matrisi, tamamlanmamış oturumlar
npm run inspect -- <session_id>            # tek oturum: parçalar, Hz, dokunma özeti, gizlilik taraması
```

"NOT COMPLETE" listesinde uzun süre kalan oturum: telefonda internet yok ya da yükleme hatası — o telefonda uygulamayı
açın, Özet/Canlı Sensör ekranındaki yükleme durumuna bakın.

## Silme talebi (KVKK)

Katılımcı kodu ile, admin anahtarıyla: `DELETE /v1/admin/participants/<KOD>` — tüm oturumları ve Storage dosyaları silinir,
`audit_log`'a kişisel veri içermeyen bir kayıt düşer. Şifreli eşleşme tablosundan da satırı silin.

## Bilinen sınırlamalar

- SDK gerçek cihazda tek çekirdeğin ~%19'unu kullanıyor (hedef %5) — veri kalitesini etkilemez; Faz 2 öncesi optimize edilecek (`android/PERF.md`).
- Dialog/popup içindeki dokunmalar yakalanmaz (§3.5) — görevler bu yüzden tam ekrandır.
- Huawei basıncı 0–1 aralığında değil; modelde basınç cihaz başına normalize edilmeli.
