# SparkTube v1.7.4 — Upgrade & Changelog

## What's Fixed & Upgraded in v1.7.4
1. **Menu / Settings Content Now Fully Scrollable (`activity_settings_page.xml`, `fragment_menu.xml`)**: The settings pages opened from the Menu (General / Video / Music) had a `match_parent` ScrollView inside a vertical LinearLayout, which pushed the bottom of the scroll area past the screen edge so the last grid cards could never be reached. It is now `0dp` + `weight=1` + `fillViewport`, ending exactly at the screen bottom. The Menu tab's own bottom padding was raised 118dp → 176dp so every grid card always rests above the floating nav bar and the mini player.
2. **Add to Playlist for Music (`PlaylistPicker.kt`, `MusicFragment.kt`, `NowPlayingActivity.kt`)**: Playlists existed but there was no way to file a song into one. Long-pressing any track in the Music tab (trending cards + Top Songs rows) now offers "Add to Playlist", and the Now Playing options sheet gained the same action. The picker lists every playlist with item counts, toggles membership (add / remove) with confirmation toasts, and "+ New Playlist" creates a playlist and files the song straight in.
3. **Player Settings Sheet Scroll Lag Fixed (`PlayerActivity.kt`)**: The reddish-glass settings grid was re-composited on every scroll frame over the playing video, which felt sticky on low-RAM devices. The sheet content now renders once into a hardware layer and scrolls as a GPU texture, the ScrollView's scroll cache and over-scroll glow are disabled, and the settings sheet opens fully expanded with drag locked so `BottomSheetBehavior` no longer fights the ScrollView for gestures. The queue / comment sheets keep their normal drag behavior.
4. **Shorts: Fresh Feed, Instant Swipes, Cache Wiped on Exit (`ShortsActivity.kt`, `PlaybackCenter.kt`, `SparkTubeApp.kt`)**: Shorts no longer show the same feed on every open — watched items are tracked for the session and filtered out (the pool recycles only when nothing unseen is left). While a short plays, the next 5 shorts' stream catalogs are pre-resolved on IO so the next swipe starts instantly through the prefetch cache. The prefetch session is wiped when the shorts screen closes and when the app leaves the foreground (activity count hits 0), so nothing keeps loading in the background.

## v1.7.3 (previous round, also included)
Shorts pager surface fix, SAPISID1P/3PHASH + MWEB signed-in age-gate unlock, Now Playing minimize button, reddish-glass player settings grid, search history filtering, menu/settings card grids, cancellation guards, AV muxing priorities + WebM + monotonic PTS.

## Local Build & GitHub Release
```bash
./gradlew assembleRelease
bash push_and_release.sh   # or: git push origin main && git tag v1.7.4 && git push origin v1.7.4
```
