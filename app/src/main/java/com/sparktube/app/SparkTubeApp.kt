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
        DeviceTuning.init(this)
        PlaybackCenter.init(this)
        Themes.applyDefaultNightMode()

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
