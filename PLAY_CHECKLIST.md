# Google Play checklist — WiFi Usage

1. Keystore (once, on your PC): keytool -genkey -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   Then base64 it (certutil -encode / base64) and add GitHub repo secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. Keep upload.jks safe forever.
2. Actions → "Build release AAB" → Run workflow → download wifi-usage-aab → upload in Play Console.
3. Host privacy-policy.md (GitHub Pages) and paste the URL; fill in your email inside it.
4. Data safety form: no data collected or shared (everything stays on device, no INTERNET permission).
5. Permissions declarations:
   - Foreground service (location + special use): "continuous Wi-Fi data usage tracking the user turns on; shows a persistent notification".
   - Usage access (PACKAGE_USAGE_STATS): core feature = per-network/per-app Wi-Fi data usage.
   - Location: only to read the Wi-Fi network name (SSID), not GPS tracking.
6. Store listing: icon 512 (play-store-icon-512.png), feature graphic 1024x500, 2-8 phone screenshots (take from the app).
7. Target API: Play needs API 36 for new apps/updates from 31 Aug 2026 (extension to 1 Nov 2026 possible). Currently 35 → upgrade AGP/Gradle before submitting.
