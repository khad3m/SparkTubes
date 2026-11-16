# SparkTube v1.7.11 — Fast Age-Gate (Pre-Play Login) + Homepage Category Overhaul (`versionCode = 34`)

## 1. Age-Restricted Videos: Login Now Starts Before Playback (45 s wait removed)
The v1.7.10 auto-login was bounded but still added a long wait after a failure. v1.7.11
moves the whole flow earlier and shorter:

- **Pre-play kickoff** — the moment a video starts resolving, `PlaybackCenter` calls
  `YouTubeAutoLogin.kickoff()`: if the device has saved credentials and no session, the
  headless Google sign-in starts **in the background while the video fetch runs**.
- **In-flight join** — if the video turns out to be age-restricted / sign-in-required,
  the catch-path hooks now *join* the already-running login instead of starting a second
  attempt, so the wait is just the remaining login time (typically 10–20 s).
- **30 s hard cap everywhere** — the engine attempt itself is capped at 30 s
  (watchdog backstop in `:ytauth`), and all four playback hooks dropped from 45 s to
  30 s. No path can ever hang or wait longer than 30 s.
- Playback of normal videos is unaffected: kickoff is throttled (one per 10 min),
  silent, and skipped entirely when already signed in or when no credentials are saved.

## 2. Homepage: Slow "All" Category Removed, Fast Search Categories In
- The **"All"** chip (slow personalized home feed) is **removed entirely** — from the
  layout, the code and the string resources.
- **Hindi Trending Songs** takes its place and is the new **home default** category.
- New categories added:
  - **Bangla Natok** — `new bangla natok full`
  - **Lofi Song** — `lofi songs`
  - **Item Song** — `bangla hindi item song` (Bangla + Hindi)
- Every category is now a fast search-query load; the home feed path no longer exists,
  so the homepage always loads through the quick search pipeline.

## Security Notes (unchanged)
Credentials live only on the device, encrypted with the Android Keystore
(`:ytauth` `SecureStore`). Nothing is embedded in the repo or APK.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.11 → CI builds the signed release APK
```
