# SparkTube 1.6.4 - fixes

1. Age-restricted videos: signed-in session now tried with TVHTML5 / embedded clients first,
   signatureTimestamp reused, cipher formats accepted. Logs: `adb logcat -s PlayerUnlock`.
2. Floating nav: more opaque + stronger border + darker inactive icon tint.
3. Settings/Menu overlap: bottom padding is now measured from the real nav + mini player height.
4. Up next / Related: search-based fallback when YouTube returns no related list.
5. Download sheet: file size shown for audio, video and video+audio rows.
6. Video+Audio download: video and audio are merged into ONE .mp4 (works in MX Player / Gallery).
7. Equalizer: real per-band presets, saved across restarts, applied to every track.
8. Lyrics: smarter title/artist cleaning, LRCLIB exact lookup + fallbacks, retry on open.

## UI/UX polish (spacing + motion only, look unchanged)
- Press-scale feedback on music rows, download rows, channel rows, playlists, suggestions and nav tabs.
- Selected nav tab icon springs in when you switch tabs (respects "animations" setting).
- Touch targets raised to 44dp: download play button, suggestion delete, fullscreen-grid close, unsubscribe.
- Music tab side margins 20dp -> 16dp to match Home / Menu / Library.

## Pro UX features
- Mini player: swipe UP opens the full player; swipe LEFT/RIGHT dismisses it (card follows the finger, snaps back if released early).
- Haptic tick on nav tab taps and mini player play/pause.
- Download complete notification (title + "Open" action that plays the file in your player; tap opens SparkTube). Needs notification permission on Android 13+.
- Library empty state: icon + roomier card.

## Player gestures + music controls
Video player:
- Horizontal swipe = scrub with live target time label, seeks on release.
- Double-tap seek now chains: keep tapping within 0.8s for +20s, +30s ... (label shows the total).
- Touches that start at the screen edge are ignored (no more accidental brightness/volume while using back-swipe).
- Haptic tick when brightness/volume reaches min or max.
Music Now Playing:
- Swipe album art left/right = next/previous (art slides out and in). Double-tap art left/right = -10s/+10s.
- NEW Shuffle and Repeat (off / all / one) buttons, saved across restarts (audio mode only).
