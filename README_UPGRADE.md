# SparkTube v1.7.9 — `:ytauth` Library: Automatic Google Sign-in + Auto-Playing Age-Restricted Videos (`versionCode = 32`)

## What's New
A dedicated in-repo library module **`:ytauth`** (zero external dependencies) now handles
Google login end-to-end and feeds the session straight into the player:

1. **`Totp` (RFC 6238)** — generates 2FA verification codes from the account's Base32
   secret with a compact built-in Base32 decoder (HMAC-SHA1, 6 digits, 30 s step).
2. **`SecureStore`** — Android-Keystore-backed AES-256/GCM key-value store; Google
   credentials never touch the repo, the APK, or plain prefs.
3. **`GoogleLoginEngine`** — headless WebView that signs in exactly like a browser:
   email → password → 2FA (TOTP auto-filled from the secret) → redirect to YouTube →
   captures the full session cookie set (SID/HSID/SSID/SAPISID/__Secure-1PSID/
   __Secure-3PSID/…). Stage machine handles "Choose an account", bad-password and
   phone-approval challenges with clear typed failures.
4. **Auto-Playing Age-Restricted Videos** — when an 18+ video hits the age gate and
   there is no session, `PlaybackCenter` now calls `YouTubeAutoLogin.ensureSignedIn()`
   (throttled, one headless attempt per 10 min) on **all four** age-gate fallback paths
   (video catch, signed-in retry, audio catch, music URI resolution), then re-runs the
   signed-in unlock ladder (WEB / WEB_CREATOR / TV-embed / MWEB + SAPISIDHASH +
   X-Goog-Visitor-Id) and plays automatically.
5. **Auto Sign-in screen** — Menu → Google Account (and Settings → YouTube / Google
   Account) now opens the new **Auto Sign-in** activity: enter the Google email,
   password and 2FA secret once (stored Keystore-encrypted on the device); the manual
   browser login stays available via a button on the same screen.

## Security Notes
- Credentials are entered in-app and stored **encrypted on the device only**
  (Android Keystore, AES-256/GCM). They are **not** embedded in the source code,
  the APK, or this repository.
- Use a dedicated Google account for the app; if Google asks for phone approval
  (device-level challenge), approve it once or use the manual login flow.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.9 → CI builds the signed release APK
```
