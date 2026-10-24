# Jcrw Translator for Android TV

A separate companion app that captures the Android TV display with the user's consent, reads a chosen subtitle rectangle with OCR, translates recognized text, and displays a caption overlay. It does not modify or bundle Chiaki or PlayStation Remote Play. It cannot capture HDMI input through the TV's protected video path; it works only when Android's screen capture API can see the playing app.

## Önemli: Translator isteğe bağlıdır

**PS5'e bağlanıp Remote Play oynamak için yalnızca Jcrw Chiaki APK'sını kurmanız yeterlidir. Translator zorunlu değildir ve Chiaki'nin parçası değildir.** Translator'ı yalnızca altyazıları OCR ile okuyup çevirmek, ekranda göstermek veya seslendirmek istiyorsanız ayrı APK olarak kurun. Chiaki ile ya da Android'in ekran görüntüsünü yakalayabildiği başka bir Remote Play uygulamasıyla birlikte kullanılabilir; HDMI girişini okuyamaz.

Chiaki ve Translator APK'ları GitHub Releases sayfasında iki ayrı indirme dosyasıdır. Sadece istediğiniz uygulamayı kurabilirsiniz.

## Current implementation

- Android MediaProjection capture, with Android's per-session consent dialog.
- User-adjustable source rectangle using TV remote D-pad (short OK toggles move/resize; Back saves).
- ML Kit and Tesseract OCR options; processing width from 96 to 720 px.
- DeepL, OpenAI, Gemini, and LibreTranslate text translation providers.
- Translation API key storage encrypted with Android Keystore.
- Caption overlay, duplicate-text suppression, and optional sequential Android/Gemini speech.
- Separate app package (`com.jcrw.translator.tv`) so the existing Chiaki installation/settings remain separate.
- Optional project-support panel with amount-specific Papara QR codes (₺10, ₺20, ₺50, ₺100, ₺200); support is voluntary and never unlocks features.

## Install and use

1. Install `app/build/outputs/apk/debug/app-debug.apk` on Android TV (ADB or USB sideload).
2. Open **Jcrw Çeviri**, accept the first-run notice, and grant the overlay permission.
3. Choose the subtitle rectangle and OCR/translation settings. Enter the API key for the selected online provider; LibreTranslate may work only with a reachable server.
4. Select **Çeviriyi başlat** and accept Android's screen-capture prompt.
5. Switch to Chiaki or Remote Play. Use the app's notification or return to Jcrw to stop capture.

Capture cadence and OCR accuracy depend on the TV hardware and screen-capture implementation. Streaming video, DRM-protected surfaces, or `FLAG_SECURE` apps can appear blank to Android screen capture. This does not read HDMI input.

## Licensing and source notices

`SubtitleTranslator.kt` and `GeminiSpeechSynthesizer.kt` are modified from Chiaki Android code and retain its `LicenseRef-AGPL-3.0-only-OpenSSL` notice. The applicable license text is in `LICENSES/AGPL-3.0-only-OpenSSL.txt`; see the upstream Chiaki project for source and license history. Other project files and dependencies retain their own licenses. Do not distribute a modified binary without meeting applicable source and notice requirements.

The first-run notice is informational and does not replace the licenses governing included code or third-party API terms.

Upstream Chiaki-derived source: https://github.com/streetpea/chiaki-ng. The installed app includes the relevant AGPL license text under **Açık kaynak lisansları**.
