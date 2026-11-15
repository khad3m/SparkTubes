# SparkTube v1.7.3 — Upgrade & Changelog

## What's Fixed & Upgraded in v1.7.3
1. **Shorts Playback Fixed (`ShortsActivity.kt`)**: Fixed `ShortsPagerAdapter.pages` (previously allocated as a zero-length array before the feed loaded) to use a dynamic `HashMap`, ordered `PlaybackCenter.playVideo()` before `attachView()` so the ExoPlayer surface binds reliably, and added proper surface detachment on `onDestroy()`.
2. **Google Login & Age-Restricted (18+) Playback (`YouTubeAuth.kt`, `YouTubePlayerUnlock.kt`)**: Fixed `SAPISIDHASH` / `SAPISID1PHASH` / `SAPISID3PHASH` per-cookie hashing, added the `MWEB` client matching Mobile Web login cookies, prevented cross-client `playbackContext`/`serviceIntegrityDimensions` leakage into `WEB`/`MWEB`/`TVHTML5` fallbacks, and prioritized direct-URL signed-in clients.
3. **Music UI Minimize Button (`activity_now_playing.xml`)**: Replaced the stretched `bg_sheet_handle` box beside the Audio/Video toggle with a crisp `36dp` circular glass minimize chevron button (`bg_np_glass_circle_inset` + `ic_chevron_up` rotated 180°).
4. **Fullscreen Player Settings Grid (`activity_player.xml`, `PlayerActivity.kt`, `bg_glass_reddish_*.xml`)**: Removed redundant `lockButton`, `ccButton`, `speedButton`, and `scaleButton` from the player top bar and redesigned the Settings sheet as a 2-column icon grid with a semi-transparent reddish glass tone.
5. **Search History Filtering (`SearchActivity.kt`)**: Shows recent search history when the input is empty, and immediately filters out non-matching history items as soon as typing begins (from the first character).
6. **Menu & Settings Grid UI (`fragment_menu.xml`, `MenuFragment.kt`, `SettingsPageActivity.kt`)**: Expanded Menu into a balanced 2x3 icon grid (adding Google Account & Check Updates cards) and converted General, Video, and Music settings rows inside `SettingsPageActivity` into 2-column icon-card grids.
7. **Full Source Code Audit**: Added `CancellationException` rethrow guards across coroutine `catch (e: Exception)` blocks in `SearchActivity`, `PlayerActivity`, `ChannelActivity`, and `YtRepository`.
8. **Download Video+Audio Muxing (`AvMuxer.kt`, `DownloadSheet.kt`, `DownloadCenter.kt`)**: Strictly prioritized H.264/AVC over AV1 for MP4 muxing, added `MUXER_OUTPUT_WEBM` support for VP9+Opus pairs, enforced monotonic presentation timestamps, added sample-level A/V verification, and added a `copyTo` fallback if `renameTo` fails on Scoped Storage.

## Local Build & GitHub Release
```bash
./gradlew assembleDebug
bash push_and_release.sh
```
