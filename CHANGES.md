# SparkTube v1.6.3 — Glass Player (Google Login + Multi-Client InnerTube Unlock Edition)

## What's New in v1.6.3 (`versionCode = 21`)

### 1. In-App Google / YouTube Sign-In (`YouTubeLoginActivity.kt`, `YouTubeAuth.kt`, `OkHttpDownloader.kt`)
- **Full Google Account Sign-In (`SAPISIDHASH` + Session Cookie Engine)**:
  - Added crash-safe `YouTubeLoginActivity` (accessible from **Menu → YouTube / Google Account**, **Settings → General / Video / Music**, **Video Player Gear Menu**, **Player Error Screen**, and **Music Player More Options**).
  - Uses a clean Android Chrome User-Agent (without `; wv`) so Google OAuth / ServiceLogin allows normal sign-in without `disallowed_useragent` errors.
  - Automatically extracts `SAPISID`, `__Secure-3PAPISID`, `__Secure-1PAPISID`, `SID`, and `LOGIN_INFO` cookies and computes `Authorization: SAPISIDHASH <ts>_<sha1> SAPISID1PHASH <ts>_<sha1> SAPISID3PHASH <ts>_<sha1>` + `X-Origin` + `X-Goog-AuthUser: 0` on every `NewPipeExtractor` and InnerTube request.

### 2. Multi-Client InnerTube Fallback Engine (`YouTubePlayerUnlock.kt`, `YtRepository.kt`)
- **Unlocks Restricted & Bot-Checked Videos (With or Without Login)**:
  - Automatically injects `"contentCheckOk": true` and `"racyCheckOk": true` into outgoing `/youtubei/v1/player` and `/youtubei/v1/next` payloads.
  - Whenever a video returns `LOGIN_REQUIRED`, `AGE_VERIFICATION_REQUIRED`, `UNPLAYABLE`, or lacks direct stream URLs (`signatureCipher` failure), `YouTubePlayerUnlock` transparently queries fallback InnerTube clients in priority order:
    1. `ANDROID_VR` (`1.60.19` — bypasses bot-check & JS cipher, returns direct progressive/adaptive URLs up to 4K)
    2. `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (`2.0` — bypasses 18+ age-gates and login restrictions, with Google `SAPISIDHASH` cookies when signed in)
    3. `IOS` (`19.45.4` — direct iPhone adaptive/progressive streams)
    4. `WEB` (Authenticated cookie + `SAPISIDHASH` session)
    5. `ANDROID_TESTSUITE` (`1.9`)
  - Normalizes `averageBitrate` and synthesizes `microformat.playerMicroformatRenderer` when omitted by mobile/VR/TV clients so `NewPipeExtractor` parses 100% of streams and metadata without throwing exceptions.
- **Client-Matched `googlevideo.com` CDN Headers (`PlaybackCenter.kt`, `DownloadCenter.kt`)**:
  - Automatically matches the `User-Agent` (`ANDROID_VR`, `IOS`, `ANDROID`, `TVHTML5`, or `WEB`), `Origin`, `Referer`, and `Cookie` headers to the `c=` parameter on every `googlevideo.com` URL so both `ExoPlayer` playback and 6-thread parallel downloading never get HTTP 403 errors.

---

## Previous Upgrades in v1.6.2 (`versionCode = 20`)
1. Byte-Accurate Download Play / Pause / Resume / Retry (`.partmeta` chunk state persistence + automatic YouTube CDN URL refresh).
2. Public Local Storage (`Download/SparkTube`, `Music & Movies/SparkTube`) & Custom Folder Picker (`ACTION_OPEN_DOCUMENT_TREE`).

## Previous Fixes in v1.6.1 (`versionCode = 19`)
1. Fixed Offline & Online Audio Track Mismatch (`NowPlayingActivity` + `PlaybackCenter` + `MusicListActivity`).
2. Added Live Horizontal Download Progress Bar across Video Player, Audio Player, Library Downloads, Offline Audio List, and Download Sheet.
