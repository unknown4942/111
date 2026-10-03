# Jarvis — Android sesli asistan (sürüm 0.2)

Telefonunuzu Türkçe sesli komutla veya yazarak kontrol eden kişisel asistan.
Yapay zekâ olarak **Gemini (ücretsiz)** veya **Claude** seçilebilir.

## Neler yapabiliyor?

| Komut örneği | Ne olur |
|---|---|
| "Annemi ara" | Rehberde bulur, ekranda onay ister, arar |
| "Ahmet'e akşam geç kalacağım diye mesaj at" | Numarayı bulur, mesajı gösterip onay ister, SMS gönderir |
| "WhatsApp'ı aç" | Yüklü uygulamayı adıyla açar |
| "Yarın sabah 7'ye alarm kur" / "10 dakikalık zamanlayıcı" | Saat uygulamasında alarm/zamanlayıcı kurar |
| "Feneri aç" | Feneri açar/kapatır |
| "Sonraki şarkı", "Sesi kıs" | Medya ve ses kontrolü |
| "Wi-Fi ayarlarını aç" | İlgili ayar panelini açar (Android, uygulamaların Wi-Fi/Bluetooth'u doğrudan açıp kapatmasına izin vermez) |
| "Kadıköy'e yol tarifi" | Haritalarda navigasyon başlatır |
| "Şarjım kaç?" | Pil durumu, tarih ve saat |
| "İstanbul'da yarın hava nasıl?" | Web'de arar ve cevaplar |

Arama ve SMS gibi geri alınamaz işlemler **her zaman ekranda onayınızı ister.**

Ana ekran tuşuna uzun basınca açılmasını isterseniz: *Ayarlar → Uygulamalar → Varsayılan uygulamalar → Dijital asistan uygulaması* bölümünden Jarvis'i seçin (bu seçenek her telefonda bulunmayabilir).

## Kurulum

### 1. API anahtarı alın
İkisinden birini seçin:

- **Gemini (ücretsiz):** [aistudio.google.com/apikey](https://aistudio.google.com/apikey) adresinden Google hesabınızla ücretsiz anahtar alın (`AIza...`). Ücretsiz kullanımın günlük sınırları vardır ve Google ücretsiz katmandaki konuşmaları ürünlerini geliştirmek için kullanabilir.
- **Claude:** [console.anthropic.com](https://console.anthropic.com/settings/keys) adresinden anahtar oluşturun (`sk-ant-api03-...`). Kullanım ücretlidir; hesaba kredi yüklemek gerekir.

### 2. Uygulamayı derleyin
Gereken: [Android Studio](https://developer.android.com/studio) (içinde JDK ve Android SDK gelir).

- Android Studio → **Open** → bu `jarvis-android` klasörünü seçin.
- Telefonunuzda *Geliştirici seçenekleri → USB hata ayıklama*'yı açıp telefonu bağlayın.
- **Run ▶** düğmesine basın.

Komut satırından:

```bash
cd jarvis-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 3. İlk açılış
- İstenen izinleri verin (mikrofon, rehber, arama, SMS).
- **Ayarlar** düğmesinden yapay zekâyı (Gemini/Claude) seçip API anahtarınızı girin.
- Mikrofona dokunup konuşun.

## Yapı

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt   Ekran: sohbet, mikrofon, ayarlar, onay pencereleri
├── Assistant.kt      Ortak arayüz ve sistem talimatı
├── GeminiAgent.kt    Gemini ile konuşma döngüsü (araç çağrıları dahil)
├── ClaudeAgent.kt    Claude ile konuşma döngüsü (araç çağrıları dahil)
├── PhoneTools.kt     Telefon araçları: arama, SMS, uygulama, alarm, fener…
└── Voice.kt          Türkçe ses tanıma ve sesli okuma
```

Yeni bir yetenek eklemek için `PhoneTools.kt` içinde `specs` listesine bir araç tanımı ekleyin ve `execute` içinde karşılığını yazın.

## Güvenlik notları

- API anahtarı yalnızca telefonda, uygulamaya özel alanda saklanır. Anahtarı kimseyle paylaşmayın, koda gömmeyin.
- SMS göndermek ve arama yapmak her seferinde onay gerektirir.

## Yol haritası

- **Sürüm 2:** Erişilebilirlik servisi ile başka uygulamaların ekranında gezinme (ör. "WhatsApp'ta Ali'ye yaz"), bildirimleri okuma.
- **Sürüm 3:** Uzaktan kontrol (yalnızca sizin hesabınızı kabul eden Telegram botu veya kendi sunucunuz).
- **iOS:** Apple başka uygulamaları kontrol etmeye izin vermediği için Siri Kısayolları ile çalışan, daha sınırlı bir eşlik uygulaması.
