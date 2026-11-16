package com.sparktube.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sparktube.app.playback.PlaybackCenter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Hidden WebView V8 stream & token extractor for age-restricted (18+) videos (v1.7.8).
 *
 * Root causes fixed from v1.7.7:
 * 1. Uses `YouTubeAuth.syncCookiesToWebView(created)` to inject each saved Google cookie
 *    ONE BY ONE into `CookieManager` (v1.7.7 passed the entire `; `-separated string to a
 *    single `setCookie()` call, which dropped `SID`, `SAPISID`, `__Secure-1PSID`, etc.).
 * 2. Disables `window.MediaSource` / `WebKitMediaSource` / `ManagedMediaSource` at `onPageStarted`
 *    so YouTube's HTML5 player in V8 automatically deciphers `signatureCipher` + `n` and assigns
 *    a direct progressive `googlevideo.com/videoplayback` URL (`itag=18`) to `<video>.src`!
 * 3. Intercepts `googlevideo.com/videoplayback` requests in `shouldInterceptRequest` (capturing
 *    both direct URLs and any BotGuard `pot=` PO token) AND runs an in-page signed-in
 *    `fetch('/youtubei/v1/player?prettyPrint=false')` with `TVHTML5` (`TV_SAMSUNG`) + live `ytcfg`.
 */
object YouTubeWebViewUnlock {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun extractPlayerJson(context: Context, videoId: String): JSONObject? {
        if (videoId.isBlank()) return null
        return withTimeoutOrNull(24_000L) {
            runPage(context, videoId, watchUrl(videoId), 22_000L)
                ?: runPage(context, videoId, embedUrl(videoId), 14_000L)
        }
    }

    /**
     * Synchronous helper called from background IO threads inside `YouTubePlayerUnlock.fetchUnlockedPlayerJson`.
     */
    fun extractPlayerJsonBlocking(videoId: String, timeoutMs: Long = 30_000L): JSONObject? {
        if (videoId.isBlank() || Looper.myLooper() == Looper.getMainLooper()) return null
        val ctx = runCatching { PlaybackCenter.appContext }.getOrNull() ?: return null
        val ref = AtomicReference<JSONObject?>(null)
        val latch = CountDownLatch(1)
        Thread {
            try {
                val res = kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(timeoutMs) {
                        runPage(ctx, videoId, watchUrl(videoId), (timeoutMs * 2 / 3).coerceAtLeast(9_000L))
                            ?: runPage(ctx, videoId, embedUrl(videoId), (timeoutMs / 3).coerceAtLeast(6_000L))
                    }
                }
                ref.set(res)
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }.start()
        runCatching { latch.await(timeoutMs + 1_000L, TimeUnit.MILLISECONDS) }
        return ref.get()
    }

    private fun watchUrl(videoId: String) =
        "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1&hl=en"

    private fun embedUrl(videoId: String) =
        "https://www.youtube.com/embed/$videoId?hl=en&autoplay=1&has_verified=1"

    private val DISABLE_MSE_JS = """
        (function() {
            try {
                delete window.MediaSource;
                delete window.WebKitMediaSource;
                delete window.ManagedMediaSource;
                Object.defineProperty(window, 'MediaSource', { value: undefined, configurable: true });
                Object.defineProperty(window, 'WebKitMediaSource', { value: undefined, configurable: true });
                Object.defineProperty(window, 'ManagedMediaSource', { value: undefined, configurable: true });
            } catch (e) {}
        })();
    """.trimIndent()

    private fun buildProbeJs(videoId: String): String = """
        (function() {
            try {
                if (window.ytcfg && typeof window.ytcfg.get === 'function' && window.SparkBridge) {
                    var vd = window.ytcfg.get('VISITOR_DATA') || '';
                    var ds = window.ytcfg.get('DATASYNC_ID') || '';
                    var si = String(window.ytcfg.get('SESSION_INDEX') !== undefined ? window.ytcfg.get('SESSION_INDEX') : '');
                    var sts = Number(window.ytcfg.get('STS') || 0);
                    window.SparkBridge.onYtCfg(vd, ds, si, sts);
                }

                var btns = document.querySelectorAll('button, a, tp-yt-paper-button, ytd-button-renderer');
                for (var i = 0; i < btns.length; i++) {
                    var t = ((btns[i].innerText || '') + (btns[i].textContent || '') + (btns[i].getAttribute('aria-label') || '')).toLowerCase();
                    if (t.indexOf('i understand') >= 0 || t.indexOf('understand and wish') >= 0 ||
                        t.indexOf('proceed') >= 0 || t.indexOf('confirm') >= 0) {
                        try { btns[i].click(); } catch (_) {}
                    }
                }

                var vid = document.querySelector('video');
                if (vid) {
                    try { vid.muted = true; vid.play(); } catch (_) {}
                    var vSrc = vid.currentSrc || vid.src || '';
                    if (vSrc && vSrc.indexOf('googlevideo.com/videoplayback') !== -1 && window.SparkBridge) {
                        window.SparkBridge.onDirectVideoUrl(vSrc);
                    }
                }

                if (!window.__sparkInPageFetchStarted && window.ytcfg && typeof window.ytcfg.get === 'function') {
                    window.__sparkInPageFetchStarted = true;
                    var stsVal = Number(window.ytcfg.get('STS') || 0);
                    var visitorVal = window.ytcfg.get('VISITOR_DATA') || '';
                    var bodyObj = {
                        videoId: '$videoId',
                        racyCheckOk: true,
                        contentCheckOk: true,
                        context: {
                            client: {
                                clientName: 'TVHTML5',
                                clientVersion: '5.20260707',
                                deviceMake: 'Samsung',
                                deviceModel: 'UKS9800',
                                osName: 'Tizen',
                                osVersion: '2.4.0',
                                platform: 'TV',
                                hl: 'en',
                                gl: 'US',
                                visitorData: visitorVal
                            },
                            user: { lockedSafetyMode: false }
                        }
                    };
                    if (stsVal > 0) {
                        bodyObj.playbackContext = {
                            contentPlaybackContext: {
                                signatureTimestamp: stsVal,
                                html5Preference: 'HTML5_PREF_WANTS'
                            }
                        };
                    }
                    fetch('/youtubei/v1/player?prettyPrint=false', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-YouTube-Client-Name': '7',
                            'X-YouTube-Client-Version': '5.20260707'
                        },
                        body: JSON.stringify(bodyObj)
                    }).then(function(r) { return r.text(); }).then(function(txt) {
                        if (txt && txt.indexOf('"status":"OK"') !== -1 && window.SparkBridge) {
                            window.SparkBridge.onPlayerJson(txt);
                        }
                    }).catch(function(_) {});
                }

                var p = window.ytInitialPlayerResponse;
                if (!p && window.ytplayer && window.ytplayer.config && window.ytplayer.config.args &&
                    window.ytplayer.config.args.raw_player_response) {
                    var raw = window.ytplayer.config.args.raw_player_response;
                    p = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                }
                if (p && p.playabilityStatus && p.playabilityStatus.status === 'OK' && p.streamingData && window.SparkBridge) {
                    window.SparkBridge.onInitialPlayerJson(JSON.stringify(p));
                }
            } catch (e) {}
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runPage(
        context: Context,
        videoId: String,
        pageUrl: String,
        timeoutMs: Long
    ): JSONObject? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    var webView: WebView? = null
                    var finished = false
                    val interceptedUrls = CopyOnWriteArrayList<String>()
                    val capturedPoToken = AtomicReference<String?>(null)
                    val initialPlayerJsonRef = AtomicReference<String?>(null)
                    val probeJs = buildProbeJs(videoId)

                    fun finishWith(json: JSONObject?) {
                        if (finished) return
                        finished = true
                        mainHandler.post {
                            runCatching {
                                webView?.stopLoading()
                                webView?.loadUrl("about:blank")
                                webView?.destroy()
                            }
                            webView = null
                        }
                        if (cont.isActive) cont.resume(json)
                    }

                    val created = runCatching {
                        WebView(context.applicationContext)
                    }.getOrNull()
                    if (created == null) {
                        finishWith(null)
                        return@post
                    }
                    webView = created
                    cont.invokeOnCancellation {
                        mainHandler.post {
                            runCatching {
                                webView?.stopLoading()
                                webView?.loadUrl("about:blank")
                                webView?.destroy()
                            }
                            webView = null
                        }
                    }

                    // Inject saved Google cookies ONE BY ONE across all YouTube domains
                    YouTubeAuth.syncCookiesToWebView(created)

                    val bridge = object {
                        @JavascriptInterface
                        fun onYtCfg(visitorData: String?, datasyncId: String?, sessionIndex: String?, sts: Int) {
                            YouTubeAuth.updateSessionMetadata(
                                visitorData = visitorData?.takeIf { it.isNotBlank() },
                                datasyncId = datasyncId?.takeIf { it.isNotBlank() },
                                sessionIndex = sessionIndex?.takeIf { it.isNotBlank() },
                                sts = sts.takeIf { it > 18000 }
                            )
                        }

                        @JavascriptInterface
                        fun onDirectVideoUrl(url: String?) {
                            if (!url.isNullOrBlank() && url.contains("googlevideo.com/videoplayback")) {
                                val cleanUrl = url.replace(Regex("([?&])range=[^&]+&?"), "$1").trimEnd('&', '?')
                                interceptedUrls.add(cleanUrl)
                                val built = buildSynthesizedPlayerJson(
                                    videoId,
                                    interceptedUrls,
                                    initialPlayerJsonRef.get(),
                                    capturedPoToken.get()
                                )
                                if (built != null) {
                                    finishWith(built)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onPlayerJson(json: String?) {
                            if (!json.isNullOrBlank() && json.contains("\"streamingData\"")) {
                                val parsed = runCatching { JSONObject(json) }.getOrNull()
                                if (parsed != null) {
                                    val normalized = YouTubePlayerUnlock.deobfuscateAndNormalize(parsed, videoId)
                                    val sd = normalized.optJSONObject("streamingData")
                                    val hasFormats = (sd?.optJSONArray("formats")?.length() ?: 0) > 0 ||
                                        (sd?.optJSONArray("adaptiveFormats")?.length() ?: 0) > 0
                                    if (hasFormats) {
                                        finishWith(normalized)
                                    }
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onInitialPlayerJson(json: String?) {
                            if (!json.isNullOrBlank()) {
                                initialPlayerJsonRef.compareAndSet(null, json)
                            }
                        }
                    }

                    created.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = YouTubeAuth.UA_WEB_CREATOR
                    }
                    created.addJavascriptInterface(bridge, "SparkBridge")

                    var attempts = 0
                    val pollRunnable = object : Runnable {
                        override fun run() {
                            if (finished || !cont.isActive) return
                            val wv = webView ?: return
                            attempts++
                            wv.evaluateJavascript(probeJs, null)
                            if (attempts >= 22) {
                                val fallback = buildSynthesizedPlayerJson(
                                    videoId,
                                    interceptedUrls,
                                    initialPlayerJsonRef.get(),
                                    capturedPoToken.get()
                                ) ?: initialPlayerJsonRef.get()?.let { runCatching { JSONObject(it) }.getOrNull() }
                                finishWith(fallback)
                            } else {
                                mainHandler.postDelayed(this, 500L)
                            }
                        }
                    }

                    created.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(DISABLE_MSE_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(DISABLE_MSE_JS, null)
                            if (!finished && cont.isActive) {
                                mainHandler.postDelayed(pollRunnable, 350L)
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.contains("googlevideo.com/videoplayback")) {
                                runCatching {
                                    val pot = request?.url?.getQueryParameter("pot")
                                    if (!pot.isNullOrBlank()) {
                                        capturedPoToken.set(pot)
                                    }
                                }
                                val cleanUrl = reqUrl.replace(Regex("([?&])range=[^&]+&?"), "$1")
                                    .trimEnd('&', '?')
                                interceptedUrls.add(cleanUrl)
                                val built = buildSynthesizedPlayerJson(
                                    videoId,
                                    interceptedUrls,
                                    initialPlayerJsonRef.get(),
                                    capturedPoToken.get()
                                )
                                if (built != null) {
                                    finishWith(built)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    val headers = HashMap<String, String>()
                    headers["Referer"] = "https://www.youtube.com/"
                    headers["Accept-Language"] = "en-US,en;q=0.9"
                    created.loadUrl(pageUrl, headers)
                }
            }
        }
    }

    private fun buildSynthesizedPlayerJson(
        videoId: String,
        interceptedUrls: List<String>,
        initialPlayerJson: String?,
        poToken: String?
    ): JSONObject? {
        if (interceptedUrls.isEmpty() && initialPlayerJson.isNullOrBlank()) return null

        val root = if (!initialPlayerJson.isNullOrBlank()) {
            runCatching { JSONObject(initialPlayerJson) }.getOrNull() ?: JSONObject()
        } else {
            JSONObject()
        }

        root.put("playabilityStatus", JSONObject().put("status", "OK"))

        val vd = root.optJSONObject("videoDetails") ?: JSONObject().also { root.put("videoDetails", it) }
        if (vd.optString("videoId").isBlank()) vd.put("videoId", videoId)
        if (vd.optString("title").isBlank()) vd.put("title", "YouTube Video")
        if (vd.optString("author").isBlank()) vd.put("author", "YouTube")

        val sd = root.optJSONObject("streamingData") ?: JSONObject().also { root.put("streamingData", it) }
        val formats = sd.optJSONArray("formats") ?: JSONArray().also { sd.put("formats", it) }
        val adaptive = sd.optJSONArray("adaptiveFormats") ?: JSONArray().also { sd.put("adaptiveFormats", it) }

        for (rawUrl in interceptedUrls.distinct()) {
            val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: continue
            val itag = uri.getQueryParameter("itag")?.toIntOrNull() ?: 18
            val mime = uri.getQueryParameter("mime") ?: if (itag == 140) "audio/mp4" else "video/mp4"
            val durSec = uri.getQueryParameter("dur")?.toDoubleOrNull() ?: 0.0
            val durMs = (durSec * 1000.0).toLong().coerceAtLeast(0L)

            if (durSec > 0 && vd.optString("lengthSeconds") in listOf("", "0")) {
                vd.put("lengthSeconds", durSec.toLong().toString())
            }

            val streamObj = JSONObject()
                .put("itag", itag)
                .put("url", rawUrl)
                .put(
                    "mimeType",
                    if (mime.contains("audio")) "$mime; codecs=\"mp4a.40.2\""
                    else "$mime; codecs=\"avc1.42001E, mp4a.40.2\""
                )
                .put("width", 640)
                .put("height", 360)
                .put("fps", 30)
                .put("qualityLabel", "360p")
                .put("bitrate", 500_000)
                .put("averageBitrate", 500_000)
                .put("approxDurationMs", durMs.toString())

            if (mime.contains("audio")) {
                adaptive.put(streamObj)
            } else {
                // Prepend verified direct progressive stream
                val newFormats = JSONArray()
                newFormats.put(streamObj)
                for (i in 0 until formats.length()) {
                    newFormats.put(formats.opt(i))
                }
                sd.put("formats", newFormats)
            }
        }

        if (!poToken.isNullOrBlank()) {
            for (i in 0 until adaptive.length()) {
                val item = adaptive.optJSONObject(i) ?: continue
                val u = item.optString("url", "")
                if (u.startsWith("http") && !u.contains("pot=")) {
                    val sep = if (u.contains("?")) "&" else "?"
                    item.put("url", "${u}${sep}pot=$poToken")
                }
            }
        }

        return if ((sd.optJSONArray("formats")?.length() ?: 0) > 0 ||
            (sd.optJSONArray("adaptiveFormats")?.length() ?: 0) > 0
        ) {
            root
        } else {
            null
        }
    }
}
