# SparkTube v1.7.8 — Complete Age-Restricted (18+) Playback & `0:00` Infinite Loop Fix (`versionCode = 31`)

## Why v1.7.7 Still Failed (Bytecode + Protocol Root-Cause Audit)

A full line-by-line audit of `SparkTube-v1.7.7` and bytecode disassembly of `newpipeextractor-0.26.5-android-compat.jar` against [yt-dlp's YouTube `_base.py` client definitions](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py) exposed **6 concrete bugs** that caused the two exact symptoms reported:
- **Symptom A**: `"This age-restricted video cannot be watched anonymously"` even when logged into a Google account.
- **Symptom B**: Player stuck at `0:00` and infinitely auto-reloading/retrying.

### The 6 Root Causes Found & Fixed in v1.7.8

1. **Root Cause 1 — `NewPipeExtractor v0.26.5` Calls `/youtubei/v1/reel/reel_item_watch` First, Which `YouTubePlayerUnlock` Ignored (`YouTubePlayerUnlock.kt`)**:
   - Disassembling `YoutubeStreamExtractor.fetchAndroidClient` in `newpipeextractor-0.26.5-android-compat.jar` revealed that when `androidPoTokenResult == null`, NewPipeExtractor calls `https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false&t=...&id=VIDEO_ID&$fields=playerResponse` FIRST, which returns `{"playerResponse": {...}}`, and immediately invokes `checkPlayabilityStatus(playerResponse.getObject("playabilityStatus"))`.
   - In v1.7.7, `YouTubePlayerUnlock.ensurePlayable()` checked `if (!url.contains("/youtubei/v1/player")) return responseBody`, completely ignoring `/youtubei/v1/reel/reel_item_watch`! Thus `YoutubeStreamExtractor` received the raw `LOGIN_REQUIRED` response and immediately threw `AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")`.
   - **Fix in v1.7.8**: `YouTubePlayerUnlock.ensurePlayable()` and `patchOutgoingRequestBody()` now intercept **both** `/youtubei/v1/player` and `/youtubei/v1/reel/reel_item_watch`, unwrapping and re-wrapping `{"playerResponse": ...}` seamlessly so `NewPipeExtractor` gets a playable response on the very first call.

2. **Root Cause 2 — `YouTubeAuth.visitorData()` Always Returned `null` & `X-Goog-PageId: 0` Invalidated Primary Google Sessions (`YouTubeAuth.kt`)**:
   - In v1.7.7, `YouTubeAuth.visitorData()` fetched `https://www.youtube.com/sw.js_data` and ran `Regex("\"visitorData\":\"((?:[^\"\\\\]|\\\\.)+)\"")`. Because `sw.js_data` is a Google JSPB array (`)]}'\n[[[...]]]`) with no `"visitorData":` JSON key, `visitorData()` returned `null` 100% of the time!
   - Even worse, `YouTubeAuth.applyYouTubeAuthToRequest` unconditionally sent `X-Goog-PageId: 0` on every signed-in request. Per [yt-dlp's `_base.py` authentication implementation](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py), `X-Goog-PageId` is strictly for a secondary Brand Account's 21-digit `DELEGATED_SESSION_ID` (parsed from `DATASYNC_ID`), while `0` belongs in `X-Goog-AuthUser`. Sending `X-Goog-PageId: 0` caused Google's Gaia authentication server to look up a non-existent Brand Account `"0"` and reject the session with `LOGIN_REQUIRED`.
   - **Fix in v1.7.8**: `YouTubeAuth` now harvests `visitorData` and `datasyncId` directly from InnerTube `/youtubei/v1/visitor_id` and `/youtubei/v1/guide` JSON responses, passive `/youtubei/v1/` sniffing, `/tv` `ytcfg`, and live login/WebView `window.ytcfg`. And `X-Goog-PageId` is **only** sent when a real Brand Account `delegatedSessionId()` exists.

3. **Root Cause 3 — `WEB`, `WEB_CREATOR`, and `MWEB` `adaptiveFormats` Require GVS PO Tokens (`pot=`) in 2026 and Return HTTP 403 (`YouTubePlayerUnlock.kt`)**:
   - Per [yt-dlp `_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py), [yt-dlp issue #17542](https://github.com/yt-dlp/yt-dlp/issues/17542), and [yt-dlp issue #17389](https://github.com/yt-dlp/yt-dlp/issues/17389), `WEB`, `WEB_CREATOR`, and `MWEB` enforce `GVS_PO_TOKEN_POLICY: required=True` on `adaptiveFormats`. Without a GVS PO Token (`pot=`), every `adaptiveFormats` stream URL returns **HTTP 403 Forbidden** when ExoPlayer requests it from `googlevideo.com` (only progressive `formats` `itag=18` works without a PO Token on those clients).
   - Conversely, `TV_SAMSUNG` (`clientName: "TVHTML5"`, `clientVersion: "5.20260707"`, `deviceMake: "Samsung"`, `deviceModel: "UKS9800"`, `osName: "Tizen"`, `osVersion: "2.4.0"`), `WEB_EMBEDDED_PLAYER` (`2.20260708.00.00`, `thirdParty.embedUrl: "https://www.reddit.com/"`), `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (`2.0`), and `TVHTML5` (`7.20260707.07.00`) have `SUPPORTS_COOKIES = True` and **no GVS PO Token requirement**!
   - **Fix in v1.7.8**: Reordered the signed-in unlock ladder so `TV_SAMSUNG` → `WEB_EMBEDDED_PLAYER` → `TVHTML5_SIMPLY_EMBEDDED_PLAYER` → `TVHTML5` → `TV_DOWNGRADED` run **first**, and added a fast `Range: bytes=0-0` HTTP probe (`probeStreamUrlOk`) that prunes any 403-blocked `adaptiveFormats` while keeping verified `200`/`206` progressive (`itag=18`) or adaptive streams.

4. **Root Cause 4 — Infinite `0:00` Auto-Reload / Retry Loop in `PlaybackCenter.kt`**:
   - When ExoPlayer encountered HTTP 403 (`ERROR_CODE_IO_BAD_HTTP_STATUS`) on a PO-token-gated adaptive stream in v1.7.7, `onPlayerFailed` checked `if (!usedHttpRetry)` **before** `if (dashActive)` and **before** `if (!usedFallback && c.muxed.isNotEmpty())`, set `usedHttpRetry = true`, and called `resolveCurrent()`.
   - Inside `resolveCurrent()`, line 1489 unconditionally executed `usedSignedInRetry = false; usedHttpRetry = false`! That wiped `usedHttpRetry` back to `false` on every single retry, causing `PlaybackCenter` to loop `resolveCurrent()` → `0:00` → HTTP 403 → `resolveCurrent()` infinitely without ever falling back from DASH (`adaptiveFormats`) to progressive `c.muxed` (`itag=18`)!
   - **Fix in v1.7.8**:
     - Removed `usedSignedInRetry = false` and `usedHttpRetry = false` from inside `resolveCurrent()` and moved them into `resetRetryFlags()`, called **only** when starting a new track.
     - Reordered `onPlayerFailed` so local stream fallbacks (`dashActive` → non-DASH, and `videoOnly` → `c.muxed`) execute **before** the single bounded `usedHttpRetry` network re-resolve.
     - Synthesized a fallback `AudioStream` from `rawMuxed` (`itag=18`) whenever `rawAudio` is empty, and added duration fallback from `catalog.durationSec` / `approxDurationMs` so the player UI never shows `0:00`.

5. **Root Cause 5 — `player_es6.vflset` vs `player_ias.vflset` Mismatch & Sticky Static Exceptions in `YoutubeJavaScriptPlayerManager` (`OkHttpDownloader.kt`, `YouTubePlayerUnlock.kt`)**:
   - Per [yt-dlp issue #17726](https://github.com/yt-dlp/yt-dlp/issues/17726) and [yt-dlp issue #17542](https://github.com/yt-dlp/yt-dlp/issues/17542), YouTube serves `player_es6.vflset` in 2026, which breaks `YoutubeJavaScriptExtractor`'s hardcoded `player_ias\.vflset` regex and Rhino's ES5 parser. Worse, `YoutubeJavaScriptPlayerManager` caches any `ParsingException` in static fields (`sigTimestampExtractionEx`, `sigDeobFuncExtractionEx`, `throttlingDeobfFuncExtractionEx`) forever, and `YoutubeStreamExtractor.buildAndAddItagInfoToList` has zero `try/catch` blocks around `getUrlWithThrottlingParameterDeobfuscated`.
   - **Fix in v1.7.8**: `OkHttpDownloader` rewrites `player_es6.vflset` → `player_ias.vflset` in both URLs and HTML responses, `YouTubePlayerUnlock` clears sticky static exceptions via reflection, pre-populates `CACHED_THROTTLING_PARAMETERS` if NewPipe's `n`-regex fails on a new `base.js`, and includes a standalone Rhino signature decipherer fallback.

6. **Root Cause 6 — `YouTubeWebViewUnlock.kt` Single-Header Cookie Bug & V8 Stream Interception**:
   - In v1.7.7, `YouTubeWebViewUnlock` called `cm.setCookie(domain, sessionCookies)` with the entire `; `-joined cookie string. Because Android's `CookieManager.setCookie` parses a single `Set-Cookie` header, everything after the first `;` was treated as attributes of `CONSENT=YES+cb`, dropping all login cookies (`SID`, `SAPISID`, `__Secure-1PSID`, `__Secure-3PSID`)!
   - **Fix in v1.7.8**: `YouTubeAuth.syncCookiesToWebView()` sets each cookie one-by-one (`name=value; Domain=.youtube.com; Path=/; Secure`), `YouTubeWebViewUnlock` disables `window.MediaSource` at `onPageStarted` so YouTube's V8 player deciphers `itag=18` into `<video>.src`, intercepts `googlevideo.com/videoplayback` URLs and BotGuard `pot=` tokens in `shouldInterceptRequest`, and executes an in-page signed-in `TVHTML5` `fetch('/youtubei/v1/player')`.

## Build & Release (`v1.7.8`, `versionCode = 31`)
```bash
./gradlew assembleRelease
# Or push and trigger GitHub Actions release v1.7.8:
GITHUB_PAT=<your_github_pat> ./push_and_release.sh
```
