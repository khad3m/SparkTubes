package com.sparktube.app.net

import android.webkit.CookieManager
import com.sparktube.app.util.AppPrefs
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale

/**
 * Manages Google / YouTube session cookies, `SAPISIDHASH` authorization headers,
 * and client-matched `googlevideo.com` playback/download headers.
 */
object YouTubeAuth {

    const val UA_WEB_CHROME =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val UA_ANDROID_VR =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 " +
            "(Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1) gzip"

    const val UA_IOS =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    const val UA_ANDROID =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14; en_US) gzip"

    const val UA_TV_EMBED =
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/15.4 Safari/605.1.15"

    fun parseCookieMap(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (raw.isBlank()) return map
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    fun extractSapisid(cookieMap: Map<String, String> = parseCookieMap(AppPrefs.youtubeCookies)): String? {
        return cookieMap["SAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-1PAPISID"]?.takeIf { it.isNotBlank() }
    }

    fun isLoggedIn(): Boolean {
        val map = parseCookieMap(AppPrefs.youtubeCookies)
        val hasSapisid = !extractSapisid(map).isNullOrBlank()
        val hasSid = !map["SID"].isNullOrBlank() ||
            !map["__Secure-1PSID"].isNullOrBlank() ||
            !map["__Secure-3PSID"].isNullOrBlank() ||
            !map["LOGIN_INFO"].isNullOrBlank()
        return hasSapisid && hasSid
    }

    fun hasAuthCookiesInString(rawCookies: String): Boolean {
        val map = parseCookieMap(rawCookies)
        val hasSapisid = !extractSapisid(map).isNullOrBlank()
        val hasSid = !map["SID"].isNullOrBlank() ||
            !map["__Secure-1PSID"].isNullOrBlank() ||
            !map["__Secure-3PSID"].isNullOrBlank() ||
            !map["LOGIN_INFO"].isNullOrBlank()
        return hasSapisid && hasSid
    }

    fun saveCookies(rawCookies: String, accountLabel: String = "Google Account Connected") {
        val map = LinkedHashMap<String, String>()
        map["CONSENT"] = "YES+cb"
        map["SOCS"] = "CAI"
        map.putAll(parseCookieMap(rawCookies))
        val serialized = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        AppPrefs.youtubeCookies = serialized
        if (accountLabel.isNotBlank()) {
            AppPrefs.youtubeAccountLabel = accountLabel
        }
    }

    fun clearSession() {
        AppPrefs.youtubeCookies = ""
        AppPrefs.youtubeAccountLabel = ""
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    fun accountSummary(): String {
        if (!isLoggedIn()) return "Not signed in"
        return AppPrefs.youtubeAccountLabel.ifBlank { "Google Account Connected ✓" }
    }

    fun buildCookieHeader(existingCookie: String? = null): String {
        val merged = LinkedHashMap<String, String>()
        merged["CONSENT"] = "YES+cb"
        merged["SOCS"] = "CAI"
        if (!existingCookie.isNullOrBlank()) {
            merged.putAll(parseCookieMap(existingCookie))
        }
        val saved = AppPrefs.youtubeCookies
        if (saved.isNotBlank()) {
            merged.putAll(parseCookieMap(saved))
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun buildAuthorizationHeader(origin: String = "https://www.youtube.com"): String? {
        val sapisid = extractSapisid() ?: return null
        val epochSec = System.currentTimeMillis() / 1000L
        val payload = "$epochSec $sapisid $origin"
        val sha1Hex = runCatching {
            val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(Locale.US, it) }
        }.getOrNull() ?: return null
        val token = "${epochSec}_${sha1Hex}"
        return "SAPISIDHASH $token SAPISID1PHASH $token SAPISID3PHASH $token"
    }

    fun applyYouTubeAuthToRequest(url: String, builder: Request.Builder, existingCookies: List<String> = emptyList()) {
        val lower = url.lowercase(Locale.US)
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return

        val origin = if (lower.contains("music.youtube.com")) {
            "https://music.youtube.com"
        } else {
            "https://www.youtube.com"
        }

        val combinedExisting = existingCookies.joinToString("; ")
        val cookieHeader = buildCookieHeader(combinedExisting)
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }

        if (isLoggedIn()) {
            buildAuthorizationHeader(origin)?.let { auth ->
                builder.header("Authorization", auth)
            }
            builder.header("X-Origin", origin)
            builder.header("Origin", origin)
            builder.header("X-Goog-AuthUser", "0")
        }
    }

    /**
     * Returns client-matched HTTP headers (`User-Agent`, `Origin`, `Referer`, `Cookie`)
     * for `googlevideo.com` CDN stream URLs so ExoPlayer and DownloadCenter never get HTTP 403.
     */
    fun streamHeadersForUrl(url: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val lower = url.lowercase(Locale.US)
        val clientParam = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.uppercase(Locale.US).orEmpty()

        when {
            clientParam == "ANDROID_VR" -> {
                headers["User-Agent"] = UA_ANDROID_VR
            }
            clientParam == "IOS" -> {
                headers["User-Agent"] = UA_IOS
            }
            clientParam.startsWith("ANDROID") -> {
                headers["User-Agent"] = UA_ANDROID
            }
            clientParam.startsWith("TVHTML5") -> {
                headers["User-Agent"] = UA_TV_EMBED
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
                if (isLoggedIn()) {
                    headers["Cookie"] = buildCookieHeader()
                }
            }
            else -> {
                headers["User-Agent"] = OkHttpDownloader.USER_AGENT
                if (lower.contains("googlevideo.com") || lower.contains("youtube.com")) {
                    headers["Origin"] = "https://www.youtube.com"
                    headers["Referer"] = "https://www.youtube.com/"
                    if (isLoggedIn()) {
                        headers["Cookie"] = buildCookieHeader()
                    }
                }
            }
        }
        return headers
    }
}
