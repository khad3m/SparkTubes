# SparkTube v1.8.2 — WebView Is the Only Streaming Path Now (`versionCode = 41`)

## The hard truth this release is built on (all verified live)
The user's v1.8.1 log showed `cloud tier miss … status=OK attempts=WEB:LOGIN_REQUIRED,
MWEB:HTTP 400, WEB:OK` — the worker's session WORKS (age gate passed) but playback
still failed. Investigation with the deployed worker + a 5-variant auth matrix
(`/diag-mweb`) + yt-dlp ground truth established, step by step:

1. The worker's WEB response contains **only ciphered formats** (0 direct URLs) —
   the app deciphers them fine, but its `Range: bytes=0-0` probe from the device
   then gets **HTTP 403**, so it prunes everything → "miss".
2. MWEB with a valid session needed a specific auth shape (SAPISIDHASH rejects the
   m.youtube.com origin with HTTP 400; **all-www origin/UA** = the only accepted
   combo — found via the variant matrix). But even the fixed MWEB response is
   ciphered now.
3. Decisive: **yt-dlp with the same fresh cookies** passes the age gate (status OK)
   and then warns: *"mweb client https formats require a GVS PO Token which was not
   provided. They will be skipped as they may yield HTTP Error 403."* Probes
   confirmed: deciphered URLs, direct URLs, even `serverAbrStreamingUrl` — **all 403
   without a PO Token, from any IP, through the worker's `/stream` proxy included.**

Conclusion: since ~Nov 2026 YouTube requires a GVS PO Token for every WEB/MWEB
stream URL. No server (worker, proxy, sandbox) can produce streamable URLs without
minting a PO token. **A real browser context is the only token mint.**

## What v1.8.2 does
1. **Cloud tier removed from the unlock ladder** — it can never stream anymore;
   keeping it only burned ~15 s per age video. The worker stays deployed
   (`sparktube-unlock.khadimsorder1.workers.dev`) with the fresh session secret for
   diagnostics and future PO-token work; Settings keeps the URL field.
2. **Dead headless AutoLogin removed everywhere** (resolve kickoff + all four
   `ensureSignedIn` call sites + the 65 s forced refresh). It never completed once.
3. **Interactive Browser login becomes the automatic fix path**: when the ladder
   fails on an age-gated video, the app now launches the Browser login activity by
   itself (once per 2 minutes) with a Bangla notice. After the user signs in once,
   the WebView tier has a live session.
4. **WebView tier = the streaming engine**: it runs a real browser context, mints
   its own PO token, disables MSE to force a progressive itag-18 URL, captures the
   page's deciphered media URL + in-page signed-in player response. Budgets bumped
   (watch 22 s, embed 14 s, blocking 30 s) — age pages are slower.
5. Error hint updated: `Settings → Account এ "Browser login" করুন (একবার)`.

## User-facing flow after update
18+ video → ladder fails → app auto-opens Browser login → user signs in (their own
account, age-verified) → back to app → play the video again → WebView tier streams
it directly. Non-age videos are unaffected (normal NewPipe path).
