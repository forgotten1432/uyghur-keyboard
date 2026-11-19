# Değişiklik kaydı

## 1.3.2 — 2026-11-18

- **Türkçe ve İngilizce cümle tahmini:** boşluktan sonra sonraki kelime artık bu dillerde de öneriliyor (örn. "bugün hava" → güzel / çok / soğuk, "thank" → you). Kelime çiftleri Tatoeba cümlelerinden (CC BY 2.0 FR) çıkarıldı: Türkçe 80 bin, İngilizce 117 bin çift. Üretmek için `tools/build_latin_bigrams.py`.
- **Öneriler açılışta hemen geliyor:** sözlükler artık sırayla değil aynı anda yükleniyor, açık olan dil önce. Eskiden Türkçe/İngilizce öneriler, Uygurca sözlüğün yüklenmesini beklediği için ilk 30 saniye kadar hiç çıkmıyordu.

- **Sesle yazmada daha az hata:** dil modeli THUYG-20'nin yanında Common Voice 27.0'ın 220 bin Uygurca cümlesiyle yeniden kuruldu. Common Voice test kayıtlarında kelime hatası %14,5'ten %10,9'a indi (test cümleleri dil modeline alınmadı). Ses modeli değişmedi: Common Voice ile ince ayar denendi, test setlerinde kazanç vermedi (ayrıntılar TRAINING.md'de).

## 1.3.1 — 2026-11-17

- **Ses tanımada sözlük bonusu:** tanıma, 154 bin Uygurca kelimelik sözlükte olan kelimeleri biraz daha fazla tercih ediyor. THUYG-20 test kümesinde kelime hatası %12,0'dan %11,6'ya indi.
  - Daha büyük bir dil modeli, kelime sınırı bonusu ve daha geniş arama da denendi; ek kazanç ölçüm gürültüsü düzeyinde kaldı.
  - Kalan hataların çoğu akustik (özel isimler, nadir kelimeler, ünlüler). Bunu düşürmenin yolu modeli daha çok veriyle eğitmek (TRAINING.md).
- **Emülatör testi:** her derlemede gerçek Android 14 emülatöründe klavye kurulup tuşlara basılarak deneniyor. Ekran görüntüleri `emulator-shots` branch'inde.

## 1.3.0 — 2026-11-17

- **Türkçe klavye:** Türkçe Q düzeni (ğ ü ş ı i ö ç) ve 50 bin kelimelik Türkçe sözlükle kelime tahmini. I/ı ve İ/i doğru işleniyor. 🌐 tuşu Uygurca → Türkçe → İngilizce arasında geçiyor; Türkçe ve İngilizce ayarlardan kapatılabiliyor.
- **Cümle tahmini ve alışkanlık (bütün dillerde):**
  - Tahmin son iki kelimeye bakıyor; sık yazdığın ifadeleri ezberliyor. Örn. ئەسسالامۇ'dan sonra ئەلەيكۇم, ardından قانداق.
  - Cümleye nasıl başladığını da öğreniyor: boş alanda ilk kelimeni öneriyor.
- **Hesap makinesi tipi tuş takımı:** sayı, tutar, telefon ve tarih alanlarında büyük tuşlu 4×4 tuş takımı açılıyor. Telefon takımında 0'a uzun basınca +, - tuşunda ( ) / . var.
- **Ayarlara klavyeden ulaşım:** öneri yokken şeridin sağında ⚙ çıkıyor. Sayı satırı, diller, ses ve titreşim oradan değiştirilebiliyor.

## 1.2.0 — 2026-11-17

### Yeni klavye görünümü (Samsung/Gboard tarzı)
- Tüm klavye tek bir görünümde çiziliyor. Tuşlar arasında ölü bölge yok; her dokunuş en yakın tuşa gidiyor.
- Telefonun açık/koyu teması izleniyor. Tuşlar yuvarlatılmış; simgeler (shift, silme, enter, dil, mikrofon) tek renk vektör.
- Basınca harf baloncuğu çıkıyor. Uzun basınca alternatifler menüsü açılıyor; parmağı kaydırıp seçiliyor.
- Üst sıradaki tuşlara uzun basınca rakamlar geliyor. Sayı satırı ayarlardan açılabiliyor.
- Uygurca shift katmanında ژ ف گ خ ج ۆ var (standart düzen); eskiden yalnız uzun basmayla yazılabiliyordu. İngilizcede tek dokunuş tek büyük harf, çift dokunuş büyük harf kilidi.
- Sembol tuşu iki sayfa sembol açıyor; Uygurcada ؟ ؛ ، « » dahil.

### Yazma hissi
- İki başparmakla hızlı yazma: yeni parmak dokunduğunda önceki tuş hemen yazılıyor.
- Silme tuşu basılı tutulunca hızlanıyor; 1,5 saniyeden sonra kelime kelime siliyor.
- Boşluk tuşunda parmağı sağa/sola kaydırınca imleç hareket ediyor.
- İki kez boşluk ". " yazıyor. Öneri seçince boşluk otomatik ekleniyor; ardından noktalama yazılırsa boşluk noktalamanın arkasına geçiyor.
- Enter tuşu uygulamaya göre değişiyor (ara, gönder, sonraki, tamam).
- İngilizcede cümle başı otomatik büyük harf.
- Sayı ve telefon alanlarında rakam/sembol sayfası açılıyor. Şifre alanlarında öneri ve öğrenme kapalı.
- 🌐'ye uzun basınca sistemin klavye seçicisi açılıyor.

### Kelime tahmini
- 150 bin kelimelik Uygurca sözlük (önceden ~60 kelime) ve 30 bin kelimelik İngilizce sözlük.
- Yazım hatası düzeltme: komşu tuşlar ve karışan harfler (ى/ې, ۇ/ۈ/ۆ, ك/گ …).
- Boşluktan sonra sonraki kelime tahmini.
- Öğrenilen kelimeler ve kelime çiftleri öne çıkıyor. Eski sürümde öğrenilen kelimeler aktarılıyor. Kayıt artık her tuşta değil, birkaç saniyede bir arka planda yapılıyor.
- Öneri şeridi: en iyi tahmin ortada ve kalın; yazdığın kelime ile ikinci tahmin yanlarda.

### Diğer
- Uygulamanın internet izni kaldırıldı; klavye tamamen çevrimdışı.
- Kurulum ekranı yenilendi: adım durumları, ayarlar (sayı satırı, ses, titreşim, öneriler) ve deneme alanı.
- Paparazzi ile ekran görüntüsü testleri eklendi. Actions her derlemede klavyeyi çizip `screenshots` branch'ine koyuyor.

## 1.1.1 — 2026-11-17

### Sesle yazma
- **Cümle daha geç kesinleşiyor:** 0,5 yerine ~1 saniyelik duraklamada. Kelime arasındaki kısa düşünme duraklamaları artık cümleyi bölmüyor. Duraklamalı konuşma testinde kelime hata oranı %19,5'ten %17,1'e indi.
- **Kelime sonları kırpılmıyor:** konuşmanın sonundan 0,4 saniye fazladan alınıyor (eskiden 0,2).
- **Ara metin bir kelime geriden geliyor:** henüz söylenmekte olan son kelime gösterilmiyor. Gösterilen kelimelerin sonradan değişme oranı %12'den %6'ya indi.

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
