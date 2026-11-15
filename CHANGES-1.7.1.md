# SparkTube v1.7.1 — Changelog

**Release focus: crash fix, AV-download audio repair, real Shorts feed (country + account
personalization), premium chip-grid UI everywhere.**

## 1. Search crash fix
- Every view lookup in the Search mini-player bar is now null-safe and failure-isolated
  (`MiniPlayerBar` v1.7.1): if anything fails on a specific device the bar hides itself and
  Search keeps working — the bar can no longer crash the screen.
- The "open playlist" dialog is now guarded against being shown after the activity is gone
  (`BadTokenException`), and the bar construction is wrapped so Search always launches.

## 2. Premium chip-grid UI (player, menus, settings)
- The whole player control set now uses the Audio-Studio pill style:
  **Speed (0.5x–3x), Video Quality, Audio Track, Subtitle mode** are premium chip grids
  with the active choice highlighted.
- Settings pages rebuilt with inline premium chip grids: **Theme, Accent colour,
  Default quality (Wi-Fi / Data), Subtitle language, Music audio quality, Crossfade** —
  no more dialogs; one tap switches and the grid re-renders live.
- Menu tab keeps the Audio Studio sheet (Sleep Timer / EQ / Crossfade as pill grids).

## 3. Video+Audio download now really has sound in MX Player
- `AvMuxer` hardening:
  - validates BOTH source files before muxing (a broken/truncated audio download can no
    longer produce a silent .mp4);
  - **verifies the merged file actually contains a video track AND an audio track** before
    accepting it — MediaMuxer silently dropping an unsupported audio track was producing
    exactly the "video plays, no sound" files;
  - retries the merge once if verification fails; on failure the two source files are kept.
- The audio side for AV downloads now **prefers AAC/M4A streams** (Opus/WebM audio muxes
  unreliably into MP4 across devices) and the audio sidecar gets its REAL container
  extension (`.m4a`/`.webm`) so even the fallback two-file case plays everywhere.

## 4. Shorts / Reels: real feed, country- and account-aware
- The shorts screen now uses the InnerTube shorts endpoints (the same one the youtube.com
  shorts tab uses — FEshorts browse is no longer served anonymously):
  - `gl` follows the app's country preference (Bangladesh → BD feed);
  - **when Google-signed-in, session cookies + SAPISIDHASH are attached**, so the reels
    follow the account's personalization (user-specific shorts);
  - multiple discovery queries are merged for a feed feel, with trending + search
    fallbacks so the screen is never empty.
- The Search → Shorts tab uses the same real shorts rows (title, view-count, vertical
  cover) instead of duration-filtered video results.
