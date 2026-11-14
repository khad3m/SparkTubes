# SparkTube v1.5.1 — Polish, bug fixes, RAM

## Bugs fixed
- Lyrics/info card was drawn *under* the artwork (elevation) and ignored the paused-art scale; now sits above, square, rounded, scales with the art.
- Scrolling the lyrics card could collapse the player; now ignored while touching it.
- Long-press "Favorite" started playback and could favorite the wrong track; it now only toggles the track that is actually loaded.
- Country change in Menu was cancelled if you switched tab; now survives, and the Music tab reloads for the new country.
- Music tab: skeleton animators + load job leaked after the view was destroyed.
- Volume slider jittered while dragging (polled every tick).
- Returning to Now Playing could show stale state (listener re-added but nothing re-bound).
- Music load errors had no way to retry — added "Try again".
- Wrong icons: volume row used a headset icon, "more" button used a hamburger icon.
- Removed fake/misleading UI claims: "Lossless", "Apple Digital Master", "24-bit ALAC", "Synced Lyrics", and a non-working Playback Speed row.

## RAM / performance
- Backdrop color decode was full-size thumbnail on every track change; now 48px, cancelled on track change, no extra scaled bitmap.
- Art + backdrop only reload when the track really changes (not on every play/pause/queue event).
- Music and Menu tabs are detached when hidden (views + bitmaps freed); Music re-binds from cache with no network.
- Mini player no longer reloads its thumbnail on every state change.
- Progress loop: 250 ms only while playing, 1 s when paused; download/disk checks at most 1/s. Scrubber animates smoothly.
- One shared ActionSheet instead of hand-built dialogs.

## UI/UX
- Interactive drag-down-to-dismiss on Now Playing (follows finger, fling or spring back).
- Cross-fading ambient backdrop, thumbless iOS-style scrubber + volume bars with larger touch area.
- Ranked chart now has its own rank column; iOS-style grouped action sheet.

---
# v1.5.2 — Glass + red

- Accent switched to YouTube red (#FF0033); genre tiles and editorial cards re-tinted to a red/graphite family.
- Glassmorphism: frosted gradient surfaces with a hairline highlight for the search field, library pills, profile badge, Now Playing buttons / action bar / Up Next / badge. Light + dark variants (values/ and values-night/glass_colors.xml).
- Action sheet is now a frosted sheet with large top corners, with real background blur on Android 12+.
- Now Playing ambient backdrop is pulled slightly toward brand red.
- iOS-like press feedback (scale + spring) on cards, rows, pills, tiles; staggered fade/rise entrance on the Music tab (respects the Animations setting).
