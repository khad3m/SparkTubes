# SparkTube 1.9.2

## Player / Fullscreen
- Added dedicated Fit / Fill / Stretch aspect-ratio control to fullscreen player.
- The control is visible only in landscape while the player controller is visible.
- Positioned directly to the left of the fullscreen exit/enter icon in the upper-right corner.
- Replaced the generic tune icon with a clear aspect-ratio icon.
- Existing persisted Fit / Crop / Stretch behavior is preserved: Fit = whole video, Crop = screen-filling zoom, Stretch = full-screen fill with possible distortion.

## Restricted playback architecture
- Keeps the age/login-gated route direct to the real Android WebView session; no legacy cloud unlock ladder is reintroduced.
- Existing WebView PO-token/browser-side playback path remains intact.

## Low-RAM target
- Based on the supplied 1.9.1 low-RAM/UI-optimized source.
- No new always-running service or large cache was added by this update.

Note: Gradle APK compilation could not be completed in this offline environment because the Gradle 8.7 distribution was not cached and the environment could not reach services.gradle.org. Source changes were made and packaged.
