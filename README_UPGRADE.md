# SparkTube v1.8.1 — Fully Managed Cloud Unlock (Zero Setup) (`versionCode = 40`)

## What changed vs v1.8.0
v1.8.0 shipped the cloud unlock architecture but required the user to deploy a
Cloudflare worker and log in interactively. v1.8.1 completes the setup
end-to-end **for the user**:

1. **Google session established server-side** — the project worker now holds a
   fresh, age-verified YouTube session (SID/HSID/SSID/SAPISID/__Secure-*PSID…,
   captured via a real interactive browser flow with TOTP).
2. **Worker deployed and live**: `https://sparktube-unlock.khadimsorder1.workers.dev`
   - `/unlock?v=<id>` — WEB client with the secret session (verified live:
     `status=OK, session=worker, WEB:OK, formats 1+20` on the exact age-gated
     video from the diagnostics logs, `52h_AS2fEl8`).
   - Session priority: device cookies (if fresh) → worker secret session.
   - `/stream?url=…` Range-aware googlevideo proxy fallback.
3. **Worker URL baked as the default** (`AppPrefs.DEFAULT_WORKER_URL`) — the
   app ships pre-configured; Settings → Account → Cloud unlock can override it
   or restore the default.
4. **No local login required for 18+**: `CloudUnlockClient` calls the worker
   even with no local cookies (X-Cookies attached only when present).
5. **Browser User-Agent on worker calls** — workers.dev WAF (error 1010)
   blocks the okhttp UA; `UA_WEB_CHROME` is now sent.
6. Age-gate error hint updated: cloud tier is automatic; Diagnostics shows the
   `cloud tier OK / miss` lines.

## Verification chain (all done live this release)
- Stealth browser Google login (headed Chromium, `AutomationControlled`
  disabled) → password + Google Authenticator TOTP accepted.
- InnerTube test with the fresh cookies: `WEB: OK (1+20)`, `MWEB: OK (1+25)`.
- Cloudflare: account created via Google SSO, API token via dashboard,
  worker uploaded via REST API, `YT_COOKIES` set as a secret,
  workers.dev route enabled, live `/unlock` test passed.

## Fallbacks (unchanged)
On-device unlock ladder still runs when the worker is unreachable; the
interactive Browser login (Settings → Account) refreshes local cookies; the
headless auto-login remains as the last local option.
