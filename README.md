# Uygurca Klavye (Android)

Uygurca/İngilizce Android klavyesi. Cihaz üzerinde, internetsiz çalışan Uygurca konuşma tanıma içerir: 🎤 tuşuna basılı tut, konuş, bırak.

## Konuşma tanıma nasıl çalışıyor?

```
mikrofon (22050 Hz) → ONNX model (int8, 80 MB) → CTC beam search + karakter dil modeli → Latin (ULY) → Arapça yazı
```

| | Kelime hata oranı (THUYG-20, 300 test cümlesi) |
|---|---|
| Eski sürüm (16 kHz kayıt, greedy) | %34,2 |
| 22050 Hz kayıt | %15,8 |
| + beam search + dil modeli | **%11,6** |
| + int8 model (279 → 80 MB, ~3× hızlı) | %12,0 |

Modeli kendi verilerinle (Common Voice, kendi sesin) daha da iyileştirmek için: **[TRAINING.md](TRAINING.md)**

## Derleme

GitHub Actions → *Android Native Build* → *Run workflow*. APK, *Artifacts* altında çıkar.
Uygulamanın modeli (`app/src/main/assets/`) normal git dosyası olarak duruyor. Eğitim için gereken büyük model (`model/uyghur_asr_fp32.onnx`) ise Git LFS'te.

## Kaynaklar

- Ses modeli: THUYG-20 ile eğitilmiş CTC modeli ([gheyret/uyghur-asr-ctc](https://github.com/gheyret/uyghur-asr-ctc) ailesi)
- Veri: [THUYG-20](http://www.openslr.org/22/), [Common Voice](https://datacollective.mozillafoundation.org)
