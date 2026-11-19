# Modeli eğitmek (ince ayar)

## Neden eğitmek gerekiyor?

Klavyedeki model sadece **THUYG-20** ile eğitilmiş. Bu veri yaklaşık 20 saat, az sayıda konuşmacı ve stüdyo kaydından oluşuyor.
Bu yüzden THUYG test cümlelerinde iyi çalışıyor (beam + dil modeliyle WER ~%12). Telefon mikrofonunda ve farklı seslerde ise belirgin şekilde zayıflıyor.

**Common Voice Uygurca** yüzlerce saatlik ve 1800'den fazla konuşmacılı bir veri seti. Kayıtlar çoğunlukla telefon ve laptop mikrofonuyla yapılmış.
Modeli bununla eğitmeye devam etmek, en büyük iyileşmeyi verecek adım.

## Ne hazır?

| Dosya | Görevi |
|---|---|
| `model/uyghur_asr_fp32.onnx` | Mevcut modelin tam hassasiyetli hali. Eğitim buradan başlar (Git LFS). |
| `tools/train/model_from_onnx.py` | ONNX modeli PyTorch'ta birebir yeniden kurar. Çıktılar ONNX ile aynıdır (fark ~1e-4). |
| `tools/train/uyghur_train.py` | Arapça → Latin çevirici, veri yükleyici, gürültü ekleme, eğitim döngüsü, CER/WER ölçümü |
| `tools/train/finetune_colab.ipynb` | Baştan sona Colab notebook'u |
| `tools/quantize_int8.py` | Modeli 279 MB'tan 80 MB'a küçültür; doğruluk değişmez |
| `tools/export_char_lm.py` | Metinden karakter dil modeli kurar |
| `tools/evaluate.py` | Bir modelin CER/WER değerini ölçer |

Hepsi burada küçük ölçekte denendi:
- PyTorch ↔ ONNX çıktıları aynı.
- 48 cümlelik CPU denemesinde kayıp düştü.
- Dışa aktarılıp int8'e çevrilen model uygulamadaki modelle aynı kalitede.

## Adımlar

1. **Common Voice'u indir:** https://datacollective.mozillafoundation.org adresinde hesap aç, *Common Voice Scripted Speech – Uyghur* setini indir (birkaç GB, `.tar.gz`). Google Drive'da `uyghur_asr/` klasörüne `cv_ug.tar.gz` adıyla yükle.
2. **Colab'ı aç:** https://colab.research.google.com → *GitHub* sekmesi → `forgotten1432/uyghur-keyboard` → `tools/train/finetune_colab.ipynb`. Çalışma zamanını **T4 GPU** yap.
3. **Hücreleri sırayla çalıştır.** Eğitim hücresi en fazla 3 saat sürer ve en iyi modeli Drive'a kaydeder.
   - Oturum düşerse aynı hücreleri tekrar çalıştır; Drive'daki checkpoint'ten devam eder.
   - Sonraki oturumlarda `SESSION = 1, 2, …` yap. Böylece Common Voice'un farklı bir parçası kullanılır.
4. **Ölç (8. hücre):** Yeni modelin WER'i eskisinden düşük değilse uygulamaya koyma.
5. **Uygulamaya koy (9. hücre):** Bir GitHub token'ı ile `model-update` branch'ine push et. Sonra Actions → *Run workflow* ile APK'yı derle.

Colab yerine **Kaggle Notebooks** da kullanılabilir. Orada haftalık ücretsiz GPU kotası var ve oturumlar daha uzun sürüyor. Aynı kodu küçük yol değişiklikleriyle çalıştırabilirsin (`/content` yerine `/kaggle/working`).
Mac mini (M1) eğitim için çok yavaş kalır; orada sadece değerlendirme ve dil modeli işleri yapılabilir.

## En çok fayda sağlayan 3 şey

1. **Kendi sesin:** 100–300 cümleyi telefonla kendin oku (notebook'taki 3c adımı). Model senin sesine ve mikrofonuna alışır. Az veriyle en büyük kazanç budur.
2. **Daha çok metin (dil modeli):** `export_char_lm.py` her satırı bir cümle olan herhangi bir Latin Uygurca metni kabul eder. Arapça metinler için önce `uey_to_uly()` ile çevir. Haber ve kitap metinleri eklemek, ses verisi olmadan da hata oranını düşürür.
3. **Gürültü:** Eğitimde rastgele gürültü ve hız değişimi zaten açık. Gürültülü ortamda kullanıyorsan gerçek arka plan kayıtlarını (`augment_wave`) eklemek fayda sağlar.

## Teknik notlar

- Model 22050 Hz ses bekler. Uygulama bu hızda kaydeder; cihaz desteklemiyorsa başka bir hızda kaydedip dönüştürür.
- Çıktı alfabesi Latin Uygurca (ULY): `a…z, é, ö, ü, ’` ve boşluk. Arapça yazıya çeviri uygulamada yapılır (`UyghurScript.kt`).
- int8'e çevirirken mel ön-işleme katmanı float bırakılmalı. Yoksa model tamamen bozulur (WER ~%99). `quantize_int8.py` bunu zaten yapıyor.
- Dil modeli ağırlıkları (`alpha=0.3`, `beta=1.0`) THUYG üzerinde ayarlandı. Büyük bir dil modeli kurarsan `evaluate.py --alpha … --beta …` ile tekrar dene ve `CtcDecoder.kt` içindeki değerleri güncelle.

## Denenenler ve sonuçlar (2026-11-18)

Common Voice 27.0 Uygurca ile Colab T4'te denendi. Ölçüm: 200 Common Voice + 200 THUYG test kaydı, kelime hatası (WER).

- **İnce ayar kazanç vermedi.** `lr=5e-5` modeli bozdu (CV dev %12 → %16). `lr=1e-5` ile bir tur sonunda test setleri yerinde saydı (CV %14,7 → %15,3, THUYG %17,7 → %17,5; dil modelsiz). Mevcut model Common Voice'un dev kısmında test kısmından çok daha iyi; büyük olasılıkla Common Voice'un bir kısmını zaten görmüş. Daha çok kazanç için asıl yol kendi sesinle kayıt (yukarıdaki 1. madde).
- **Dil modeli en büyük kazancı verdi.** Common Voice'un bütün cümleleriyle kurulan dil modeli (test cümleleri hariç, budama 8) uygulamanın kod çözücüsüyle CV testinde WER'i %14,5'ten %10,9'a indirdi. Eski modelin THUYG test sonucu (%8,8) güvenilir değil; o dil modeli THUYG cümlelerini içeriyor. Bedeli: klavyenin Java belleği emülatörde 62 MB'tan 84 MB'a çıkıyor (135 bin bağlam; budamasız sürüm 213 bin bağlam olurdu, CV WER %10,7).
- **int8 ARM'da sorunsuz, x86'da değil.** Telefonla aynı ARM işlemcide (Mac M1) int8 model fp32'ye çok yakın (CV %15,4'e karşı %14,9). Colab'ın x86 işlemcisinde aynı int8 model %21,7 veriyor; bu, x86'ya özgü bir int8 hesaplama farkı. **int8 modeli Colab'da ölçme**, fp32 ile ya da ARM bir makinede ölç.
- BatchNorm istatistikleri sorun değil: ağırlıklar sabitken eğitim verisi geçirmek WER'i değiştirmedi.
- Dinamik int8 (yalnız ağırlıklar) çok yavaş; telefona uygun değil.

## Hazır alternatifler (eğitmek istemezsen)

- **Whisper-small Uygurca** (ixxan, Hugging Face): daha güçlü olabilir ama telefonda daha ağır çalışır (whisper.cpp gerekir).
- **SenseVoice Uygurca int8** (sc666, Hugging Face): sherpa-onnx tabanlı Android demosu var ve 118 saat Common Voice ile eğitilmiş. Erişim için izin istemek gerekiyor.
