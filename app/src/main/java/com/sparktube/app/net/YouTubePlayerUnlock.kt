package com.sparktube.app.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Multi-Client InnerTube Unlock Engine:
 *
 * 1. Injects `"contentCheckOk": true` and `"racyCheckOk": true` into outgoing
 *    `/youtubei/v1/player` and `/youtubei/v1/next` JSON bodies.
 * 2. Inspects `/youtubei/v1/player` responses inside [OkHttpDownloader].
 *    Whenever a video returns `LOGIN_REQUIRED`, `AGE_VERIFICATION_REQUIRED`,
 *    `UNPLAYABLE`, or lacks direct playable `url` streams in `streamingData`,
 *    automatically queries fallback InnerTube clients:
 *    - `ANDROID_VR` (`1.60.19` — bypasses bot-check & JS cipher, returns direct URLs)
 *    - `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (`2.0` — bypasses age-restriction & login gates,
 *      with user's Google `SAPISIDHASH` cookies when signed in)
 *    - `IOS` (`19.45.4` — direct progressive/adaptive streams)
 *    - `ANDROID_TESTSUITE` (`1.9`)
 *    and merges the unlocked `streamingData`, `playabilityStatus`, `videoDetails`,
 *    and `microformat` back into the response so `NewPipeExtractor` succeeds seamlessly.
 */
object YouTubePlayerUnlock {

    private const val TAG = "PlayerUnlock"
    private const val TV_VERSION = "7.20250312.16.00"
    private const val WEB_EMBED_VERSION = "1.20250310.01.00"
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val MWEB_PLAYER_ENDPOINT = "https://m.youtube.com/youtubei/v1/player?prettyPrint=false"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val forcedVideoIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    @Volatile private var cachedSts: Int? = null
    @Volatile private var cachedStsAt: Long = 0L

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun signatureTimestampFor(videoId: String): Int? {
        val now = System.currentTimeMillis()
        cachedSts?.let { if (now - cachedStsAt < 6 * 3600_000L) return it }
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        if (sts != null && sts > 0) {
            cachedSts = sts
            cachedStsAt = now
        }
        return sts
    }

    /** Direct InnerTube unlock call for PlaybackCenter bypass when NewPipe throws AgeRestrictedContentException. */
    fun fetchUnlockedPlayerJson(videoId: String): JSONObject? {
        if (videoId.isBlank()) return null
        val loggedIn = YouTubeAuth.isLoggedIn()
        val candidates = if (loggedIn) {
            listOf(
                FallbackSpec("MWEB", useCookies = true),
                FallbackSpec("TVHTML5", useCookies = true),
                FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = true),
                FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = true),
                FallbackSpec("WEB", useCookies = true),
                FallbackSpec("ANDROID", useCookies = true),
                FallbackSpec("ANDROID_VR", useCookies = false),
                FallbackSpec("IOS", useCookies = false)
            )
        } else {
            listOf(
                FallbackSpec("ANDROID_VR", useCookies = false),
                FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = false),
                FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = false),
                FallbackSpec("IOS", useCookies = false)
            )
        }
        var cipherFallback: JSONObject? = null
        for (spec in candidates) {
            val fb = fetchFallbackPlayer(videoId, spec, directClient, null) ?: continue
            deobfuscateStreamingData(fb, videoId)
            if (isPlayableWithDirectUrls(fb)) {
                normalizePlayerJson(fb, null, videoId)
                return fb
            }
            if (isPlayableWithStreamSources(fb) && cipherFallback == null) {
                cipherFallback = fb
            }
        }
        return cipherFallback?.also {
            deobfuscateStreamingData(it, videoId)
            normalizePlayerJson(it, null, videoId)
        }
    }

    fun deobfuscateAndNormalize(root: JSONObject, videoId: String): JSONObject {
        deobfuscateStreamingData(root, videoId)
        normalizePlayerJson(root, null, videoId)
        return root
    }

    private fun deobfuscateStreamingData(root: JSONObject, videoId: String) {
        val sd = root.optJSONObject("streamingData") ?: return
        deobfuscateArray(sd.optJSONArray("formats"), videoId)
        deobfuscateArray(sd.optJSONArray("adaptiveFormats"), videoId)
    }

    private fun deobfuscateArray(arr: JSONArray?, videoId: String) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val fmt = arr.optJSONObject(i) ?: continue
            var directUrl = fmt.optString("url", "")
            if (directUrl.isBlank()) {
                val cipherStr = fmt.optString("signatureCipher", "").ifBlank { fmt.optString("cipher", "") }
                if (cipherStr.isNotBlank()) {
                    val parsed = parseQueryParams(cipherStr)
                    val baseUrl = parsed["url"].orEmpty()
                    val encSig = parsed["s"].orEmpty()
                    val sp = parsed["sp"].orEmpty().ifBlank { "signature" }
                    if (baseUrl.startsWith("http")) {
                        directUrl = if (encSig.isNotBlank()) {
                            val decSig = runCatching {
                                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encSig)
                            }.getOrNull().orEmpty()
                            if (decSig.isNotBlank()) {
                                val sep = if (baseUrl.contains("?")) "&" else "?"
                                "$baseUrl$sep$sp=${Uri.encode(decSig)}"
                            } else ""
                        } else {
                            baseUrl
                        }
                    }
                }
            }
            if (directUrl.startsWith("http")) {
                val cleanUrl = runCatching {
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, directUrl)
                }.getOrDefault(directUrl)
                fmt.put("url", cleanUrl)
            }
        }
    }

    private fun parseQueryParams(qs: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        qs.split("&").forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val k = Uri.decode(part.substring(0, eq))
                val v = Uri.decode(part.substring(eq + 1))
                map[k] = v
            }
        }
        return map
    }

    fun forceUnlockForUrl(url: String) {
        val id = extractVideoIdFromUrl(url)
        if (!id.isNullOrBlank()) {
            forcedVideoIds.add(id)
        }
    }

    private fun extractVideoIdFromUrl(url: String): String? {
        return Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})")
            .find(url)?.groupValues?.getOrNull(1)
    }

    fun patchOutgoingRequestBody(url: String, body: ByteArray?): ByteArray? {
        if (body == null || body.isEmpty()) return body
        if (!url.contains("/youtubei/v1/player") && !url.contains("/youtubei/v1/next")) {
            return body
        }
        return runCatching {
            val json = JSONObject(String(body, Charsets.UTF_8))
            var modified = false
            if (!json.optBoolean("contentCheckOk", false)) {
                json.put("contentCheckOk", true)
                modified = true
            }
            if (!json.optBoolean("racyCheckOk", false)) {
                json.put("racyCheckOk", true)
                modified = true
            }
            if (modified) json.toString().toByteArray(Charsets.UTF_8) else body
        }.getOrDefault(body)
    }

    fun ensurePlayable(
        url: String,
        requestBody: ByteArray?,
        responseBody: String,
        httpClient: OkHttpClient
    ): String {
        if (!url.contains("/youtubei/v1/player")) return responseBody
        return runCatching {
            val reqJson = requestBody?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            val origJson = runCatching { JSONObject(responseBody) }.getOrNull()

            val videoId = reqJson?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: origJson?.optJSONObject("videoDetails")?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: return responseBody

            val forceUnlock = forcedVideoIds.remove(videoId)
            if (!forceUnlock && origJson != null && isPlayableWithDirectUrls(origJson)) {
                normalizePlayerJson(origJson, null, videoId)
                return origJson.toString()
            }

            val loggedIn = YouTubeAuth.isLoggedIn()
            // Age-restricted videos are unlocked with the user's real session:
            // the desktop WEB client + saved Google cookies + SAPISIDHASH header
            // (exactly what the signed-in browser sends). Every anonymous client
            // (VR / IOS / TV / embedded) now gets "Sign in to confirm your age",
            // so when signed in the WEB+cookies request must come FIRST; the TV /
            // embedded clients below stay as backup for bot-checked videos.
            // ANDROID_VR / IOS never accept cookies, so they only help for videos
            // that are merely bot-checked, not age-gated.
            val candidates = if (loggedIn) {
                listOf(
                    FallbackSpec("MWEB", useCookies = true),
                    FallbackSpec("WEB", useCookies = true),
                    FallbackSpec("TVHTML5", useCookies = true),
                    FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = true),
                    FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = true),
                    FallbackSpec("ANDROID", useCookies = true),
                    FallbackSpec("TVHTML5_SIMPLY", useCookies = true),
                    FallbackSpec("ANDROID_VR", useCookies = false),
                    FallbackSpec("IOS", useCookies = false),
                    FallbackSpec("ANDROID_TESTSUITE", useCookies = false)
                )
            } else {
                listOf(
                    FallbackSpec("ANDROID_VR", useCookies = false),
                    FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = false),
                    FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = false),
                    FallbackSpec("IOS", useCookies = false),
                    FallbackSpec("ANDROID_TESTSUITE", useCookies = false)
                )
            }

            var bestFallback: JSONObject? = null
            var cipherFallback: JSONObject? = null
            for (spec in candidates) {
                val fb = fetchFallbackPlayer(videoId, spec, httpClient, reqJson) ?: continue
                deobfuscateStreamingData(fb, videoId)
                val st = fb.optJSONObject("playabilityStatus")
                Log.d(
                    TAG,
                    "$videoId ${spec.kind} cookies=${spec.useCookies} status=${st?.optString("status")} " +
                        "reason=${st?.optString("reason")} direct=${isPlayableWithDirectUrls(fb)} playable=${isPlayableWithStreamSources(fb)}"
                )
                if (isPlayableWithDirectUrls(fb)) {
                    bestFallback = fb
                    break
                }
                if (isPlayableWithStreamSources(fb) && cipherFallback == null) {
                    cipherFallback = fb
                } else if (bestFallback == null && hasAnyStreamingData(fb)) {
                    bestFallback = fb
                }
            }
            if (bestFallback == null) {
                bestFallback = cipherFallback
            }

            if (bestFallback != null) {
                normalizePlayerJson(bestFallback, origJson, videoId)
                bestFallback.toString()
            } else {
                responseBody
            }
        }.getOrDefault(responseBody)
    }

    private data class FallbackSpec(
        val kind: String,
        val useCookies: Boolean
    )

    private fun isPlayableWithDirectUrls(root: JSONObject): Boolean {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (!status.equals("OK", ignoreCase = true)) return false
        val sd = root.optJSONObject("streamingData") ?: return false
        var directVideo = 0
        var directAudio = 0

        val adaptive = sd.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val fmt = adaptive.optJSONObject(i) ?: continue
                if (!fmt.optString("url", "").startsWith("http")) continue
                val mime = fmt.optString("mimeType", "")
                if (mime.startsWith("video/")) directVideo++
                else if (mime.startsWith("audio/")) directAudio++
            }
        }
        val muxed = sd.optJSONArray("formats")
        if (muxed != null) {
            for (i in 0 until muxed.length()) {
                val fmt = muxed.optJSONObject(i) ?: continue
                if (fmt.optString("url", "").startsWith("http")) {
                    directVideo++
                    directAudio++
                }
            }
        }
        return directVideo > 0 && directAudio > 0
    }

    /**
     * A response counts as playable when it has OK status plus at least one
     * video AND one audio source — direct `url` OR `signatureCipher` (the
     * signed-in WEB client returns ciphered URLs, which NewPipeExtractor
     * de-obfuscates with its player-JS engine, so cipher counts as usable).
     */
    private fun isPlayableWithStreamSources(root: JSONObject): Boolean {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (!status.equals("OK", ignoreCase = true)) return false
        val sd = root.optJSONObject("streamingData") ?: return false
        var video = 0
        var audio = 0

        val adaptive = sd.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val fmt = adaptive.optJSONObject(i) ?: continue
                if (!hasStreamSource(fmt)) continue
                val mime = fmt.optString("mimeType", "")
                if (mime.startsWith("video/")) video++
                else if (mime.startsWith("audio/")) audio++
            }
        }
        val muxed = sd.optJSONArray("formats")
        if (muxed != null) {
            for (i in 0 until muxed.length()) {
                val fmt = muxed.optJSONObject(i) ?: continue
                if (hasStreamSource(fmt)) {
                    video++
                    audio++
                }
            }
        }
        return video > 0 && audio > 0
    }

    /** A format is usable if it has a direct URL or a (decryptable) signature cipher. */
    private fun hasStreamSource(fmt: JSONObject): Boolean =
        fmt.optString("url", "").startsWith("http") ||
            fmt.optString("signatureCipher", "").isNotBlank() ||
            fmt.optString("cipher", "").isNotBlank()

    private fun hasAnyStreamingData(root: JSONObject): Boolean {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (!status.equals("OK", ignoreCase = true)) return false
        val sd = root.optJSONObject("streamingData") ?: return false
        val adaptiveLen = sd.optJSONArray("adaptiveFormats")?.length() ?: 0
        val formatsLen = sd.optJSONArray("formats")?.length() ?: 0
        return adaptiveLen > 0 || formatsLen > 0
    }

    private fun fetchFallbackPlayer(
        videoId: String,
        spec: FallbackSpec,
        httpClient: OkHttpClient,
        origRequest: JSONObject?
    ): JSONObject? = runCatching {
        val payload = buildClientPayload(videoId, spec.kind)
        val origClient = origRequest?.optJSONObject("context")?.optJSONObject("client")?.optString("clientName").orEmpty()
        if (origClient.equals(spec.kind, ignoreCase = true)) {
            origRequest?.optJSONObject("playbackContext")?.let { payload.put("playbackContext", it) }
            origRequest?.optJSONObject("serviceIntegrityDimensions")?.let {
                payload.put("serviceIntegrityDimensions", it)
            }
        } else if (spec.kind in setOf("WEB", "MWEB", "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "WEB_EMBEDDED_PLAYER")) {
            signatureTimestampFor(videoId)?.let { sts ->
                payload.put(
                    "playbackContext",
                    JSONObject().put(
                        "contentPlaybackContext",
                        JSONObject()
                            .put("html5Preference", "HTML5_PREF_WANTS")
                            .put("signatureTimestamp", sts)
                    )
                )
            }
        }
        val endpoint = if (spec.kind == "MWEB") MWEB_PLAYER_ENDPOINT else PLAYER_ENDPOINT
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")

        when (spec.kind) {
            "ANDROID_VR" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_ANDROID_VR)
                reqBuilder.header("X-YouTube-Client-Name", "28")
                reqBuilder.header("X-YouTube-Client-Version", "1.60.19")
            }
            "TVHTML5" -> {
                reqBuilder.header("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
                reqBuilder.header("X-YouTube-Client-Name", "7")
                reqBuilder.header("X-YouTube-Client-Version", TV_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/tv")
            }
            "TVHTML5_SIMPLY" -> {
                reqBuilder.header("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
                reqBuilder.header("X-YouTube-Client-Name", "75")
                reqBuilder.header("X-YouTube-Client-Version", "1.0")
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/tv")
            }
            "WEB_EMBEDDED_PLAYER" -> {
                reqBuilder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
                )
                reqBuilder.header("X-YouTube-Client-Name", "56")
                reqBuilder.header("X-YouTube-Client-Version", WEB_EMBED_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/embed/$videoId")
            }
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_TV_EMBED)
                reqBuilder.header("X-YouTube-Client-Name", "85")
                reqBuilder.header("X-YouTube-Client-Version", "2.0")
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/")
            }
            "IOS" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_IOS)
                reqBuilder.header("X-YouTube-Client-Name", "5")
                reqBuilder.header("X-YouTube-Client-Version", "19.45.4")
            }
            "ANDROID" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_ANDROID)
                reqBuilder.header("X-YouTube-Client-Name", "3")
                reqBuilder.header("X-YouTube-Client-Version", "19.45.38")
            }
            "ANDROID_TESTSUITE" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_ANDROID)
                reqBuilder.header("X-YouTube-Client-Name", "30")
                reqBuilder.header("X-YouTube-Client-Version", "1.9")
            }
            "MWEB" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_WEB_CHROME)
                reqBuilder.header("X-YouTube-Client-Name", "2")
                reqBuilder.header("X-YouTube-Client-Version", "2.20250312.01.00")
                reqBuilder.header("Origin", "https://m.youtube.com")
                reqBuilder.header("Referer", "https://m.youtube.com/watch?v=$videoId")
            }
            else -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_WEB_CHROME)
                reqBuilder.header("X-YouTube-Client-Name", "1")
                reqBuilder.header("X-YouTube-Client-Version", "2.20250312.01.00")
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/watch?v=$videoId")
            }
        }

        if (spec.useCookies && YouTubeAuth.isLoggedIn()) {
            YouTubeAuth.applyYouTubeAuthToRequest(endpoint, reqBuilder)
        } else {
            reqBuilder.header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        }

        reqBuilder.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) return@runCatching null
            JSONObject(text)
        }
    }.getOrNull()

    private fun buildClientPayload(videoId: String, kind: String): JSONObject {
        val clientObj = when (kind) {
            "ANDROID_VR" -> JSONObject()
                .put("clientName", "ANDROID_VR")
                .put("clientVersion", "1.60.19")
                .put("androidSdkVersion", 34)
                .put("osName", "Android")
                .put("osVersion", "14")
                .put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "TVHTML5" -> JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_VERSION)
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "TVHTML5_SIMPLY" -> JSONObject()
                .put("clientName", "TVHTML5_SIMPLY")
                .put("clientVersion", "1.0")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "WEB_EMBEDDED_PLAYER" -> JSONObject()
                .put("clientName", "WEB_EMBEDDED_PLAYER")
                .put("clientVersion", WEB_EMBED_VERSION)
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> JSONObject()
                .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                .put("clientVersion", "2.0")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "IOS" -> JSONObject()
                .put("clientName", "IOS")
                .put("clientVersion", "19.45.4")
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "17.5.1.21F90")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "ANDROID" -> JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.45.38")
                .put("androidSdkVersion", 34)
                .put("osName", "Android")
                .put("osVersion", "14")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "ANDROID_TESTSUITE" -> JSONObject()
                .put("clientName", "ANDROID_TESTSUITE")
                .put("clientVersion", "1.9")
                .put("androidSdkVersion", 34)
                .put("osName", "Android")
                .put("osVersion", "14")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "MWEB" -> JSONObject()
                .put("clientName", "MWEB")
                .put("clientVersion", "2.20250312.01.00")
                .put("hl", "en")
                .put("gl", com.sparktube.app.util.AppPrefs.countryOrDefault.ifBlank { "US" })
                .put("utcOffsetMinutes", 0)

            else -> JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20250312.01.00")
                .put("hl", "en")
                .put("gl", com.sparktube.app.util.AppPrefs.countryOrDefault.ifBlank { "US" })
                .put("utcOffsetMinutes", 0)
        }

        val contextObj = JSONObject().put("client", clientObj)
        if (kind == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
            contextObj.put(
                "thirdParty",
                JSONObject().put("embedUrl", "https://www.youtube.com/")
            )
        } else if (kind == "WEB_EMBEDDED_PLAYER") {
            contextObj.put(
                "thirdParty",
                JSONObject().put("embedUrl", "https://www.youtube.com/embed/$videoId")
            )
        }

        return JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", contextObj)
    }

    private fun normalizePlayerJson(target: JSONObject, fallbackSource: JSONObject?, videoId: String) {
        // Ensure playabilityStatus is OK
        target.put("playabilityStatus", JSONObject().put("status", "OK"))

        // Normalize formats and adaptiveFormats so averageBitrate is always present
        val sd = target.optJSONObject("streamingData")
        if (sd != null) {
            normalizeFormatArray(sd.optJSONArray("formats"))
            normalizeFormatArray(sd.optJSONArray("adaptiveFormats"))
        }

        // Preserve captions or storyboards from original response if missing in fallback
        if (fallbackSource != null) {
            if (!target.has("captions") && fallbackSource.has("captions")) {
                target.put("captions", fallbackSource.opt("captions"))
            }
            if (!target.has("storyboards") && fallbackSource.has("storyboards")) {
                target.put("storyboards", fallbackSource.opt("storyboards"))
            }
            if (!target.has("videoDetails") && fallbackSource.has("videoDetails")) {
                target.put("videoDetails", fallbackSource.opt("videoDetails"))
            }
            if (!target.has("microformat") && fallbackSource.has("microformat")) {
                target.put("microformat", fallbackSource.opt("microformat"))
            }
        }

        // Ensure videoDetails has required fields
        val vd = target.optJSONObject("videoDetails") ?: JSONObject().also { target.put("videoDetails", it) }
        if (vd.optString("videoId").isBlank()) vd.put("videoId", videoId)
        if (vd.optString("title").isBlank()) {
            val origTitle = fallbackSource?.optJSONObject("videoDetails")?.optString("title").orEmpty()
            vd.put("title", origTitle.ifBlank { "YouTube Video" })
        }
        if (vd.optString("author").isBlank()) {
            val origAuthor = fallbackSource?.optJSONObject("videoDetails")?.optString("author").orEmpty()
            vd.put("author", origAuthor.ifBlank { "YouTube" })
        }
        if (vd.optString("channelId").isBlank()) {
            val origCid = fallbackSource?.optJSONObject("videoDetails")?.optString("channelId").orEmpty()
            vd.put("channelId", origCid.ifBlank { "UC_x5XG1OV2P6uZZ5FSM9Ttw" })
        }

        // Synthesize microformat.playerMicroformatRenderer if omitted by mobile/VR/TV InnerTube client
        if (!target.has("microformat")) {
            val thumbArr = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
            val mf = JSONObject()
                .put("title", JSONObject().put("simpleText", vd.optString("title", "YouTube Video")))
                .put("description", JSONObject().put("simpleText", vd.optString("shortDescription", "")))
                .put("lengthSeconds", vd.optString("lengthSeconds", "0"))
                .put("ownerChannelName", vd.optString("author", "YouTube"))
                .put("externalChannelId", vd.optString("channelId", "UC_x5XG1OV2P6uZZ5FSM9Ttw"))
                .put("viewCount", vd.optString("viewCount", "0"))
                .put("category", "Entertainment")
                .put("publishDate", "2025-01-01")
                .put("uploadDate", "2025-01-01")
                .put("thumbnail", JSONObject().put("thumbnails", thumbArr))
            target.put("microformat", JSONObject().put("playerMicroformatRenderer", mf))
        }
    }

    private fun normalizeFormatArray(arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val fmt = arr.optJSONObject(i) ?: continue
            val bitrate = fmt.optInt("bitrate", 0)
            if (fmt.optInt("averageBitrate", 0) <= 0 && bitrate > 0) {
                fmt.put("averageBitrate", bitrate)
            }
            if (!fmt.has("approxDurationMs")) {
                fmt.put("approxDurationMs", "0")
            }
        }
    }
}
