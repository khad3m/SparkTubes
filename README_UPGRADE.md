# SparkTube v1.7.6 — Upgrade & Changelog

## What's Fixed in v1.7.6 — Age-Restricted (18+) Video Playback
1. **Signed-in WebView Extractor (`net/YouTubeWebViewUnlock.kt`, new)**: When YouTube's InnerTube API rejects cookie-only requests with LOGIN_REQUIRED / "cannot be watched anonymously", a last-resort authenticated WebView now runs YouTube's own page JavaScript with the user's 22+ Google session cookies, PoToken and signatureTimestamp, and resolves `window.ytInitialPlayerResponse` directly — bypassing the API-level age gate entirely.
2. **Dedicated MWEB Player Endpoint (`net/YouTubePlayerUnlock.kt`)**: The MWEB fallback now posts to `m.youtube.com/youtubei/v1/player` (mobile-web client) instead of the desktop endpoint, and a new public `deobfuscateAndNormalize()` helper makes unlocked player JSON reusable across engines.
3. **PlaybackCenter Age-Gate Fallback Chain**: On `AgeRestrictedContentException`, playback now walks: API unlock → signed-in WebView extractor (`YouTubeWebViewUnlock.extractPlayerJson`) → deobfuscate + normalize → `forceUnlockForUrl`. 18+ videos play without switching accounts or apps.
4. **Auth Session Cookie Wiring (`net/YouTubeAuth.kt`, `ui/auth/YouTubeLoginActivity.kt`)**: Login flow refreshes the cookie set the WebView extractor relies on, so signed-in age unlocks work right after login.

## v1.7.5 (previous round, retained)
SparkAudioProcessor real-time vocal isolation + noise cancel, Vocal Studio / Speaker Box / Club / Treble EQ presets, audio-first Now Playing, Music Playlists manager dialog, streaming/unlock engine refinements.

## v1.7.4 (also retained)
Menu/Settings scroll bounds + nav overlap fix, Music Add-to-Playlist picker, player settings sheet GPU-layer scroll fix, shorts seen-dedupe + next-5 prefetch + cache wipe on exit.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.6 → CI builds the signed release APK
```
