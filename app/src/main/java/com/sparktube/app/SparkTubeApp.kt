package com.sparktube.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.CrashReporter
import com.sparktube.app.util.DeviceTuning
import com.sparktube.app.util.Themes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class SparkTubeApp : Application(), ImageLoaderFactory {

    /** Open activities — when it hits 0 the app has left the foreground. */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // Local crash reporting first — installed before anything else so a
        // crash during init (prefs, players, extractor) is still logged.
        //
        // This replaces Firebase Crashlytics: google-services.json (which
        // contains the Firebase web API key) used to live in this open-source
        // repo, so GitHub secret scanning flagged the key on every push — an
        // API key in public source can never be kept secret. Crash logs are
        // now written to a "SparkTube" folder on the user's device instead;
        // see util/CrashReporter.kt and Settings → Crash logs.
        CrashReporter.install(this)

        AppPrefs.init(this)
        // v1.7.12/13: full diagnostics trail — installed after prefs init so the
        // session header carries the real country; chains crash logging too.
        com.sparktube.app.util.DiagnosticsLogger.install(this)
        DeviceTuning.init(this)
        PlaybackCenter.init(this)
        Themes.applyDefaultNightMode()

        // v1.7.4: when the app is left entirely (home / recents / screen off),
        // the shorts preload session ends and its cache is wiped — nothing
        // keeps streaming in the background.
        registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivities++
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivities--
                    if (startedActivities <= 0) {
                        PlaybackCenter.clearShortsPrefetch()
                    }
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: android.os.Bundle?
                ) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
        )

        CrashReporter.logBreadcrumb(
            "SparkTube ${BuildConfig.VERSION_NAME} started"
        )

        NewPipe.init(
            OkHttpDownloader,
            Localization("en", "US"),
            ContentCountry(AppPrefs.countryOrDefault)
        )
        // Downloads now run through the app's own multi-thread engine
        // (DownloadCenter), so there is no system DownloadManager broadcast
        // to listen for anymore.
    }

    /**
     * Low-RAM aware image loader: smaller memory cache and RGB_565 bitmaps on
     * constrained devices so thumbnail lists never push the app near its heap.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(DeviceTuning.imageMemoryCachePercent(this).toDouble())
                    .build()
            }
            .bitmapConfig(DeviceTuning.imageBitmapConfig(this))
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release thumbnail bitmaps as soon as the system asks: this is what
        // keeps background memory low on 2 GB devices.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            imageLoader.memoryCache?.trimMemory(level)
        }
    }
}
