package com.sparktube.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sparktube.app.net.OkHttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * GitHub release checker: compares the running version against
 * khad3m/SparkTubes (or devfahim00/SparkTube fallback) latest release
 * and surfaces title + changelog + APK download link.
 *
 * Update v1.7.0: the APK is downloaded INSIDE the app with a progress bar
 * ([downloadApk]) and handed straight to the system package installer —
 * the user never leaves the app for a browser.
 */
object UpdateChecker {

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val apkUrl: String?,
        val htmlUrl: String
    )

    private val LATEST_URLS = listOf(
        "https://api.github.com/repos/khad3m/SparkTubes/releases/latest",
        "https://api.github.com/repos/devfahim00/SparkTube/releases/latest"
    )

    /** Once per process: the silent on-open check must not nag repeatedly. */
    @Volatile
    var silentCheckDone = false

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        for (endpoint in LATEST_URLS) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                val tag = json.optString("tag_name", "").ifBlank { continue }
                val apk = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url")
                }
                return@withContext Release(
                    tag = tag,
                    name = json.optString("name").ifBlank { tag },
                    body = json.optString("body"),
                    apkUrl = apk,
                    htmlUrl = json.optString(
                        "html_url",
                        "https://github.com/khad3m/SparkTubes/releases"
                    )
                )
            } catch (_: Exception) {
            }
        }
        null
    }

    /** "v2.1.0" / "2.1" vs "2.0.0" — numeric part comparison, longer wins ties. */
    fun isNewer(current: String, tag: String): Boolean {
        val cur = current.removePrefix("v").removePrefix("V")
        val tg = tag.removePrefix("v").removePrefix("V")
        val curParts = cur.split('.').map { it.toIntOrNull() ?: 0 }
        val tgParts = tg.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(curParts.size, tgParts.size)
        for (i in 0 until n) {
            val c = curParts.getOrElse(i) { 0 }
            val t = tgParts.getOrElse(i) { 0 }
            if (t != c) return t > c
        }
        return false
    }

    // ----- In-app APK download & install -----

    /** Progress callback: bytesDownloaded, totalBytes (-1 when unknown). */
    fun interface ProgressListener {
        fun onProgress(bytes: Long, total: Long)
    }

    private fun updateDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Streams the release APK to this app's private `files/updates` folder,
     * reporting progress. Safe to call from any coroutine (runs on IO).
     */
    suspend fun downloadApk(
        context: Context,
        release: Release,
        onProgress: ProgressListener
    ): File? = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: return@withContext null
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OkHttpDownloader.USER_AGENT)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                val dest = File(updateDir(context), "SparkTube-${release.tag}.apk")
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastTick = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 200L) {
                            lastTick = now
                            onProgress.onProgress(done, total)
                        }
                    }
                    out.flush()
                }
                if (total > 0L && tmp.length() != total) {
                    tmp.delete()
                    return@withContext null
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                onProgress.onProgress(dest.length(), dest.length())
                dest
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Prompts the system package installer for the downloaded APK file. */
    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "com.sparktube.app.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    /** Housekeeping: remove finished/abandoned update files. */
    fun clearUpdateFiles(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    /** Legacy helper kept for text mentions: opens the release page in a browser. */
    fun openDownload(context: Context, release: Release) {
        val url = release.apkUrl ?: release.htmlUrl
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
