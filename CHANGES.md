# v1.5.4 — Dual Audio/Video Upper-Left Switcher, Card Grid Profile (Md Khademul Islam) & Premium Home/Player

## Dual Audio / Video Switcher (Upper-Left Corner)
- Both **Video Player (`PlayerActivity`)** and **Music / Audio Player (`NowPlayingActivity`)** now feature a smoked-glass dual mini capsule in the upper-left corner with two buttons: **Audio** (`miniMusicBtn`) and **Video** (`miniVideoBtn`).
- In **Video Player**, tapping the **Audio** mini button seamlessly converts the current playback stream to Audio mode (`PlaybackCenter.switchToAudioPlayerMode()`) at the exact current timestamp and opens `NowPlayingActivity`.
- In **Music / Audio Player**, tapping the **Video** mini button seamlessly switches the current song/track to full Video mode (`PlaybackCenter.switchToVideoPlayerMode()`) at the exact current timestamp and opens `PlayerActivity`.

## Settings Panel Card Grid & Hero Profile
- Redesigned **Menu / Settings (`fragment_menu.xml` & `MenuFragment.kt`)** into a modern **Card Grid** layout.
- Added a full-width glassmorphic **Hero Profile Card** at the top displaying the uploaded portrait (`@drawable/profile_khademul`) inside a glowing gradient ring (`116dp`), badge (`PRO CREATOR · SPARKTUBE`), and large bold name **`Md Khademul Islam`** (`24sp`).
- Organized **Country / Region**, **Settings**, **Video settings**, and **Music settings** into a 2×2 tactile glass Card Grid with spring press animations.

## Premium YouTube Homepage & Watch Page Polish
- Upgraded **Home (`fragment_home.xml` & `HomeFragment.kt`)** with brand play badge, country flag glass pill, header profile avatar chip (`Md Khademul Islam`), `4K · AD-FREE` glass search bar, and interactive category filter chips (`All`, `Music`, `Gaming`, `Tech & AI`, `Podcasts`, `Movies`).
- Upgraded **Video Cards (`item_video.xml` & `VideoAdapter.kt`)** with 18dp rounded thumbnails, bottom gradient vignette, channel avatar initials fallback, and a top-left quick **Audio** play badge.
- Upgraded **Video Watch Page (`activity_player.xml`)** with live quality/speed badge (`HD · 1.0x`), frosted glass channel bar, and refined action pills.

---

# v1.5.3 — Glass home & player, gestures, new icon

## Player (YouTube videos)
- Brightness (left) and volume (right) swipe gestures with a glass HUD. Landscape uses the whole video;
  portrait uses the outer thirds so swipe-down-in-the-middle still minimizes to the mini player.
- Custom controller: previous/next removed, play/pause dead-centre of the video with back-10 / forward-10
  either side (seek step is now really 10 s), smoked-glass buttons, glass bar with time, scrubber and settings.
- Fullscreen / scale / back buttons are glass circles with 48 dp touch targets.

## Home & shared surfaces
- Glass video cards (rounded thumbnail, smoked-glass duration pill, press feedback) used by Home, Search,
  Channel, Playlist and Related; skeleton cards match exactly.
- Home header: large title + one big glass search field.
- Floating nav bar, mini player, action pills, description/comment cards, chips and library tiles are glass;
  the selected tab gets an accent glass pill.
- Soft brand-red ambient background behind every main screen so the glass has depth.

## Music polish (from 1.5.2 follow-up)
- Pull-to-refresh, tap-active-tab-to-scroll-top, contrast-safe accent text, 44 dp touch targets,
  entrance animation only on first load, accessibility labels.

## App icon
- New adaptive icon: red rounded play mark with a spark on a smoked-graphite background, a themed
  (monochrome) layer for Android 13+, and a flat fallback for Android 7. 512 px store icon in docs/.

---

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
