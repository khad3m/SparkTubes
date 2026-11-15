# SparkTube v1.7.2 — Changelog

**Release focus: real fullscreen Shorts player, age-restricted video wiring, music top-bar fix.**

## 1. Shorts / Reels — real fullscreen portrait player
- The shorts screen is now a true vertical pager: **every page is a full-screen
  video that plays inline** (no more thumbnail cards handing off to the watch page).
- **Auto-play** when a short snaps into place; **swipe up/down** to move between shorts.
- **Tap the middle of the video to play/pause** (clean UI: no controllers, big center
  play button only when paused, loading spinner while the stream resolves).
- **Fill / Fit toggle** (top-right): whole-video fit or crop-to-screen fill, remembered
  across sessions (Settings storage).
- Immersive fullscreen portrait: system bars hidden, status bar never intrudes.
- Leaving the screen pauses playback, exactly like the YouTube app.
- Feed sources unchanged (v1.7.1): country-aware InnerTube shorts feed, personalized
  when Google-signed-in, with trending + search fallbacks so it's never empty.

## 2. Age-restricted videos now follow your Google login
- The signed-in unlock path is now wired correctly end-to-end:
  - the WEB (signed-in browser) client payload version now matches its headers
    (a version mismatch could make YouTube reject the request);
  - a new **ANDROID (official app client) + your session cookies** attempt runs right
    after WEB — this mirrors what the real YouTube app does for logged-in users;
  - when a resolve fails with an age/login gate and you ARE signed in, PlaybackCenter
    now **forces the signed-in unlock engine and retries the resolve once**
    instead of surfacing "sign in to play" immediately.
- TV / embedded / VR / iOS clients remain as backup for bot-checked videos.

## 3. Music (Now Playing) top bar fixed
- The Audio/Video mode switcher next to the grab handle was wide (labeled buttons) and
  visually collided with the centered handle pill on narrow screens — it is now
  **icon-only** (34dp, same as the video player's v1.7.0 switcher), so the top bar is
  clean and nothing overlaps.
