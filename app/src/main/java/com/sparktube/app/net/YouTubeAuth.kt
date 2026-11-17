package com.sparktube.app.net

import android.webkit.CookieManager
import android.webkit.WebView
import com.sparktube.app.util.AppPrefs
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Anonymous YouTube session context (v1.9.5: Google login removed): consent
 * cookies, `X-Goog-Visitor-Id`, WebView CookieManager syncing, and client-matched
 * `googlevideo.com` playback/download headers. No Google account state exists.
 */
object YouTubeAuth {

    const val UA_WEB_CHROME =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val UA_WEB_CREATOR =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val UA_WEB_SAFARI =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

    const val UA_MWEB_IPAD =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"

    const val UA_TV_SAMSUNG =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 " +
            "(KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    const val UA_TV_COBALT =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
            "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

    const val UA_ANDROID_VR =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val UA_VISIONOS =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    const val UA_IOS =
        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    const val UA_ANDROID =
        "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"

    const val UA_TV_EMBED =
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/15.4 Safari/605.1.15"

    val COOKIE_DOMAINS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://music.youtube.com",
        "https://accounts.youtube.com",
        "https://youtube.com",
        "https://accounts.google.com",
        "https://www.google.com"
    )

    fun parseCookieMap(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (raw.isBlank()) return map
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty() &&
                    !key.equals("Path", ignoreCase = true) &&
                    !key.equals("Domain", ignoreCase = true) &&
                    !key.equals("Expires", ignoreCase = true) &&
                    !key.equals("Max-Age", ignoreCase = true) &&
                    !key.equals("SameSite", ignoreCase = true)
                ) {
                    map[key] = value
                }
            }
        }
        return map
    }

    /**
     * Returns the live Android [CookieManager] jar cookies (anonymous session:
     * CONSENT/SOCS and any cookies the WebView unlock path picked up).
     */
    fun effectiveCookieMap(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        map["CONSENT"] = "YES+cb"
        map["SOCS"] = "CAI"
        runCatching {
            val cm = CookieManager.getInstance()
            for (domain in COOKIE_DOMAINS) {
                val live = cm.getCookie(domain).orEmpty()
                if (live.isNotBlank()) {
                    map.putAll(parseCookieMap(live))
                }
            }
        }
        return map
    }

    /** Google account login was removed in v1.9.5 — sessions are anonymous-only. */
    /**
     * Injects every saved cookie into Android's [CookieManager] ONE COOKIE AT A TIME.
     * Note: `CookieManager.setCookie(url, value)` parses `value` as a single `Set-Cookie`
     * header where anything after the first `;` is treated as attributes for the first
     * cookie — so each `name=value` pair MUST be set in its own `setCookie` call.
     */
    fun syncCookiesToWebView(webView: WebView? = null) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (webView != null) {
                runCatching { cm.setAcceptThirdPartyCookies(webView, true) }
            }
            val cookies = effectiveCookieMap()
            if (cookies.isEmpty()) return@runCatching
            val ytDomains = listOf(
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://music.youtube.com",
                "https://accounts.youtube.com",
                "https://youtube.com"
            )
            for (domain in ytDomains) {
                for ((k, v) in cookies) {
                    if (k.isNotBlank() && v.isNotBlank()) {
                        runCatching {
                            cm.setCookie(domain, "$k=$v; Domain=.youtube.com; Path=/; Secure")
                        }
                    }
                }
            }
            runCatching { cm.flush() }
        }
    }

    fun clearSession() {
        AppPrefs.youtubeVisitorData = ""
        AppPrefs.youtubeDatasyncId = ""
        AppPrefs.youtubeSessionIndex = ""
        cachedVisitorData = null
        cachedVisitorDataAt = 0L
        cachedDatasyncId = null
        cachedSessionIndex = null
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    fun buildCookieHeader(existingCookie: String? = null): String {
        val merged = LinkedHashMap<String, String>()
        merged["CONSENT"] = "YES+cb"
        merged["SOCS"] = "CAI"
        if (!existingCookie.isNullOrBlank()) {
            merged.putAll(parseCookieMap(existingCookie))
        }
        merged.putAll(effectiveCookieMap())
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var cachedVisitorDataAt: Long = 0L
    @Volatile private var lastVisitorFetchAttemptAt: Long = 0L
    @Volatile private var cachedDatasyncId: String? = null
    @Volatile private var cachedSessionIndex: String? = null

    private val visitorClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    fun updateSessionMetadata(
        visitorData: String? = null,
        datasyncId: String? = null,
        sessionIndex: String? = null,
        sts: Int? = null
    ) {
        val now = System.currentTimeMillis()
        if (!visitorData.isNullOrBlank()) {
            cachedVisitorData = visitorData
            cachedVisitorDataAt = now
            AppPrefs.youtubeVisitorData = visitorData
        }
        if (!datasyncId.isNullOrBlank()) {
            cachedDatasyncId = datasyncId
            AppPrefs.youtubeDatasyncId = datasyncId
        }
        if (!sessionIndex.isNullOrBlank()) {
            cachedSessionIndex = sessionIndex
        }
        if (sts != null && sts > 0) {
            AppPrefs.youtubeSts = sts
        }
    }

    /**
     * Passively harvests `visitorData` and `datasyncId` from any `/youtubei/v1/` response JSON.
     */
    fun sniffInnerTubeResponse(rawJson: String) {
        if (rawJson.isBlank() || !rawJson.contains("responseContext")) return
        runCatching {
            Regex("\"visitorData\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").find(rawJson)
                ?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { vd -> updateSessionMetadata(visitorData = vd) }

            Regex("\"datasyncId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").find(rawJson)
                ?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { ds -> updateSessionMetadata(datasyncId = ds) }
        }
    }

    fun visitorData(allowNetworkFetch: Boolean = true): String? {
        val now = System.currentTimeMillis()
        cachedVisitorData?.let { if (now - cachedVisitorDataAt < 30 * 60_000L) return it }
        val persisted = AppPrefs.youtubeVisitorData
        if (persisted.isNotBlank() && cachedVisitorData == null) {
            cachedVisitorData = persisted
            cachedVisitorDataAt = now - 15 * 60_000L
        }
        if (!allowNetworkFetch) return cachedVisitorData
        if (now - lastVisitorFetchAttemptAt < 45_000L && cachedVisitorData != null) {
            return cachedVisitorData
        }
        if (now - lastVisitorFetchAttemptAt < 20_000L) {
            return cachedVisitorData
        }
        lastVisitorFetchAttemptAt = now

        // 1) Fast InnerTube /youtubei/v1/visitor_id or /youtubei/v1/guide JSON POST
        val guidePayload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", "2.20260708.00.00")
                        .put("hl", "en")
                        .put("gl", AppPrefs.countryOrDefault.ifBlank { "US" })
                )
            )
            .toString()

        for (ep in listOf("visitor_id", "guide")) {
            val vd = runCatching {
                val url = "https://www.youtube.com/youtubei/v1/$ep?prettyPrint=false"
                val rb = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA_WEB_CREATOR)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://www.youtube.com")
                    .header("X-Origin", "https://www.youtube.com")
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", "2.20260708.00.00")
                    .header("Cookie", buildCookieHeader())
                rb.post(guidePayload.toRequestBody(JSON_MEDIA_TYPE))
                visitorClient.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string().orEmpty()
                    sniffInnerTubeResponse(body)
                    val json = JSONObject(body)
                    json.optJSONObject("responseContext")?.optString("visitorData")
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!vd.isNullOrBlank()) {
                updateSessionMetadata(visitorData = vd)
                return vd
            }
        }

        // 2) Fallback: fetch ytcfg from /tv or /embed/ or /sw.js_data
        val fromWeb = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/tv")
                .header("User-Agent", UA_TV_SAMSUNG)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", buildCookieHeader())
                .build()
            visitorClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val vd = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"")
                    .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val ds = Regex("\"(?:DATASYNC_ID|datasyncId)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"")
                    .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val si = Regex("\"SESSION_INDEX\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val sts = Regex("\"STS\"\\s*:\\s*(\\d+)")
                    .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                updateSessionMetadata(visitorData = vd, datasyncId = ds, sessionIndex = si, sts = sts)
                vd
            }
        }.getOrNull()
        if (!fromWeb.isNullOrBlank()) return fromWeb

        // 3) JSPB sw.js_data array fallback
        val fromSw = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", UA_WEB_CHROME)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", buildCookieHeader())
                .build()
            visitorClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                Regex("\"(Cg[A-Za-z0-9_-]{12,}(?:%3D)*)\"").find(body)
                    ?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        if (!fromSw.isNullOrBlank()) {
            updateSessionMetadata(visitorData = fromSw)
            return fromSw
        }

        return cachedVisitorData
    }

    fun applyYouTubeAuthToRequest(
        url: String,
        builder: Request.Builder,
        existingCookies: List<String> = emptyList()
    ) {
        val lower = url.lowercase(Locale.US)
        val isGoogleApi = lower.contains("youtubei.googleapis.com")
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be") && !isGoogleApi) return

        // Do not attach web cookies to mobile-only youtubei.googleapis.com ANDROID requests
        if (isGoogleApi) return

        val origin = when {
            lower.contains("music.youtube.com") -> "https://music.youtube.com"
            lower.contains("m.youtube.com") -> "https://m.youtube.com"
            else -> "https://www.youtube.com"
        }

        val combinedExisting = existingCookies.joinToString("; ")
        val cookieHeader = buildCookieHeader(combinedExisting)
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }

        // v1.9.5: anonymous-only session (Google login removed)
        visitorData(allowNetworkFetch = false)?.let { builder.header("X-Goog-Visitor-Id", it) }
        builder.header("X-Origin", origin)
        builder.header("Origin", origin)
        builder.header("X-Goog-Api-Format-Version", "1")
    }

    /**
     * Returns client-matched HTTP headers (`User-Agent`, `Origin`, `Referer`)
     * for `googlevideo.com` CDN stream URLs so ExoPlayer and DownloadCenter never get HTTP 403.
     */
    fun streamHeadersForUrl(url: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val clientParam = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.uppercase(Locale.US).orEmpty()

        when {
            clientParam == "ANDROID_VR" -> {
                headers["User-Agent"] = UA_ANDROID_VR
            }
            clientParam == "VISIONOS" -> {
                headers["User-Agent"] = UA_VISIONOS
            }
            clientParam == "IOS" -> {
                headers["User-Agent"] = UA_IOS
            }
            clientParam.startsWith("ANDROID") -> {
                headers["User-Agent"] = UA_ANDROID
            }
            clientParam == "MWEB" -> {
                headers["User-Agent"] = UA_MWEB_IPAD
                headers["Origin"] = "https://m.youtube.com"
                headers["Referer"] = "https://m.youtube.com/"
            }
            clientParam == "WEB_CREATOR" -> {
                headers["User-Agent"] = UA_WEB_CREATOR
                headers["Origin"] = "https://studio.youtube.com"
                headers["Referer"] = "https://studio.youtube.com/"
            }
            clientParam == "WEB_EMBEDDED_PLAYER" -> {
                headers["User-Agent"] = UA_WEB_SAFARI
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
            clientParam == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> {
                headers["User-Agent"] = UA_TV_EMBED
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
            clientParam == "TVHTML5" || clientParam == "TVHTML5_SIMPLY" || clientParam.startsWith("TVHTML5") -> {
                headers["User-Agent"] = UA_TV_SAMSUNG
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/tv"
            }
            clientParam == "WEB" -> {
                headers["User-Agent"] = UA_WEB_CREATOR
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
            else -> {
                headers["User-Agent"] = UA_WEB_CREATOR
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
        }
        return headers
    }


}
