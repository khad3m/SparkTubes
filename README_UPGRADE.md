# SparkTube v1.7.10 — Blank Player Page Fix: Bounded Auto-Login, Hard Watchdog, Guaranteed Error Surface (`versionCode = 33`)

## Root Cause (v1.7.9 regression)
Clicking a video from search could leave the player page **completely blank — no info,
no error, no playback**. Root-cause audit found a hang chain, not a UI bug:

1. YouTube is now tightening unauthenticated player requests (InnerTube probes return
   `LOGIN_REQUIRED` for normal videos), so ordinary playback failures became common and
   carried "sign in"-style messages.
2. Those failures routed into the v1.7.9 automatic sign-in hook.
3. The `:ytauth` `GoogleLoginEngine` started its poll **only from `onPageFinished`**
   with no watchdog — if that callback never fired (stuck page, dead renderer), the
   suspend continuation never resumed, no timeout ever fired.
4. The playback coroutine stayed suspended forever: `finally { setResolving(false) }`
   never ran, `notify { it.onError(...) }` never ran → **blank player, silent**.

## Fixes
1. **Engine watchdog (no hang path possible)** — `GoogleLoginEngine` now starts its
   stage poll unconditionally 600 ms after `loadUrl` (independent of page callbacks)
   and arms a hard watchdog at `timeout + 5 s` that always finishes the login attempt
   with a typed `timeout` failure. A stuck page can no longer hang any caller.
2. **Bounded playback path** — all four `ensureSignedIn` hooks in `PlaybackCenter`
   are wrapped in `withTimeoutOrNull(45 s)`: even a worst-case auto-login adds at
   most 45 s, then the ladder/error path proceeds as before.
3. **Engine errors can never propagate** — `YouTubeAutoLogin.ensureSignedIn` wraps the
   engine call in `runCatching`, degrading any unexpected exception (WebView init
   failure, renderer crash) into a typed `engine_error` failure.
4. **Error surface guaranteed** — with the hang removed, the existing catch-path
   `notify { it.onError(...) }` always executes, so any remaining failure (bad
   credentials, Google phone-approval challenge, API rejection) now shows a visible
   message instead of silence.

## Result
- Normal videos play exactly as before (hooks only engage on failures).
- Age-restricted / sign-in-required videos attempt the automatic Google session
  (bounded at 45 s) and then play via the signed-in unlock ladder — or show a clear
  error instead of a blank page.
- Auto Sign-in screen (Menu → Google Account) is unchanged: enter the Google email,
  password and 2FA secret once; credentials stay Keystore-encrypted on the device.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.10 → CI builds the signed release APK
```
