# SparkTube v1.7.13 — Stale-Session Fix: Forced Sign-In Refresh After Unlock Failure + Full Unlock/Playback Trail (`versionCode = 36`)

## What the v1.7.12 Diagnostics Log Showed (exact diagnosis)
The user's log from a Samsung SM-A107F (Android 11) proved the real failure chain:

- `hasCreds=true loggedIn=true` — a Google session exists on the device.
- Yet every strict 18+ video failed with `AgeRestrictedContentException: This
  age-restricted video cannot be watched anonymously` — meaning YouTube treats
  the stored session as anonymous.
- The unlock ladder produced nothing for those videos (one video only got a
  muxed-only unlock via the Piped fallback), and no `Playback`/`AutoLogin` lines
  appeared — the main video resolve and `resolveCatalog` paths were not yet
  instrumented.

**Conclusion:** the saved cookies are stale (SID/SAPISID strings still present,
so `isLoggedIn()` reports true, but the server-side session is dead). Every
authenticated request therefore degrades to anonymous — and because the app
believed it was already signed in, no re-login was ever attempted. Deadlock.

## Fixes (v1.7.13)
1. **Forced session refresh + unlock retry** — in both age-gate catch paths
   (main video resolve and `resolveCatalog`): if the unlock ladder fails while
   `isLoggedIn()` is true, `refreshSessionAndUnlock()` now forces one fresh
   headless sign-in (`ensureSignedIn(force = true)`, 30 s cap), verifies the new
   session, and retries the unlock with the fresh cookies. Throttled to once per
   2 minutes so genuinely un-unlockable videos never loop logins.
2. **Full playback/unlock trail** (the two missing paths are now instrumented):
   - `resolve vid=<id> title=…` at the start of BOTH resolve functions, with the
     pre-play auto-login kickoff added to both.
   - Unlock engine tier log: `unlock start (loggedIn=…)`, per-tier outcomes —
     `client unlock OK`, `web-client tiers exhausted (muxedOnly=…)`,
     `webview unlock OK / miss`, `muxed-only unlock OK`, `piped unlock OK`,
     `ALL tiers failed`.
   - Refresh flow logged step-by-step: `forcing session refresh`, `session
     refresh FAILED`, `session refreshed — retrying unlock`, `unlock after
     refresh OK/FAIL`, plus the final player error.
3. **Session header fix** — diagnostics install moved after `AppPrefs.init`, so
   the header now carries the real country instead of `?`.

## Expected Behaviour After This Release
- 18+ videos with a dead session: ladder fails → forced re-login (~10–20 s) →
  retry with fresh cookies → plays. The log will show exactly which tier
  succeeded or that the account itself is unverified for age-restricted content.
- Everything else unchanged.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.13 → CI builds the signed release APK
```
