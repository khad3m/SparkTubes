package com.sparktube.app.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap

/**
 * Device tuning profile, computed once:
 * - Low-RAM devices (< 2 GB heap class / ActivityManager.isLowRamDevice) get a
 *   lighter playback buffer, smaller stream caches and a smaller image memory
 *   cache so the app stays responsive on 32-bit / 2 GB hardware.
 *
 * [init] is called from Application.onCreate; the cached no-arg getters are
 * safe to use from anywhere afterwards (they fall back to "not low RAM").
 */
object DeviceTuning {

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var cachedLowRam: Boolean? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    fun lowRam(context: Context? = appCtx): Boolean {
        cachedLowRam?.let { return it }
        val ctx = context ?: return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLow = am.isLowRamDevice ||
            (Runtime.getRuntime().maxMemory() / (1024L * 1024L)) <= 256L
        cachedLowRam = isLow
        return isLow
    }

    /** Coil memory cache size as a fraction of total app RAM. */
    fun imageMemoryCachePercent(context: Context): Float =
        if (lowRam(context)) 0.05f else 0.12f

    /** Small bounded disk cache for thumbnails/remote artwork. Keep it deliberately
     * tiny on 2 GB/32-bit phones so app cache cannot silently consume storage. */
    fun imageDiskCacheBytes(context: Context): Long =
        if (lowRam(context)) 24L * 1024L * 1024L else 64L * 1024L * 1024L

    /** Metadata/feed cache: enough for instant warm start, never a large offline database. */
    fun feedCacheTtlMs(context: Context): Long =
        if (lowRam(context)) 2 * 60_000L else 5 * 60_000L

    fun downloadThreads(context: Context): Int = if (lowRam(context)) 3 else 6

    fun maxConcurrentDownloads(context: Context): Int = if (lowRam(context)) 1 else 3

    /** RGB_565 halves every thumbnail's memory; quality loss is invisible at list sizes. */
    fun imageBitmapConfig(context: Context): Bitmap.Config =
        if (lowRam(context)) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
}
