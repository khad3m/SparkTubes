# SparkTube v1.7.7 — Age-Restricted Playback: Production Rebuild

## Why this release
v1.7.6's age-gate fix still failed in practice. Research (October 2026 state of YouTube's
InnerTube API, yt-dlp issue #17767 and the 2026 community threads) shows the ground truth:

- **Every anonymous InnerTube client is dead for 18+ videos** — visionos → LOGIN_REQUIRED,
  web_embedded → UNPLAYABLE, ANDROID_VR / IOS / TV clients → "Sign in to confirm your age".
  yt-dlp itself closed the no-account bypass request as *not planned*; userscripts like
  Simple-YouTube-Age-Restriction-Bypass no longer work.
- **The only working path is what a signed-in desktop browser does**: real Google session
  cookies + SAPISIDHASH authorization + `X-Goog-Visitor-Id` + the WEB / WEB_CREATOR / MWEB
  player clients. The app had the cookies and SAPISIDHASH parts, but **missed
  `X-Goog-Visitor-Id`** — without it, signed-in WEB/MWEB player requests are rejected with
  LOGIN_REQUIRED even when the cookies are perfectly valid. That was the core bug.

## What's implemented in v1.7.7 (production-grade unlock engine)
1. **Visitor data layer (`YouTubeAuth.visitorData()`)** — fetches and caches YouTube's
   `visitorData` (from `sw.js_data`) for 30 minutes and sends it as the `X-Goog-Visitor-Id`
   header **and** as `context.client.visitorData` in every signed-in WEB-family player
   payload — exact parity with yt-dlp's signed-in requests. `X-Goog-PageId` now mirrors the
   SESSION_INDEX cookie too.
2. **WEB_CREATOR client added (`YouTubePlayerUnlock`)** — the client yt-dlp currently
   recommends for age-gated content (client name 62), with desktop Chrome UA. Signed-in
   ladder reordered: **WEB → WEB_CREATOR → TVHTML5_SIMPLY_EMBEDDED → MWEB → TVHTML5 →
   WEB_EMBEDDED → ANDROID → ANDROID_VR → IOS** — the desktop clients that honor cookies
   come first; anonymous mobile clients only help for bot-checked (not age-gated) videos.
3. **Hardened signed-in WebView extractor (`YouTubeWebViewUnlock`)** — the last resort now
   truly behaves like the user's browser:
   - Saved Google session cookies are **injected into the WebView CookieManager** for all
     five YouTube domains before the page loads (previously it silently relied on a jar
     that `clearSession()` wipes and fresh processes never had).
   - Loads the **desktop** watch page (`www.youtube.com/watch?v=ID&bpctr=…&has_verified=1`)
     with a desktop UA — the only page that serves the age-confirm interstitial a signed-in
     user can pass — auto-clicks the confirm button, and falls back to the signed-in
     `/embed/` page on a second attempt (24s overall budget).
4. **Age-gate fallback on every playback path (`PlaybackCenter`)** — the music/audio
   resolution paths now also chain API unlock → WebView extractor → deobfuscate + normalize
   (previously only the main video path did), so 18+ videos play everywhere in the app.
5. **Google login hardening (`YouTubeLoginActivity`)** — right after a successful login the
   visitor data is pre-warmed in the background, so the first signed-in playback doesn't
   stall on a cold visitor-id fetch.

## On a separate library
No maintained Android library handles Google-login age-gated playback better than the
signed-in InnerTube approach above — NewPipeExtractor (the library already bundled) cannot
bypass the age gate without session cookies either, and yt-dlp's production pattern is
exactly what v1.7.7 now implements in-repo (cookies + SAPISIDHASH + visitor id + correct
client ladder + WebView as the browser-equivalent last resort).

## Note
Age-gated playback requires signing in (Menu → Settings → YouTube Account / Sign in) with an
account whose date of birth indicates 18+. Videos age-restricted by YouTube for a
non-verified account in restricted regions may still need the account to complete YouTube's
own age verification — that is a YouTube-side account requirement, not an app bug.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.7 → CI builds the signed release APK
```
