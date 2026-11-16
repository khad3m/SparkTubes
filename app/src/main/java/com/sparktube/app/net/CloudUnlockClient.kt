package com.sparktube.app.net

import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.DiagnosticsLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.8.0 Cloud unlock tier — talks to the user-deployed Cloudflare Worker
 * (deploy guide ships with the release). The worker is STATELESS: the device
 * sends its stored YouTube session cookies with every request, the worker
 * builds the signed-in InnerTube player call from a clean IP and returns the
 * raw player JSON. This tier exists because the on-device headless login
 * engine cannot complete on many devices (both 30 s and 60 s budgets time out
 * — proven by diagnostics logs), while the cookies captured once by the
 * interactive browser login (YouTubeLoginActivity) keep working server-side.
 */
object CloudUnlockClient {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Raw InnerTube player JSON from the worker, or null. Caller validates playability.
     * Blocking call — invoke from an IO context (fetchUnlockedPlayerJson's callers
     * already wrap it in Dispatchers.IO / runBlocking, same as fetchFallbackPlayer).
     * v1.8.1: the worker holds its own YouTube session (YT_COOKIES secret), so the
     * device no longer needs local cookies — they are attached when present and the
     * worker prefers them, falling back to its own session. A browser User-Agent is
     * REQUIRED: workers.dev WAF (error 1010) blocks the okhttp UA.
     */
    fun fetchPlayerJson(videoId: String): JSONObject? {
        val base = AppPrefs.workerUrl.trim()
        if (base.isBlank() || !base.startsWith("https://")) return null
        val cookies = AppPrefs.youtubeCookies
        return runCatching {
            val req = Request.Builder()
                .url("$base/unlock?v=$videoId&gl=${AppPrefs.countryOrDefault}")
                .header("User-Agent", YouTubeAuth.UA_WEB_CHROME)
                .apply {
                    if (cookies.isNotBlank()) header("X-Cookies", cookies)
                    val visitor = YouTubeAuth.visitorData(allowNetworkFetch = false).orEmpty()
                    if (visitor.isNotBlank()) header("X-Visitor-Data", visitor)
                }
                .post(
                    JSONObject().put("videoId", videoId).toString().toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DiagnosticsLogger.log("W", "Cloud", "worker HTTP ${resp.code} vid=$videoId")
                    return@use null
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@use null
                JSONObject(text)
            }
        }.onFailure {
            DiagnosticsLogger.log("W", "Cloud", "worker call failed vid=$videoId err=${it.message?.take(100)}")
        }.getOrNull()
    }
}
