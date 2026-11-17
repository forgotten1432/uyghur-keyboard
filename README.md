# Uygurca Klavye — ئۇيغۇرچە كۇنۇپكا تاختىسى

Android için Uygurca (Arap harfli) ve İngilizce klavye. **İnternetsiz çalışan Uygurca sesle yazma** özelliği var: 🎤'ye dokun, konuş; yazı sen konuşurken ekrana gelsin.

[![Android Native Build](https://github.com/forgotten1432/uyghur-keyboard/actions/workflows/main.yml/badge.svg)](https://github.com/forgotten1432/uyghur-keyboard/actions/workflows/main.yml)

## Özellikler

- **Uygurca Arap harfli düzen** ve **İngilizce QWERTY**. 🌐 tuşuyla aralarında geçilir; boşluk tuşunda o anki dil yazar.
- **Uzun basınca alternatif harfler** ve rakam satırı.
- **Kelime önerileri.** Yazdığın kelimeleri ve kelime sıralarını öğrenip sonraki sefer önerir. Uygurca kelime listesi `uyghur_words.txt` dosyasından güncellenir.
- **Sesle yazma (Uygurca):**
  - Telefonun üzerinde çalışır; ses hiçbir yere gönderilmez.
  - 🎤'ye bir kez dokun, konuşmaya başla. Bitirmek için ⏹'ye dokun.
  - Konuşurken metin altı çizili olarak belirir. Kısa bir duraklamada o cümle kesinleşir.
  - Tanınan Latin harfli metin (ULY) otomatik olarak Arap harfli Uygurcaya (UEY) çevrilir.

## Kurulum

1. [Releases](https://github.com/forgotten1432/uyghur-keyboard/releases) sayfasından en yeni `UyghurKeyboard.apk` dosyasını telefona indir ve kur. Android "bilinmeyen kaynak" izni isteyebilir.
2. **Uygurca Klavye** uygulamasını aç:
   - **Klavyeyi etkinleştir** → ayarlarda klavyeyi aç.
   - **Klavye seç** → Uygurca Klavye'yi seç.
   - **Mikrofon izni ver** → sesle yazma için gerekli.
3. Herhangi bir yazı alanında 🎤 tuşuna dokun.

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
  UyghurKeyboardService.kt   klavye (tuşlar, öneriler, sesle yazma döngüsü)
  SetupActivity.kt           kurulum ekranı
  DictionaryHelper.kt        kelime önerileri ve öğrenme
  KeyboardConstants.kt       tuş düzenleri
  AudioRecorder.kt           mikrofon kaydı
  AsrEngine.kt               modeli yükleme ve çalıştırma
  asr/                       Android'den bağımsız ses tanıma mantığı (testli)
    SpeechSegmenter.kt         canlı akışı cümlelere bölme
    CtcDecoder.kt              CTC beam search
    CharLanguageModel.kt       karakter 6-gram dil modeli
    UyghurScript.kt            Latin → Arap harfli Uygurca
    Resampler.kt               örnekleme hızı dönüştürme
app/src/main/assets/          model (uyghur_asr_int8.onnx) ve dil modeli
app/src/test/                 birim testleri
model/                        eğitim için tam hassasiyetli model (Git LFS)
tools/                        ölçüm, küçültme, dil modeli ve eğitim araçları
samples/                      örnek ses kayıtları
```

## Derleme

- **Telefondan:** GitHub → Actions → *Android Native Build* → *Run workflow*. Testler çalışır, APK derlenir ve otomatik olarak bir Release'e eklenir.
- **Bilgisayardan:** Android Studio ile aç ya da `./gradlew assembleDebug` çalıştır. Testler için `./gradlew testDebugUnitTest`.

## Katkı ve geri bildirim

Yanlış tanınan bir cümle mi buldun? [Yanlış tanıma bildir](https://github.com/forgotten1432/uyghur-keyboard/issues/new/choose) şablonunu kullan. Ne dediğini ve ne yazıldığını yazman yeterli; ses kaydı eklersen daha da iyi olur.

## Teşekkürler

- Ses modeli: THUYG-20 ile eğitilmiş CTC modeli ([gheyret/uyghur-asr-ctc](https://github.com/gheyret/uyghur-asr-ctc) ailesi)
- Veri: [THUYG-20](http://www.openslr.org/22/), [Mozilla Common Voice](https://datacollective.mozillafoundation.org)
- [ONNX Runtime](https://onnxruntime.ai)
