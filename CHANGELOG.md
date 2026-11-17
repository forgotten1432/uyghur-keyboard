# Değişiklik kaydı

## 1.1.0 — 2026-11-17

### Sesle yazma
- **Dokun-başlat / dokun-bitir:** 🎤 artık basılı tutulmuyor; bir dokunuşla başlıyor, ⏹ ile bitiyor.
- **Canlı yazı:** Konuşurken metin altı çizili olarak beliriyor ve ~0,7 saniyede bir güncelleniyor. Kısa bir duraklamada cümle kesinleşiyor. Durmadan 15 saniyeden uzun konuşulursa metin en sessiz noktadan bölünüyor.
- **Daha doğru tanıma** (THUYG-20'de kelime hata oranı %34 → %12):
  - Kayıt artık modelin eğitildiği 22050 Hz ile yapılıyor (eskiden 16 kHz).
  - Greedy çözme yerine CTC beam search ve karakter 6-gram dil modeli kullanılıyor.
  - Kesme işareti (`mu’ellim`) artık boşluk sanılmıyor. Arap harfine çeviride hemze (ئ) kuralları düzeltildi.
- **Daha küçük ve hızlı model:** int8, 279 → 80 MB, doğruluk aynı.
- Tanıma arka planda çalışıyor; klavye donmuyor.
- Model ilk açılışta henüz yüklenmemişse kayıt çöpe atılmıyor, yüklenmesi bekleniyor.
- Klavye kapanırsa kayıt duruyor; son cümle yine de yazılıyor.

### Düzeltmeler
- GitHub Actions'tan çıkan APK'da model yerine Git LFS işaretçi dosyası vardı; düzeltildi.
- Bozuk ya da eski model kopyası artık kalıcı olmuyor; boyut değişince yeniden kopyalanıyor.

### Proje
- Her Actions derlemesi APK'yı otomatik olarak bir Release'e ekliyor.
- Ses tanıma mantığı için birim testleri eklendi; her derlemede çalışıyor.
- Model eğitme hattı eklendi: `TRAINING.md`, Colab notebook'u ve `tools/` araçları.

## 1.0.0
- İlk sürüm: Uygurca/İngilizce klavye, kelime önerileri, basılı tutarak sesle yazma.
