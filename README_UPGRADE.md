# SparkTube v1.7.12 — Full Diagnostics Logging: Browse, Search, Playback & Auto-Login Trail on Device (`versionCode = 35`)

## Why
When playback, search or sign-in misbehaves, the reason so far stayed invisible.
v1.7.12 records **everything since app start** to an on-device diagnostics file so
the user can export it and send it along — then the exact failing step (search,
stream fetch, age-gate path, unlock ladder, auto-login kind, crash) is visible in
the log and can be fixed precisely.

## What Gets Recorded
- **Session header** at every app start: app version, Android version, device model,
  country preference, whether credentials are saved, whether a session is active.
- **Browse/search**: every category load (home) and every video search — query,
  page, result count; failures with exception class + message.
- **Playback**: every resolve (video id + title), stream-fetch success (stream
  counts + duration) or failure, age-gate unlock path entry with the trigger
  reason, unlock-ladder OK/FAIL, and the final player error shown to the user.
- **Auto-login**: pre-play kickoff (and its skip reasons), each attempt (email
  masked), engine result (`success cookies=N` / `failed kind=… msg=…`).
- **Crashes**: any uncaught exception with a 14-frame stack, chained after the
  existing CrashReporter so nothing is lost.

## Privacy
- Emails are masked (`a***@gmail.com`); passwords, cookies and session values are
  never written to the log.
- The file lives in app-internal storage (`files/diagnostics/`), rotated at 512 KB
  with one previous generation kept. Nothing leaves the device unless the user
  taps **Share**.

## How to Use (Menu → Settings → Diagnostics Log)
1. Reproduce the problem (search the 18+ video, play it, note what happens).
2. Open **Settings → Diagnostics Log**, tap **Share** (or **Copy**) and send the
   text along with a note of what failed. The last session's events are at the
   bottom of the log; **Clear** resets it.

## Implementation Notes
- `DiagnosticsLogger` (util): thread-safe writer executor + in-memory ring
  (400 lines) + rotated file; fallback tail from memory if files are unreadable.
- `DiagnosticsActivity` (menu): monospace tail view with Copy / Share / Clear.
- Instrumented chokepoints: `YtRepository.searchVideos` / `streamInfo`
  (wrapper functions — internal logic untouched), `PlaybackCenter` resolve and
  catch paths, `YouTubeAutoLogin`, `HomeFragment`, app start.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.12 → CI builds the signed release APK
```
