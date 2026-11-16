# SparkTube v1.7.8 — Complete Age-Restricted (18+) Playback & `0:00` Loop Fix (`versionCode = 31`)

## What's New in v1.7.8 (`versionCode = 31`)

### 1. Intercept `/youtubei/v1/reel/reel_item_watch` + NewPipe JS Hardening (`YouTubePlayerUnlock.kt`, `OkHttpDownloader.kt`)
- **Fixes `"This age-restricted video cannot be watched anonymously"`**:
  - Bytecode analysis of `newpipeextractor-0.26.5-android-compat.jar` (`YoutubeStreamExtractor.fetchAndroidClient`) showed that NewPipeExtractor calls `https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch` **first** and immediately checks `playerResponse.playabilityStatus`. `YouTubePlayerUnlock.ensurePlayable()` now intercepts both `/youtubei/v1/player` and `/youtubei/v1/reel/reel_item_watch`, unwrapping and re-wrapping `{"playerResponse": ...}` so `YoutubeStreamExtractor` never throws `AgeRestrictedContentException`.
  - Normalizes `player_es6.vflset` → `player_ias.vflset` ([yt-dlp #17726](https://github.com/yt-dlp/yt-dlp/issues/17726)), clears sticky static exceptions in `YoutubeJavaScriptPlayerManager`, pre-populates `CACHED_THROTTLING_PARAMETERS` when NewPipe's `n`-regex fails, and adds a standalone Rhino signature decipherer fallback.

### 2. Real InnerTube `visitorData` & Brand-Account-Safe `X-Goog-PageId` (`YouTubeAuth.kt`, `YouTubeLoginActivity.kt`)
- **Fixes Signed-In Session Rejection (`LOGIN_REQUIRED`)**:
  - Replaced the broken `sw.js_data` regex with InnerTube `/youtubei/v1/visitor_id` and `/youtubei/v1/guide` JSON extraction, passive `/youtubei/v1/` response sniffing, `/tv` `ytcfg` scraping, and live `window.ytcfg` harvesting on login.
  - Stopped sending `X-Goog-PageId: 0` on primary Google accounts — per [yt-dlp `_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py), `X-Goog-PageId` is strictly for a secondary Brand Account's 21-digit `DELEGATED_SESSION_ID`, while `0` goes into `X-Goog-AuthUser`.

### 3. PO-Token-Free Signed-In Client Ladder & Stream HTTP Probing (`YouTubePlayerUnlock.kt`)
- **Prioritizes Cookie-Capable Clients Without GVS PO Token Policy**:
  - Per [yt-dlp `_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py), [yt-dlp #17542](https://github.com/yt-dlp/yt-dlp/issues/17542), and [yt-dlp #17389](https://github.com/yt-dlp/yt-dlp/issues/17389), `WEB`, `WEB_CREATOR`, and `MWEB` require a GVS PO Token (`pot=`) on `adaptiveFormats` (returning HTTP 403 without one), whereas `TV_SAMSUNG` (`TVHTML5` `5.20260707`), `WEB_EMBEDDED_PLAYER` (`2.20260708.00.00`), `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (`2.0`), and `TVHTML5` (`7.20260707.07.00`) support cookies and do **not** require a GVS PO Token.
  - Added fast `Range: bytes=0-0` probing (`probeStreamUrlOk`) to prune 403-gated `adaptiveFormats` before they ever reach ExoPlayer.

### 4. Fixed `0:00` Infinite Auto-Reload / Retry Loop (`PlaybackCenter.kt`)
- **Eliminates Infinite `resolveCurrent()` Loop**:
  - Removed `usedSignedInRetry = false; usedHttpRetry = false` from inside `resolveCurrent()` (which previously wiped `usedHttpRetry` on every HTTP 403 retry and looped forever) and moved it to `resetRetryFlags()` on new track initiation.
  - Reordered `onPlayerFailed` so ExoPlayer falls back from DASH (`dashActive`) to progressive `c.muxed` (`itag=18`) **before** attempting a single bounded network re-resolve.
  - Synthesized an `AudioStream` from `rawMuxed` (`itag=18`) when `rawAudio` is empty, and added duration fallback from `catalog.durationSec` / `approxDurationMs` so duration never displays `0:00`.

### 5. Per-Cookie WebView Injection & MSE-Disabled V8 Stream Capture (`YouTubeWebViewUnlock.kt`)
- Injects saved Google cookies one-by-one via `YouTubeAuth.syncCookiesToWebView()`, disables `window.MediaSource` at `onPageStarted` so V8 deciphers progressive `itag=18` into `<video>.src`, intercepts `googlevideo.com/videoplayback` URLs and `pot=` tokens in `shouldInterceptRequest`, and runs an in-page `TVHTML5` `fetch('/youtubei/v1/player')`.
