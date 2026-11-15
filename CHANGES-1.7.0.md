# SparkTube v1.7.0 — Changelog

**Release focus: 13 user-reported fixes & features — age-restricted playback, up-next repair,
mini player on Search, premium Audio Studio, playlists & shorts in Search, player controls
cleanup, in-app update download, low-RAM optimisation.**

## 1. Age-restricted (18+) videos now play after Google Sign-In
- Every anonymous InnerTube client (ANDROID_VR, IOS, TV/TV-embedded, WEB_EMBEDDED,
  ANDROID_TESTSUITE) is now rejected by YouTube for age-gated videos ("Sign in to confirm
  your age") — verified live against a NewPipe age-restricted test video.
- New **signed-in WEB client fallback**: when a Google account is connected, the desktop WEB
  player request is retried with the user's session cookies + `SAPISIDHASH` authorization
  (exactly what the signed-in browser sends) as the FIRST fallback, ahead of the TV/VR/IOS
  chain.
- Fallback acceptance now also counts `signatureCipher` formats (the signed-in WEB client
  returns ciphered URLs that NewPipeExtractor de-obfuscates with its player-JS engine), so
  age-gated videos no longer surface the "anonymous" sign-in popup.

## 2. Up Next / Related videos populate immediately
- The video watch path never ran the search-based related fallback — only the audio path did,
  which is why the list appeared only after toggling audio/video mode.
- The fallback (`relatedFallback`: title+channel search) now runs on BOTH paths via a shared
  `maybeFetchRelatedFallback()`, and the radio queue refills when it lands.

## 3. Mini player above the Search results
- New reusable `MiniPlayerBar` + `mini_player_bar.xml`, included at the bottom of
  `SearchActivity` (results list gets 90dp clearance): thumbnail, title/channel, play/pause,
  close; tap opens the full video player or Now Playing; gestures intact.

## 4. Menu screen fixes
- The duplicate **Google connect card is removed from the Menu tab** (it lives in
  Settings → YouTube / Google Account); the profile card reflects connection state.
- Bottom clearance for the floating navbar now has a 100dp floor, so menu/settings cards
  can never slide under the navbar; Settings page remains fully scrollable.

## 5. Premium Audio Studio (Sleep Timer / EQ / Crossfade)
- The old plain action list is replaced by a frosted-glass **Audio Studio bottom sheet**
  with real pill buttons: Sleep Timer (15/30/60/Off with remaining time), Hardware EQ
  presets (Flat / Bass Boost / Vocal / Club / Treble) and Crossfade (Off–8s) — active
  choices highlighted, sheet re-renders live on tap.

## 6. Radio auto-builds the music playlist
- Every music playback entry point (Favorites, Downloads, Library music queues, offline
  audio downloads) now starts with **radio mode enabled**, so the music queue auto-extends
  with matching tracks after the seed song.

## 7. Hardware acceleration: ON by default, user-toggleable
- Manifest now sets `android:hardwareAccelerated="true"` explicitly.
- New **Settings → Video → "Hardware acceleration"** row (default ON). OFF prefers software
  decoders (`c2.android.*` / `OMX.google.*`) via a custom `MediaCodecSelector` — real
  software decoding for devices with broken hardware codecs.

## 8. Low-RAM optimisation
- New `DeviceTuning` profile (`ActivityManager.isLowRamDevice` / heap check):
  - Playback buffer 45s/120s → 20s/60s, back-buffer 30s → 10s on low-RAM devices.
  - In-memory caches shrink (stream-info 30 → 10, music-URI cache 64 → 24, pending queue
    256 → 96).
  - Coil image loader: memory cache capped at 8% (20% normal) + RGB_565 bitmaps for
    thumbnails.
  - `onTrimMemory` releases thumbnail bitmaps as soon as the system asks.

## 9. Playlists in Search — and playable
- New **Playlists tab** (YouTube PLAYLISTS search filter): cover, video-count badge and
  channel per row; opening one offers **Play as video** (watch-page queue with auto-advance)
  or **Play as audio** (Now Playing + radio continuation).

## 10. Shorts / Reels: toggle, Search tab & bottom-bar shortcut
- **Settings → "Shorts / Reels videos"** (default ON):
  - Search gains a **Shorts tab** (clips ≤ 3 minutes).
  - The floating navbar gains an extra **Shorts button** (hidden when disabled) opening a
    new full-page vertical snap Shorts feed (thumbnail cards, low-RAM friendly).

## 11. Player controls cleanup
- **No more volume/brightness zones in portrait** — vertical swipe down minimises to the
  mini player; brightness/volume HUD is fullscreen (landscape) only.
- Audio/Video switcher is now **icon-only** (no text labels).
- New **speed icon button** on the player opening a 0.5x – 3.0x speed sheet
  (0.5 / 0.75 / 1 / 1.25 / 1.5 / 1.75 / 2 / 2.5 / 3).

## 12. Update download happens inside the app
- "Download update" now streams the APK **inside a progress dialog** (live percent + size)
  directly from GitHub releases — no browser. When ready, a one-tap **Install now** prompt
  hands the file to the system package installer (REQUEST_INSTALL_PACKAGES +
  FileProvider wiring added).

## 13. Full wiring verification
- All 181 resource XML files validated; version bumped to **1.7.0 (23)**; release built,
  signature verified (v2/v3), and every new screen/row/button wired end-to-end.
