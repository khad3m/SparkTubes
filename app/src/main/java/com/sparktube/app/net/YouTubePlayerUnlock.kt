package com.sparktube.app.net

import android.net.Uri
import android.util.Log
import com.sparktube.app.util.AppPrefs
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Multi-Client InnerTube Unlock Engine (v1.7.8):
 *
 * Root causes resolved in v1.7.8:
 * 1. Intercepts BOTH `/youtubei/v1/player` AND `/youtubei/v1/reel/reel_item_watch`!
 *    NewPipeExtractor v0.26.5 calls `https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch`
 *    FIRST inside `fetchAndroidClient()` (when `androidPoTokenResult == null`), returning
 *    `{"playerResponse": {...}}`, and immediately calls `checkPlayabilityStatus()`.
 *    Because v1.7.7 only checked `url.contains("/youtubei/v1/player")`, `reel_item_watch`
 *    bypassed the unlocker and immediately threw `AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")`!
 * 2. Clears sticky static `ParsingException` fields in `YoutubeJavaScriptPlayerManager`
 *    (`sigTimestampExtractionEx`, `sigDeobFuncExtractionEx`, `throttlingDeobfFuncExtractionEx`)
 *    and pre-populates `CACHED_THROTTLING_PARAMETERS` when NewPipe's `n`-parameter regex fails on
 *    2026 `base.js` so `YoutubeStreamExtractor.buildAndAddItagInfoToList` never crashes.
 * 3. Prioritizes cookie-capable clients that do NOT require a GVS PO Token (`TV_SAMSUNG` `5.20260707`,
 *    `WEB_EMBEDDED_PLAYER` `2.20260708.00.00` with `embedUrl = "https://www.reddit.com/"`,
 *    `TVHTML5_SIMPLY_EMBEDDED_PLAYER` `2.0`, `TVHTML5` `7.20260707.07.00`), and HTTP-probes
 *    `adaptiveFormats` with `Range: bytes=0-0` to prune 403-blocked PO-token streams on
 *    `WEB`, `WEB_CREATOR`, and `MWEB` while keeping verified `200`/`206` streams (`itag=18` or adaptive).
 * 4. Includes a built-in Mozilla Rhino signature decipherer fallback if NewPipeExtractor's regexes miss a 2026 player JS update.
 */
object YouTubePlayerUnlock {

    private const val TAG = "PlayerUnlock"
    private const val TV_VERSION = "7.20260707.07.00"
    private const val TV_SAMSUNG_VERSION = "5.20260707"
    private const val WEB_VERSION = "2.20260708.00.00"
    private const val MWEB_VERSION = "2.20260708.05.00"
    private const val WEB_EMBED_VERSION = "2.20260708.00.00"
    private const val WEB_CREATOR_VERSION = "1.20260708.06.00"

    private val WEB_FAMILY = setOf(
        "TV_SAMSUNG", "TVHTML5", "TV_DOWNGRADED", "TVHTML5_SIMPLY",
        "WEB_EMBEDDED_PLAYER", "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        "WEB_CREATOR", "MWEB", "WEB"
    )

    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val MWEB_PLAYER_ENDPOINT = "https://m.youtube.com/youtubei/v1/player?prettyPrint=false"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val forcedVideoIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    private data class CachedUnlock(val jsonStr: String, val savedAtMs: Long)
    private val unlockedCache = ConcurrentHashMap<String, CachedUnlock>()
    private const val UNLOCK_CACHE_TTL_MS = 10 * 60_000L

    @Volatile private var cachedSts: Int? = null
    @Volatile private var cachedStsAt: Long = 0L
    @Volatile private var cachedPlayerJsHash: String? = null
    @Volatile private var cachedPlayerJsCode: String? = null

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun clearCache() {
        unlockedCache.clear()
        forcedVideoIds.clear()
        clearNewPipeJavaScriptExceptions()
    }

    /**
     * Clears sticky static exception fields in NewPipeExtractor's `YoutubeJavaScriptPlayerManager`
     * (`sigTimestampExtractionEx`, `sigDeobFuncExtractionEx`, `throttlingDeobfFuncExtractionEx`)
     * so a transient regex failure never permanently disables deciphering for the app process.
     */
    fun clearNewPipeJavaScriptExceptions() {
        runCatching {
            val clazz = YoutubeJavaScriptPlayerManager::class.java
            for (fieldName in listOf(
                "sigTimestampExtractionEx",
                "sigDeobFuncExtractionEx",
                "throttlingDeobfFuncExtractionEx"
            )) {
                runCatching {
                    val f: Field = clazz.getDeclaredField(fieldName)
                    f.isAccessible = true
                    f.set(null, null)
                }
            }
        }
    }

    /**
     * Pre-populates NewPipeExtractor's `YoutubeJavaScriptPlayerManager.CACHED_THROTTLING_PARAMETERS`
     * (`private static final Map<String, String>`) with `n -> n` for every stream URL in `root`
     * IF NewPipe's own throttling deobfuscator throws a `ParsingException` on 2026 `base.js`.
     *
     * Why this is essential:
     * `YoutubeStreamExtractor.buildAndAddItagInfoToList` calls
     * `YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)`
     * with ZERO exception handlers! If `YoutubeThrottlingParameterUtils` fails on a 2026 `base.js`
     * revision, `buildAndAddItagInfoToList` throws `ParsingException` and aborts `StreamInfo.getInfo()`.
     * Because `YoutubeJavaScriptPlayerManager` checks `CACHED_THROTTLING_PARAMETERS` first,
     * pre-populating it prevents `YoutubeStreamExtractor` from ever crashing!
     */
    @Suppress("UNCHECKED_CAST")
    fun ensureNewPipeThrottlingSafe(root: JSONObject, videoId: String) {
        clearNewPipeJavaScriptExceptions()
        val sd = root.optJSONObject("streamingData") ?: return
        val urls = ArrayList<String>()
        for (arrName in listOf("formats", "adaptiveFormats")) {
            val arr = sd.optJSONArray(arrName) ?: continue
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i)?.optString("url").orEmpty()
                if (u.startsWith("http")) urls.add(u)
            }
        }
        if (urls.isEmpty()) return

        val testUrl = urls.first()
        val newPipeOk = try {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, testUrl)
            true
        } catch (_: Throwable) {
            clearNewPipeJavaScriptExceptions()
            false
        }
        if (newPipeOk) return

        runCatching {
            val clazz = YoutubeJavaScriptPlayerManager::class.java
            val cacheField = clazz.getDeclaredField("CACHED_THROTTLING_PARAMETERS")
            cacheField.isAccessible = true
            val cacheMap = cacheField.get(null) as? MutableMap<String, String> ?: return@runCatching
            for (u in urls) {
                val nVal = runCatching { Uri.parse(u).getQueryParameter("n") }.getOrNull()
                if (!nVal.isNullOrBlank() && !cacheMap.containsKey(nVal)) {
                    cacheMap[nVal] = nVal
                }
            }
        }
        clearNewPipeJavaScriptExceptions()
    }

    private fun signatureTimestampFor(videoId: String): Int? {
        val now = System.currentTimeMillis()
        cachedSts?.let { if (now - cachedStsAt < 6 * 3600_000L) return it }
        AppPrefs.youtubeSts.takeIf { it > 18000 }?.let {
            cachedSts = it
            cachedStsAt = now
        }
        clearNewPipeJavaScriptExceptions()
        val sts = runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)?.trim()?.toIntOrNull()
        }.getOrNull()
        if (sts != null && sts > 18000) {
            cachedSts = sts
            cachedStsAt = now
            AppPrefs.youtubeSts = sts
            return sts
        }
        fetchPlayerJsCode(videoId)
        return cachedSts
    }

    private fun fetchPlayerJsCode(videoId: String): String? {
        val now = System.currentTimeMillis()
        cachedPlayerJsCode?.let { if (now - cachedStsAt < 3600_000L) return it }
        return runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", YouTubeAuth.UA_WEB_CREATOR)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", YouTubeAuth.buildCookieHeader())
                .build()
            val html = directClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string().orEmpty()
            }
            Regex("\"STS\"\\s*:\\s*(\\d{5,})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { sts ->
                cachedSts = sts
                cachedStsAt = now
                AppPrefs.youtubeSts = sts
            }
            val hash = Regex("/s/player/([A-Za-z0-9_-]{8,})/").find(html)?.groupValues?.getOrNull(1)
                ?: cachedPlayerJsHash ?: return@runCatching null
            cachedPlayerJsHash = hash

            val jsUrl = "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_US/base.js"
            val jsReq = Request.Builder()
                .url(jsUrl)
                .header("User-Agent", YouTubeAuth.UA_WEB_CREATOR)
                .build()
            val jsCode = directClient.newCall(jsReq).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string().orEmpty()
            }
            if (jsCode.isNotBlank()) {
                cachedPlayerJsCode = jsCode
                cachedStsAt = now
                Regex("signatureTimestamp\\s*:\\s*(\\d{5,})").find(jsCode)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { sts ->
                    cachedSts = sts
                    AppPrefs.youtubeSts = sts
                }
            }
            jsCode
        }.getOrNull()
    }

    private data class FallbackSpec(
        val kind: String,
        val useCookies: Boolean
    )

    private fun candidateList(loggedIn: Boolean): List<FallbackSpec> = if (loggedIn) {
        listOf(
            // 1-5: Signed-in clients WITHOUT a GVS PO Token policy on adaptiveFormats (yt-dlp 2026 parity)
            FallbackSpec("TV_SAMSUNG", useCookies = true),
            FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = true),
            FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = true),
            FallbackSpec("TVHTML5", useCookies = true),
            FallbackSpec("TV_DOWNGRADED", useCookies = true),
            // 6-8: Signed-in desktop/mobile web clients (progressive itag=18 works without PO token;
            // adaptiveFormats are HTTP-probed and pruned if 403-gated)
            FallbackSpec("WEB_CREATOR", useCookies = true),
            FallbackSpec("MWEB", useCookies = true),
            FallbackSpec("WEB", useCookies = true),
            // 9-11: Non-cookie clients for non-age-gated bot-checked videos
            FallbackSpec("ANDROID_VR", useCookies = false),
            FallbackSpec("VISIONOS", useCookies = false),
            FallbackSpec("IOS", useCookies = false)
        )
    } else {
        listOf(
            FallbackSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", useCookies = false),
            FallbackSpec("WEB_EMBEDDED_PLAYER", useCookies = false),
            FallbackSpec("TV_SAMSUNG", useCookies = false),
            FallbackSpec("ANDROID_VR", useCookies = false),
            FallbackSpec("VISIONOS", useCookies = false),
            FallbackSpec("IOS", useCookies = false),
            FallbackSpec("ANDROID_TESTSUITE", useCookies = false)
        )
    }

    /** Direct InnerTube unlock call for PlaybackCenter / YtRepository bypass. */
    fun fetchUnlockedPlayerJson(videoId: String): JSONObject? {
        if (videoId.isBlank()) return null

        unlockedCache[videoId]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAtMs < UNLOCK_CACHE_TTL_MS) {
                runCatching { return JSONObject(cached.jsonStr) }
            }
        }

        val loggedIn = YouTubeAuth.isLoggedIn()
        val candidates = candidateList(loggedIn)
        var muxedOnlyFallback: JSONObject? = null

        for (spec in candidates) {
            val fb = fetchFallbackPlayer(videoId, spec, directClient, null) ?: continue
            deobfuscateAndPruneStreamingData(fb, videoId)
            if (isPlayableWithDirectUrls(fb)) {
                normalizePlayerJson(fb, null, videoId)
                ensureNewPipeThrottlingSafe(fb, videoId)
                if (hasDirectAdaptiveVideoAndAudio(fb)) {
                    unlockedCache[videoId] = CachedUnlock(fb.toString(), System.currentTimeMillis())
                    return fb
                } else if (muxedOnlyFallback == null) {
                    muxedOnlyFallback = fb
                }
            }
        }

        // Next tier: Hidden WebView V8 engine (captures deciphered <video src> + in-page TVHTML5 fetch)
        val webViewObj = YouTubeWebViewUnlock.extractPlayerJsonBlocking(videoId)
        if (webViewObj != null) {
            deobfuscateAndPruneStreamingData(webViewObj, videoId)
            if (isPlayableWithDirectUrls(webViewObj)) {
                normalizePlayerJson(webViewObj, null, videoId)
                ensureNewPipeThrottlingSafe(webViewObj, videoId)
                unlockedCache[videoId] = CachedUnlock(webViewObj.toString(), System.currentTimeMillis())
                return webViewObj
            }
        }

        if (muxedOnlyFallback != null) {
            unlockedCache[videoId] = CachedUnlock(muxedOnlyFallback.toString(), System.currentTimeMillis())
            return muxedOnlyFallback
        }

        // Final tier: Public Piped API fallback
        val pipedObj = fetchPublicPipedFallback(videoId)
        if (pipedObj != null) {
            normalizePlayerJson(pipedObj, null, videoId)
            ensureNewPipeThrottlingSafe(pipedObj, videoId)
            unlockedCache[videoId] = CachedUnlock(pipedObj.toString(), System.currentTimeMillis())
            return pipedObj
        }

        return null
    }

    fun deobfuscateAndNormalize(root: JSONObject, videoId: String): JSONObject {
        deobfuscateAndPruneStreamingData(root, videoId)
        normalizePlayerJson(root, null, videoId)
        ensureNewPipeThrottlingSafe(root, videoId)
        return root
    }

    private fun deobfuscateAndPruneStreamingData(root: JSONObject, videoId: String) {
        val sd = root.optJSONObject("streamingData") ?: return
        deobfuscateArray(sd.optJSONArray("formats"), videoId)
        deobfuscateArray(sd.optJSONArray("adaptiveFormats"), videoId)

        // Probe first adaptive format URL with `Range: bytes=0-0` to detect GVS PO Token 403 blocks
        // on WEB / WEB_CREATOR / MWEB so ExoPlayer never enters an infinite 0:00 HTTP 403 reload loop!
        val adaptive = sd.optJSONArray("adaptiveFormats")
        if (adaptive != null && adaptive.length() > 0) {
            var firstAdaptiveUrl = ""
            for (i in 0 until adaptive.length()) {
                val u = adaptive.optJSONObject(i)?.optString("url").orEmpty()
                if (u.startsWith("http")) {
                    firstAdaptiveUrl = u
                    break
                }
            }
            if (firstAdaptiveUrl.isNotEmpty() && !probeStreamUrlOk(firstAdaptiveUrl)) {
                Log.w(TAG, "adaptiveFormats returned HTTP 403 for $videoId (PO-token gated); pruning broken adaptiveFormats")
                sd.remove("adaptiveFormats")
                sd.remove("dashManifestUrl")
                sd.remove("serverAbrStreamingUrl")
            }
        }

        // If no adaptiveFormats remain, verify that progressive `formats` (itag=18) passes HTTP probe
        val remAdaptive = sd.optJSONArray("adaptiveFormats")
        val hasRemAdaptive = remAdaptive != null && remAdaptive.length() > 0
        val formats = sd.optJSONArray("formats")
        if (!hasRemAdaptive && formats != null && formats.length() > 0) {
            var firstMuxedUrl = ""
            for (i in 0 until formats.length()) {
                val u = formats.optJSONObject(i)?.optString("url").orEmpty()
                if (u.startsWith("http")) {
                    firstMuxedUrl = u
                    break
                }
            }
            if (firstMuxedUrl.isNotEmpty() && !probeStreamUrlOk(firstMuxedUrl)) {
                Log.w(TAG, "Progressive formats also failed HTTP probe for $videoId")
                sd.remove("formats")
            }
        }
    }

    fun probeStreamUrlOk(streamUrl: String): Boolean {
        return try {
            val builder = Request.Builder()
                .url(streamUrl)
                .get()
                .header("Range", "bytes=0-0")
            YouTubeAuth.streamHeadersForUrl(streamUrl).forEach { (k, v) ->
                builder.header(k, v)
            }
            val cookies = YouTubeAuth.buildCookieHeader()
            if (cookies.isNotBlank()) {
                builder.header("Cookie", cookies)
            }
            probeClient.newCall(builder.build()).execute().use { resp ->
                resp.code in 200..299
            }
        } catch (_: Throwable) {
            // Do not discard on transient network timeout
            true
        }
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
                    val sp = parsed["sp"].orEmpty().ifBlank { "sig" }
                    if (baseUrl.startsWith("http")) {
                        directUrl = if (encSig.isNotBlank()) {
                            clearNewPipeJavaScriptExceptions()
                            val decSig = runCatching {
                                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encSig)
                            }.getOrNull()?.takeIf { it.isNotBlank() && it != encSig }
                                ?: decipherSignatureWithRhino(videoId, encSig)
                                ?: ""
                            if (decSig.isNotBlank()) {
                                val sep = if (baseUrl.contains("?")) "&" else "?"
                                "$baseUrl$sep$sp=${Uri.encode(decSig)}"
                            } else ""
                        } else {
                            baseUrl
                        }
                        if (directUrl.startsWith("http")) {
                            fmt.remove("signatureCipher")
                            fmt.remove("cipher")
                        }
                    }
                }
            }
            if (directUrl.startsWith("http")) {
                clearNewPipeJavaScriptExceptions()
                val cleanUrl = runCatching {
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, directUrl)
                }.getOrElse {
                    clearNewPipeJavaScriptExceptions()
                    directUrl
                }
                fmt.put("url", cleanUrl)
            }
        }
    }

    /**
     * Fallback signature decipherer using Mozilla Rhino directly on `player_ias.vflset/en_US/base.js`
     * when NewPipeExtractor's regexes do not match a 2026 player revision.
     */
    private fun decipherSignatureWithRhino(videoId: String, signature: String): String? {
        return try {
            val jsCode = fetchPlayerJsCode(videoId) ?: return null
            val funcPatterns = listOf(
                Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\("""),
                Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\("""),
                Regex("""\bm=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(h\.s\)\)"""),
                Regex("""([a-zA-Z0-9$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)\s*;\s*([a-zA-Z0-9$]{2,})\.""")
            )
            var funcMatch: MatchResult? = null
            for (pat in funcPatterns) {
                funcMatch = pat.find(jsCode)
                if (funcMatch != null) break
            }
            val funcName = funcMatch?.groupValues?.getOrNull(1) ?: return null
            val escapedFunc = Regex.escape(funcName)
            val declRegex = Regex(
                """(?:function\s+$escapedFunc|var\s+$escapedFunc\s*=\s*function|$escapedFunc\s*=\s*function)\s*\(([a-zA-Z0-9_]+)\)\s*\{([^}]+)\}"""
            )
            val declMatch = declRegex.find(jsCode) ?: return null
            val argName = declMatch.groupValues[1]
            val funcBody = declMatch.groupValues[2]

            val helperMatch = Regex("""([a-zA-Z0-9$]{2,})\.[a-zA-Z0-9$]{2,}\(""").find(funcBody)
            val helperName = helperMatch?.groupValues?.getOrNull(1) ?: return null
            val escapedHelper = Regex.escape(helperName)
            val helperRegex = Regex("""var\s+$escapedHelper\s*=\s*\{([\s\S]*?)\};""")
            val helperDecl = helperRegex.find(jsCode)?.value ?: return null

            val script = "$helperDecl\nfunction _decipher($argName){$funcBody}"
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                val scope: ScriptableObject = cx.initSafeStandardObjects()
                cx.evaluateString(scope, script, "decipher.js", 1, null)
                val fn = scope.get("_decipher", scope) as? Function ?: return null
                val res = fn.call(cx, scope, scope, arrayOf<Any>(signature))
                Context.toString(res)?.takeIf { it.isNotBlank() }
            } finally {
                Context.exit()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Rhino fallback signature decipher failed: ${t.message}")
            null
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
            unlockedCache.remove(id)
        }
    }

    private fun extractVideoIdFromUrl(url: String): String? {
        runCatching {
            val uri = Uri.parse(url)
            uri.getQueryParameter("id")?.takeIf { it.length == 11 }?.let { return it }
            uri.getQueryParameter("v")?.takeIf { it.length == 11 }?.let { return it }
        }
        return Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})")
            .find(url)?.groupValues?.getOrNull(1)
    }

    fun patchOutgoingRequestBody(url: String, body: ByteArray?): ByteArray? {
        if (body == null || body.isEmpty()) return body
        if (!url.contains("/youtubei/v1/player") &&
            !url.contains("/youtubei/v1/reel/reel_item_watch") &&
            !url.contains("/youtubei/v1/next")
        ) {
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
            val playerRequest = json.optJSONObject("playerRequest")
            if (playerRequest != null) {
                playerRequest.put("contentCheckOk", true)
                playerRequest.put("racyCheckOk", true)
                modified = true
            }
            if (modified) json.toString().toByteArray(Charsets.UTF_8) else body
        }.getOrDefault(body)
    }

    /**
     * Intercepts BOTH `/youtubei/v1/player` AND `/youtubei/v1/reel/reel_item_watch` responses
     * inside [OkHttpDownloader].
     *
     * When `NewPipeExtractor` calls `/youtubei/v1/reel/reel_item_watch?...&id=VIDEO_ID&$fields=playerResponse`
     * in `fetchAndroidClient()`, the response is wrapped as `{"playerResponse": {...}}`.
     * We unwrap `playerResponse`, unlock it via our multi-client/WebView ladder if it is
     * `LOGIN_REQUIRED` / `UNPLAYABLE` / missing direct streams, ensure `CACHED_THROTTLING_PARAMETERS`
     * is safe, and re-wrap it in `{"playerResponse": ...}` so `YoutubeStreamExtractor` succeeds on
     * the very first try without throwing `AgeRestrictedContentException`!
     */
    fun ensurePlayable(
        url: String,
        requestBody: ByteArray?,
        responseBody: String,
        @Suppress("UNUSED_PARAMETER") httpClient: OkHttpClient
    ): String {
        val isPlayerEndpoint = url.contains("/youtubei/v1/player")
        val isReelWatchEndpoint = url.contains("/youtubei/v1/reel/reel_item_watch")
        if (!isPlayerEndpoint && !isReelWatchEndpoint) return responseBody
        if (responseBody.isBlank()) return responseBody

        return runCatching {
            val reqJson = requestBody?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            val outerJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val isWrappedReel = isReelWatchEndpoint || (outerJson != null && outerJson.has("playerResponse"))
            val origPlayerJson = if (isWrappedReel) {
                outerJson?.optJSONObject("playerResponse")
            } else {
                outerJson
            }

            val videoId = reqJson?.optString("videoId")?.takeIf { it.length == 11 }
                ?: reqJson?.optJSONObject("playerRequest")?.optString("videoId")?.takeIf { it.length == 11 }
                ?: origPlayerJson?.optJSONObject("videoDetails")?.optString("videoId")?.takeIf { it.length == 11 }
                ?: extractVideoIdFromUrl(url)
                ?: return responseBody

            val forceUnlock = forcedVideoIds.remove(videoId)
            if (!forceUnlock && origPlayerJson != null && isPlayableWithDirectUrls(origPlayerJson)) {
                normalizePlayerJson(origPlayerJson, null, videoId)
                ensureNewPipeThrottlingSafe(origPlayerJson, videoId)
                return if (isWrappedReel && outerJson != null) {
                    outerJson.put("playerResponse", origPlayerJson)
                    outerJson.toString()
                } else {
                    origPlayerJson.toString()
                }
            }

            // Also check if this is NewPipe's metadata-only `$fields=microformat,playabilityStatus,storyboards,videoDetails`
            // request where status is LOGIN_REQUIRED or UNPLAYABLE.
            val unlocked = fetchUnlockedPlayerJson(videoId)
            if (unlocked != null) {
                val merged = JSONObject(unlocked.toString())
                normalizePlayerJson(merged, origPlayerJson, videoId)
                ensureNewPipeThrottlingSafe(merged, videoId)
                if (isWrappedReel) {
                    val wrapper = outerJson ?: JSONObject()
                    wrapper.put("playerResponse", merged)
                    wrapper.toString()
                } else {
                    merged.toString()
                }
            } else {
                responseBody
            }
        }.getOrDefault(responseBody)
    }

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

    private fun hasDirectAdaptiveVideoAndAudio(root: JSONObject): Boolean {
        val sd = root.optJSONObject("streamingData") ?: return false
        val adaptive = sd.optJSONArray("adaptiveFormats") ?: return false
        var hasVideo = false
        var hasAudio = false
        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            if (!fmt.optString("url", "").startsWith("http")) continue
            val mime = fmt.optString("mimeType", "")
            if (mime.startsWith("video/")) hasVideo = true
            else if (mime.startsWith("audio/")) hasAudio = true
        }
        return hasVideo && hasAudio
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
        } else if (spec.kind in WEB_FAMILY) {
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
            "TV_SAMSUNG" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_TV_SAMSUNG)
                reqBuilder.header("X-YouTube-Client-Name", "7")
                reqBuilder.header("X-YouTube-Client-Version", TV_SAMSUNG_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/tv")
            }
            "TVHTML5" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_TV_COBALT)
                reqBuilder.header("X-YouTube-Client-Name", "7")
                reqBuilder.header("X-YouTube-Client-Version", TV_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/tv")
            }
            "TV_DOWNGRADED" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_TV_COBALT)
                reqBuilder.header("X-YouTube-Client-Name", "7")
                reqBuilder.header("X-YouTube-Client-Version", TV_SAMSUNG_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/tv")
            }
            "WEB_EMBEDDED_PLAYER" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_WEB_SAFARI)
                reqBuilder.header("X-YouTube-Client-Name", "56")
                reqBuilder.header("X-YouTube-Client-Version", WEB_EMBED_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.reddit.com/")
            }
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_TV_EMBED)
                reqBuilder.header("X-YouTube-Client-Name", "85")
                reqBuilder.header("X-YouTube-Client-Version", "2.0")
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.reddit.com/")
            }
            "WEB_CREATOR" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_WEB_CREATOR)
                reqBuilder.header("X-YouTube-Client-Name", "62")
                reqBuilder.header("X-YouTube-Client-Version", WEB_CREATOR_VERSION)
                reqBuilder.header("Origin", "https://studio.youtube.com")
                reqBuilder.header("Referer", "https://studio.youtube.com/")
            }
            "MWEB" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_MWEB_IPAD)
                reqBuilder.header("X-YouTube-Client-Name", "2")
                reqBuilder.header("X-YouTube-Client-Version", MWEB_VERSION)
                reqBuilder.header("Origin", "https://m.youtube.com")
                reqBuilder.header("Referer", "https://m.youtube.com/watch?v=$videoId")
            }
            "ANDROID_VR" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_ANDROID_VR)
                reqBuilder.header("X-YouTube-Client-Name", "28")
                reqBuilder.header("X-YouTube-Client-Version", "1.60.19")
            }
            "VISIONOS" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_VISIONOS)
                reqBuilder.header("X-YouTube-Client-Name", "101")
                reqBuilder.header("X-YouTube-Client-Version", "1.02")
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/")
            }
            "IOS" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_IOS)
                reqBuilder.header("X-YouTube-Client-Name", "5")
                reqBuilder.header("X-YouTube-Client-Version", "21.26.4")
            }
            "ANDROID_TESTSUITE" -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_ANDROID)
                reqBuilder.header("X-YouTube-Client-Name", "30")
                reqBuilder.header("X-YouTube-Client-Version", "1.9")
            }
            else -> {
                reqBuilder.header("User-Agent", YouTubeAuth.UA_WEB_CREATOR)
                reqBuilder.header("X-YouTube-Client-Name", "1")
                reqBuilder.header("X-YouTube-Client-Version", WEB_VERSION)
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/watch?v=$videoId")
            }
        }

        if (spec.useCookies && YouTubeAuth.isLoggedIn()) {
            YouTubeAuth.applyYouTubeAuthToRequest(endpoint, reqBuilder)
        } else {
            reqBuilder.header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            YouTubeAuth.visitorData(allowNetworkFetch = false)?.let {
                reqBuilder.header("X-Goog-Visitor-Id", it)
            }
        }

        reqBuilder.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) return@runCatching null
            YouTubeAuth.sniffInnerTubeResponse(text)
            JSONObject(text)
        }
    }.getOrNull()

    private fun buildClientPayload(videoId: String, kind: String): JSONObject {
        val country = AppPrefs.countryOrDefault.ifBlank { "US" }
        val clientObj = when (kind) {
            "TV_SAMSUNG" -> JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_SAMSUNG_VERSION)
                .put("deviceMake", "Samsung")
                .put("deviceModel", "UKS9800")
                .put("osName", "Tizen")
                .put("osVersion", "2.4.0")
                .put("platform", "TV")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "TVHTML5" -> JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_VERSION)
                .put("platform", "TV")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "TV_DOWNGRADED" -> JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_SAMSUNG_VERSION)
                .put("platform", "TV")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "WEB_EMBEDDED_PLAYER" -> JSONObject()
                .put("clientName", "WEB_EMBEDDED_PLAYER")
                .put("clientVersion", WEB_EMBED_VERSION)
                .put("platform", "DESKTOP")
                .put("clientScreen", "EMBED")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> JSONObject()
                .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                .put("clientVersion", "2.0")
                .put("platform", "TV")
                .put("clientScreen", "EMBED")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "WEB_CREATOR" -> JSONObject()
                .put("clientName", "WEB_CREATOR")
                .put("clientVersion", WEB_CREATOR_VERSION)
                .put("platform", "DESKTOP")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "MWEB" -> JSONObject()
                .put("clientName", "MWEB")
                .put("clientVersion", MWEB_VERSION)
                .put("platform", "TABLET")
                .put("clientFormFactor", "SMALL_FORM_FACTOR")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)

            "ANDROID_VR" -> JSONObject()
                .put("clientName", "ANDROID_VR")
                .put("clientVersion", "1.60.19")
                .put("androidSdkVersion", 32)
                .put("osName", "Android")
                .put("osVersion", "12L")
                .put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "VISIONOS" -> JSONObject()
                .put("clientName", "VISIONOS")
                .put("clientVersion", "1.02")
                .put("osName", "visionOS")
                .put("osVersion", "2.0.22N320")
                .put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice14,1")
                .put("platform", "TABLET")
                .put("hl", "en")
                .put("gl", "US")
                .put("utcOffsetMinutes", 0)

            "IOS" -> JSONObject()
                .put("clientName", "IOS")
                .put("clientVersion", "21.26.4")
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iOS")
                .put("osVersion", "18.3.2.22D82")
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

            else -> JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_VERSION)
                .put("platform", "DESKTOP")
                .put("hl", "en")
                .put("gl", country)
                .put("utcOffsetMinutes", 0)
        }

        if (kind in WEB_FAMILY) {
            YouTubeAuth.visitorData(allowNetworkFetch = true)?.takeIf { it.isNotBlank() }?.let {
                clientObj.put("visitorData", it)
            }
        }

        val contextObj = JSONObject()
            .put("client", clientObj)
            .put("user", JSONObject().put("lockedSafetyMode", false))

        if (kind == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || kind == "WEB_EMBEDDED_PLAYER") {
            contextObj.put(
                "thirdParty",
                JSONObject().put("embedUrl", "https://www.reddit.com/")
            )
        }

        return JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", contextObj)
    }

    private fun fetchPublicPipedFallback(videoId: String): JSONObject? {
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            "https://api.piped.private.coffee"
        )
        for (base in pipedInstances) {
            try {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", YouTubeAuth.UA_WEB_CREATOR)
                    .header("Accept", "application/json")
                    .build()
                directClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val piped = JSONObject(body)
                    val title = piped.optString("title").takeIf { it.isNotBlank() } ?: return@use
                    val uploader = piped.optString("uploader", "YouTube")
                    val duration = piped.optLong("duration", 0L)

                    val adaptive = JSONArray()
                    val formats = JSONArray()

                    val audioStreams = piped.optJSONArray("audioStreams")
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val a = audioStreams.optJSONObject(i) ?: continue
                            val u = a.optString("url").takeIf { it.startsWith("http") } ?: continue
                            val br = a.optInt("bitrate", 128000).coerceAtLeast(64000)
                            adaptive.put(
                                JSONObject()
                                    .put("itag", a.optInt("itag", 140))
                                    .put("url", u)
                                    .put("mimeType", a.optString("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""))
                                    .put("bitrate", br)
                                    .put("averageBitrate", br)
                                    .put("approxDurationMs", (duration * 1000L).toString())
                            )
                        }
                    }

                    val videoStreams = piped.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        for (i in 0 until videoStreams.length()) {
                            val v = videoStreams.optJSONObject(i) ?: continue
                            val u = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                            val videoOnly = v.optBoolean("videoOnly", true)
                            val br = v.optInt("bitrate", 1500000).coerceAtLeast(300000)
                            val obj = JSONObject()
                                .put("itag", v.optInt("itag", if (videoOnly) 137 else 18))
                                .put("url", u)
                                .put("mimeType", v.optString("mimeType", "video/mp4; codecs=\"avc1.640028\""))
                                .put("width", v.optInt("width", 1280))
                                .put("height", v.optInt("height", 720))
                                .put("fps", v.optInt("fps", 30))
                                .put("qualityLabel", v.optString("quality", "720p"))
                                .put("bitrate", br)
                                .put("averageBitrate", br)
                                .put("approxDurationMs", (duration * 1000L).toString())
                            if (videoOnly) adaptive.put(obj) else formats.put(obj)
                        }
                    }

                    if (adaptive.length() == 0 && formats.length() == 0) return@use

                    val out = JSONObject()
                        .put("playabilityStatus", JSONObject().put("status", "OK"))
                        .put(
                            "videoDetails",
                            JSONObject()
                                .put("videoId", videoId)
                                .put("title", title)
                                .put("author", uploader)
                                .put("lengthSeconds", duration.toString())
                        )
                        .put(
                            "streamingData",
                            JSONObject()
                                .put("expiresInSeconds", "21540")
                                .put("formats", formats)
                                .put("adaptiveFormats", adaptive)
                        )
                    return out
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun normalizePlayerJson(target: JSONObject, fallbackSource: JSONObject?, videoId: String) {
        target.put("playabilityStatus", JSONObject().put("status", "OK"))

        val vd = target.optJSONObject("videoDetails") ?: JSONObject().also { target.put("videoDetails", it) }
        val durationSec = vd.optString("lengthSeconds").toLongOrNull()
            ?: fallbackSource?.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull()
            ?: fallbackSource?.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
                ?.optString("lengthSeconds")?.toLongOrNull()
            ?: 0L

        if (durationSec > 0L && vd.optString("lengthSeconds") in listOf("", "0")) {
            vd.put("lengthSeconds", durationSec.toString())
        }

        val sd = target.optJSONObject("streamingData")
        if (sd != null) {
            normalizeFormatArray(sd.optJSONArray("formats"), durationSec)
            normalizeFormatArray(sd.optJSONArray("adaptiveFormats"), durationSec)
        }

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

        if (!target.has("microformat")) {
            val thumbArr = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
            val mf = JSONObject()
                .put("title", JSONObject().put("simpleText", vd.optString("title", "YouTube Video")))
                .put("description", JSONObject().put("simpleText", vd.optString("shortDescription", "")))
                .put("lengthSeconds", vd.optString("lengthSeconds", durationSec.toString()))
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

    private fun normalizeFormatArray(arr: JSONArray?, durationSec: Long) {
        if (arr == null) return
        val defaultDurationMs = if (durationSec > 0L) (durationSec * 1000L).toString() else "0"
        for (i in 0 until arr.length()) {
            val fmt = arr.optJSONObject(i) ?: continue
            val bitrate = fmt.optInt("bitrate", 0)
            if (fmt.optInt("averageBitrate", 0) <= 0 && bitrate > 0) {
                fmt.put("averageBitrate", bitrate)
            }
            if (!fmt.has("approxDurationMs") || fmt.optString("approxDurationMs") in listOf("", "0")) {
                val u = fmt.optString("url", "")
                val durFromUrl = runCatching {
                    Uri.parse(u).getQueryParameter("dur")?.toDoubleOrNull()?.let { (it * 1000.0).toLong().toString() }
                }.getOrNull()
                fmt.put("approxDurationMs", durFromUrl ?: defaultDurationMs)
            }
        }
    }
}
