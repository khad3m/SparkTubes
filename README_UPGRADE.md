# SparkTube v1.7.14 — Refresh-First Unlock Flow + Per-Client Failure Reasons (`versionCode = 37`)

## What the Second Diagnostics Log Showed (v1.7.13 on device)
The new trail worked (every resolve and unlock tier now logged) and exposed two
remaining problems precisely:

1. **`forcing session refresh` never fired.** The age-gate catch still re-ran the
   unlock ladder first (~28 s), then the slow WebView tier (~30–60 s) — the forced
   session refresh sat at the END of that chain. In every logged attempt the user
   had already tapped the next video (resolve cancelled) or the copy cut off
   before the refresh ever started. Deadlock again: right fix, wrong order.
2. **Web clients failed in 3–4 s with zero visibility** — `fetchFallbackPlayer`
   swallowed both HTTP errors (`return null` on `!isSuccessful`) and 200 responses
   carrying `playabilityStatus: LOGIN_REQUIRED`. Impossible to tell from the log
   whether the saved session is stale (refresh will fix) or the Google account
   itself is not age-verified (no app fix possible).

## Fixes (v1.7.14)
1. **Refresh-first unlock flow** (both age-gate catch paths): the redundant
   ladder re-run is removed — it already executes inside `streamInfo` via
   OkHttpDownloader's `ensurePlayable` interception. The catch now goes:
   forced session refresh + unlock retry (fresh cookies, ~30 s cap) → WebView
   extraction last. The refresh now actually starts within ~1 s of ladder
   failure, so a cancelled next tap can no longer starve it.
2. **`no session — refresh skipped`** logged when no session exists (trail is
   complete for signed-out cases too).
3. **Per-client failure reasons**: every unlock client that fails now logs
   `client <NAME> HTTP <code>` (auth on/off) and every non-OK `playabilityStatus`
   logs `client <NAME> status=… reason=…` — e.g. `Sign in to confirm your age`
   (stale session) vs `This video may be inappropriate for some users`
   (account needs age verification). The next log will state the exact cause
   in plain text.

## Expected Log Flow Now (18+ video, logged in)
```
resolve vid=… title=…
unlock start vid=… loggedIn=true
client WEB status=LOGIN_REQUIRED reason=Sign in to confirm your age   ← decisive
web-client tiers exhausted …
ladder failed while logged in — forcing session refresh vid=…
attempt email=a***…
success cookieHeaderLen=…
session refreshed — retrying unlock vid=…
client unlock OK vid=…     ← if the session was the problem
```
If the reasons instead say age-verification-required for every signed-in client,
the account itself must be verified on YouTube — the log will prove it.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.7.14 → CI builds the signed release APK
```
