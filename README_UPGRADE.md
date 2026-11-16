# SparkTube v1.7.15 — Login Engine Budget Raised to 60 s + Real Re-Login Result Logging (`versionCode = 38`)

## What the Third Diagnostics Log Proved (v1.7.14 on device, decisive)
1. **Refresh-first flow works**: `forcing session refresh` now fires ~1 s after
   ladder failure, per-client reasons are logged.
2. **The re-login never completes**: `attempt email=k*** timeout=30s` at
   16:23:09.271 → `session refreshed` at 16:23:39.056 = exactly the 30 s window,
   with NO `success cookieHeaderLen=…` line. The headless Google login on this
   low-end device (SM-A107F, Android 11, BD network) needs more than 30 s.
3. **The helper false-positived**: after the timeout it checked
   `isLoggedIn()` — the stale cookies satisfy that check — and logged
   "session refreshed" even though no fresh session landed. (The second
   refresh was cancelled mid-login by the next tap and false-positived the
   same way.)
4. Consequently every signed-in client still answered
   `status=LOGIN_REQUIRED reason=Sign in to confirm your age` — but we cannot
   yet conclude whether the account itself is age-unverified, because no
   genuinely fresh session has ever been tested.

## Fixes (v1.7.15)
1. **Login engine budget 30 s → 60 s** (`timeoutMs = 60_000L`) — measured
   too short on low-end devices.
2. **Real result logging in the refresh flow**: the helper now uses
   `ensureSignedIn`'s Boolean return (65 s wait to cover the engine's 60 s
   budget). Fresh session confirmed → `fresh session confirmed in <ms>ms —
   retrying unlock`. Timeout/cancel/failure → `forced re-login FAILED after
   <ms>ms — no fresh session, stale cookies unusable`. No more false
   "session refreshed".
3. **Self-contained final verdict**: if a CONFIRMED fresh session still fails
   the unlock, the log states it outright — "…if every signed-in client still
   says 'Sign in to confirm your age' with fresh cookies, the Google account
   itself is not age-verified (verify at youtube.com)".

## The Decision Tree This Log Will Resolve
- `success cookieHeaderLen=…` then `client unlock OK` → stale session was the
  whole problem; fixed for good.
- `success cookieHeaderLen=…` then all clients still
  `Sign in to confirm your age` → **the Google account is not age-verified** —
  verification must be done on the account (youtube.com → Settings → age
  verification), no app change can bypass that.
- `forced re-login FAILED` → login itself is blocked (risk wall / network);
  the engine's `failed kind=… msg=…` line shows why.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.15 → CI builds the signed release APK
```
