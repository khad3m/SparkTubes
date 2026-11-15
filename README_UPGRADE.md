# SparkTube v1.7.5 — Upgrade & Changelog

## What's New in v1.7.5 (latest source export)
1. **SparkAudioProcessor — Real-Time Vocal & Noise Engine (`playback/SparkAudioProcessor.kt`, integrated in `PlaybackCenter.kt`)**: A new Media3 `BaseAudioProcessor` chain: a 1024-point Hann-windowed 50%-overlap STFT spectral vocal isolator (per-bin Mid/Side Wiener center-channel masking, < 300 Hz bass and > 4.5 kHz cymbal hard-cuts, vocal-formant gate) plus a noise-cancel mode (85 Hz rumble high-pass + 7.2 kHz hiss low-pass + smooth gate).
2. **New EQ Presets (`NowPlayingActivity.kt`)**: The equalizer sheet gained 🔊 Speaker Box, 🎛 Flat, 💥 Bass Boost, 🎙 Vocal Studio (drives the vocal isolator), ⚡ Club and ✨ Treble — mapped through the new processor in PlaybackCenter.
3. **Now Playing Audio-First Polish**: The lyrics button/overlay is replaced by audio-focused info (LOSSLESS · AUDIO badge) to keep the player lightweight.
4. **Music Playlists Manager (`MusicFragment.kt`)**: The Playlists button now opens a manage dialog — "+ Create New Playlist" plus every playlist with item counts (play / manage in one place, alongside the long-press "Add to Playlist" picker from v1.7.4).
5. **Streaming & Unlock Engine (`YtRepository.kt`, `YouTubePlayerUnlock.kt`, `YouTubeAuth.kt`)**: Refined stream resolution and signed-in unlock paths; extractor served through the local `newpipeextractor-0.26.5-android-compat.jar`.
6. **Smoothness (`Motion.kt`, `SkeletonAdapter.kt`, `HomeFragment.kt`, `MainActivity.kt`)**: Touch feedback and skeleton list polish; menu tab padding raised further (210dp) so cards always rest above the nav bar.

## v1.7.4 (previous round, retained)
Menu/Settings scroll bounds + nav overlap fix, Music Add-to-Playlist picker (track sheet + Now Playing), player settings sheet GPU-layer scroll fix, shorts seen-dedupe + next-5 prefetch + cache wipe on exit.

## v1.7.3 (also retained)
Shorts pager surface fix, SAPISID1P/3PHASH + MWEB signed-in age-gate unlock, Now Playing minimize button, reddish-glass player settings grid, search history filtering, menu/settings card grids, cancellation guards, AV muxing priorities + WebM + monotonic PTS.

## Local Build & GitHub Release
```bash
./gradlew assembleRelease
bash push_and_release.sh   # or: git push origin main && git tag v1.7.5 && git push origin v1.7.5
```
