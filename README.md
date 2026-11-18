# Uygurca Klavye — ئۇيغۇرچە كۇنۇپكا تاختىسى

Android için Uygurca (Arap harfli) ve İngilizce klavye. **İnternetsiz çalışan Uygurca sesle yazma** özelliği var: 🎤'ye dokun, konuş; yazı sen konuşurken ekrana gelsin.

[![Android Native Build](https://github.com/forgotten1432/uyghur-keyboard/actions/workflows/main.yml/badge.svg)](https://github.com/forgotten1432/uyghur-keyboard/actions/workflows/main.yml)

## Özellikler

- **Samsung/Gboard tarzı görünüm ve his:**
  - Telefonun açık/koyu temasını izler.
  - Tuşlar arasında ölü bölge yok; iki başparmakla hızlı yazılır.
  - Basınca harf baloncuğu çıkar. Uzun basınca alternatifler menüsü açılır.
  - Silme tuşu basılı tutulunca hızlanır ve kelime kelime siler.
  - Boşluk tuşunda parmağı kaydırınca imleç hareket eder.
  - İki kez boşluk ". " yazar.
  - Enter tuşu uygulamaya göre "ئىزدە / يوللا / تامام" olur.
- **Diller:**
  - **Uygurca standart düzen** (Windows/UKIJ ile aynı tuş yerleri). Shift ile ژ ف گ خ ج ۆ gelir.
  - **Türkçe Q** ve **İngilizce QWERTY**.
  - 🌐 tuşu diller arasında geçer; uzun basınca sistemin klavye seçicisi açılır.
  - Üst sıradaki tuşlara uzun basınca rakamlar çıkar. İstersen sayı satırını ayarlardan açabilirsin.
- **Sayı alanlarında hesap makinesi tipi tuş takımı** (sayı, tutar, telefon, tarih).
- **Kelime ve cümle tahmini (Uygurca, Türkçe, İngilizce):**
  - Sözlüklerle kelime tamamlama: Uygurca 150 bin, Türkçe 50 bin, İngilizce 30 bin kelime.
  - Son iki kelimeye bakarak sonraki kelimeyi tahmin etme. Sık yazdığın ifadeleri (ör. ئەسسالامۇ ئەلەيكۇم) ve cümleye nasıl başladığını öğrenir.
  - Yanlış basılan komşu tuşları ve ى/ې, ۇ/ۈ gibi karışan harfleri düzeltme.
  - Yazdıklarını öğrenir ve sonraki sefer onları öne çıkarır.
  - Şifre alanlarında öneri yapmaz ve öğrenmez.
- **Tamamen çevrimdışı:** uygulamanın internet izni yok.
- **Ayarlar:** uygulamayı açarak ya da öneri şeridindeki ⚙ ile. Sayı satırı, diller, tuş sesi, titreşim, kelime önerileri.
- **Sesle yazma (Uygurca):**
  - Telefonun üzerinde çalışır; ses hiçbir yere gönderilmez.
  - 🎤'ye bir kez dokun, konuşmaya başla. Bitirmek için ⏹'ye dokun.
  - Konuşurken metin altı çizili olarak, bir kelime geriden belirir. ~1 saniyelik duraklamada o cümle kesinleşir; kelime aralarındaki kısa düşünme duraklamaları cümleyi bölmez.
  - Tanınan Latin harfli metin (ULY) otomatik olarak Arap harfli Uygurcaya (UEY) çevrilir.

## Kurulum

1. [Releases](https://github.com/forgotten1432/uyghur-keyboard/releases) sayfasından en yeni `UyghurKeyboard.apk` dosyasını telefona indir ve kur. Android "bilinmeyen kaynak" izni isteyebilir.
2. **Uygurca Klavye** uygulamasını aç ve üç adımı tamamla:
   - **Klavyeyi etkinleştir:** ayarlarda klavyeyi aç.
   - **Klavyeyi seç**
   - **Mikrofon izni ver:** sesle yazma için gerekli.
3. Sesle yazmak için öneri şeridinin solundaki mikrofona dokun.

> Daha önce başka bir derlemeyi kurduysan ve yeni sürüm "paket çakışıyor" hatası verirse, önce eskisini kaldır. Actions derlemeleri her seferinde farklı bir debug anahtarıyla imzalanıyor.

## Ses tanıma kalitesi

THUYG-20 veri setinde, eğitimde kullanılmamış 300 cümleyle ölçüldü (kelime hata oranı, düşük daha iyi):

| Sürüm | WER |
|---|---|
| 1.0 (16 kHz kayıt, greedy çözme) | %34,2 |
| 22050 Hz kayıt | %15,8 |
| + beam search + karakter dil modeli | %11,6 |
| 1.1: + int8 model (279 → 80 MB, ~3× hızlı) | %12,0 |

Bu sayılar stüdyo kayıtlarına ait; telefon mikrofonunda ve gürültülü ortamda daha yüksek olur. Modeli kendi sesinle ve Common Voice ile iyileştirmek için: **[TRAINING.md](TRAINING.md)**

## Nasıl çalışıyor?

```
mikrofon (22050 Hz) → duraklama algılama → ONNX model (int8) → beam search + dil modeli → ULY → Arap harfli Uygurca
```

Ayrıntılar için: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Proje yapısı

```
app/src/main/java/com/uyghur/keyboard/
  UyghurKeyboardService.kt   yazma mantığı, öneriler, sesle yazma döngüsü
  SetupActivity.kt           kurulum ve ayarlar ekranı
  KeyboardSettings.kt        ayarlar
  keyboard/                  görünüm (KeyboardView), tuş düzenleri, dokunma geometrisi, tema
  predict/                   kelime tahmini (sözlük, düzeltme, sonraki kelime, öğrenme)
  AudioRecorder.kt           mikrofon kaydı
  AsrEngine.kt               modeli yükleme ve çalıştırma
  asr/                       Android'den bağımsız ses tanıma mantığı (testli)
    SpeechSegmenter.kt         canlı akışı cümlelere bölme
    CtcDecoder.kt              CTC beam search
    CharLanguageModel.kt       karakter 6-gram dil modeli
    UyghurScript.kt            Latin → Arap harfli Uygurca
    Resampler.kt               örnekleme hızı dönüştürme
app/src/main/assets/          ses modeli, dil modeli, sözlükler
app/src/test/                 birim testleri ve ekran görüntüsü testleri (Paparazzi)
model/                        eğitim için tam hassasiyetli model (Git LFS)
tools/                        ölçüm, küçültme, dil modeli ve eğitim araçları
samples/                      örnek ses kayıtları
```

## Derleme

- **Telefondan:** GitHub → Actions → *Android Native Build* → *Run workflow*. Testler çalışır, APK derlenir ve otomatik olarak bir Release'e eklenir. Klavyenin ekran görüntüleri de çizilip [`screenshots`](https://github.com/forgotten1432/uyghur-keyboard/tree/screenshots) branch'ine konur.
- **Bilgisayardan:** Android Studio ile aç ya da `./gradlew assembleDebug` çalıştır. Testler için `./gradlew testDebugUnitTest`.

## Katkı ve geri bildirim

Yanlış tanınan bir cümle mi buldun? [Yanlış tanıma bildir](https://github.com/forgotten1432/uyghur-keyboard/issues/new/choose) şablonunu kullan. Ne dediğini ve ne yazıldığını yazman yeterli; ses kaydı eklersen daha da iyi olur.

## Teşekkürler

- Ses modeli: THUYG-20 ile eğitilmiş CTC modeli ([gheyret/uyghur-asr-ctc](https://github.com/gheyret/uyghur-asr-ctc) ailesi)
- Veri: [THUYG-20](http://www.openslr.org/22/), [Mozilla Common Voice](https://datacollective.mozillafoundation.org)
- Uygurca sözlük: [Uyghur-LRs/Uyghur-Wordlist](https://github.com/Uyghur-LRs/Uyghur-Wordlist), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Web kelime sıklıkları yazım listeleriyle süzüldü (`tools/build_dictionary.py`).
- İngilizce ve Türkçe kelime sıklıkları: [wordfreq](https://github.com/rspeer/wordfreq). Veri [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); `english_dict.txt` ve `turkish_dict.txt` de bu lisansla dağıtılır.
- [ONNX Runtime](https://onnxruntime.ai)
