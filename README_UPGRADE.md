# SparkTube v1.8.0 — Cloud Unlock Worker (18+ Fix, Final Architecture) (`versionCode = 39`)

## What the Fourth Diagnostics Log Proved (definitive)
1. **On-device headless Google login is dead**: `attempt timeout=60s` →
   `E/AutoLogin: failed kind=timeout msg=Sign-in did not complete in time` →
   `forced re-login FAILED after 63891ms`. Even 60 s is not enough on this
   device; Google's risk wall defeats automated logins. No further on-device
   login tuning will fix this.
2. The real-result logging worked exactly as designed — no more false
   "session refreshed".
3. A network outage also appeared (`UnknownHostException`) — unrelated flakiness,
   correctly surfaced in the trail.

## The New Architecture (v1.8.0) — Cloud Unlock Worker
The user-deployed Cloudflare Worker (stateless, free plan) does the unlock;
the device streams directly:

```
SparkTube ──(video ID + own session cookies)──▶ Worker (clean IP)
                                                 │ WEB/MWEB + SAPISIDHASH + cookies
SparkTube (direct stream) ◀── stream URLs ────── YouTube InnerTube
```

Key design decisions:
- **No password login in the worker** (datacenter IP automated logins get
  blocked even harder). Cookies come from the **interactive browser login**
  (`YouTubeLoginActivity` — the v1.7.8 WebView flow, re-exposed and now
  recommended): a real browser session passes risk walls, captured once.
- **Worker is stateless**: cookies travel with every request from the app.
  Nothing is stored server-side.
- **Cloud tier runs FIRST** in the unlock ladder when a worker URL is set
  (`cloud tier OK` / `cloud tier miss status=… attempts=…` in the trail).
- **Auto-play, no button**: once configured, 18+ videos resolve through the
  worker automatically. Unconfigured age-gate failures now show actionable
  Bangla guidance instead of a bare NewPipe message.

## App changes
1. `CloudUnlockClient` — worker HTTP client (cookies + visitor data headers).
2. Cloud tier first in `fetchUnlockedPlayerJson` (same deobfuscation pipeline).
3. Settings → Account dialog: **Browser login (recommended)**, **Cloud unlock —
   Worker URL (18+)**, Auto login (headless), Sign out.
4. `AppPrefs.workerUrl`; age-gate error hints on all three resolve paths.
5. Worker ships with the release: `SparkTube-Cloudflare-Worker/worker.js` +
   `DEPLOY-GUIDE-BN.md` (Bangla step-by-step).
6. Worker endpoints: `/unlock?v=…` (WEB then MWEB, SAPISIDHASH via
   `crypto.subtle`), `/stream?url=…` (Range-aware googlevideo proxy fallback),
   `/` health check.

## User setup (Bangla guide ships with the release)
1. Cloudflare dashboard → Workers & Pages → Create Worker → paste `worker.js` → Deploy.
2. Copy `https://<name>.<account>.workers.dev`.
3. App → Settings → Account → **Browser login** (one-time interactive login).
4. Settings → Account → **Cloud unlock — Worker URL** → paste URL → done.
   18+ videos now play automatically.

## Build & Release
```bash
./gradlew assembleRelease
# GitHub: tag v1.8.0 → CI builds the signed release APK
```
