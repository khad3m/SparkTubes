package com.sparktube.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Last-resort signed-in WebView extractor for age-restricted (18+) videos.
 *
 * YouTube's plain InnerTube clients cannot serve age-gated videos anymore
 * (2026: every anonymous client answers LOGIN_REQUIRED). What still works is
 * exactly what a signed-in desktop browser does — so this engine reproduces it:
 * 1. The saved Google session cookies ([YouTubeAuth]) are injected into the
 *    WebView CookieManager for every YouTube domain before the page loads.
 * 2. The desktop watch page (`www.youtube.com/watch?v=ID&bpctr=…&has_verified=1`)
 *    is loaded with a desktop Chrome UA; its age-confirm interstitial button is
 *    auto-clicked and `window.ytInitialPlayerResponse` is polled until it is OK.
 * 3. If the watch page never becomes playable, the signed-in `/embed/` page is
 *    tried the same way (second attempt).
 */
object YouTubeWebViewUnlock {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cookieDomains = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://music.youtube.com",
        "https://accounts.youtube.com",
        "https://youtube.com"
    )

    suspend fun extractPlayerJson(context: Context, videoId: String): JSONObject? {
        if (videoId.isBlank() || !YouTubeAuth.isLoggedIn()) return null
        return withTimeoutOrNull(24_000L) {
            runPage(context, videoId, watchUrl(videoId)) ?: runPage(context, videoId, embedUrl(videoId))
        }
    }

    private fun watchUrl(videoId: String) =
        "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1&hl=en"

    private fun embedUrl(videoId: String) =
        "https://www.youtube.com/embed/$videoId?hl=en&autoplay=0"

    private suspend fun runPage(context: Context, videoId: String, pageUrl: String): JSONObject? {
        return withTimeoutOrNull(11_000L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    var webView: WebView? = null
                    var finished = false

                    fun finishWith(json: JSONObject?) {
                        if (finished) return
                        finished = true
                        mainHandler.post {
                            runCatching {
                                webView?.stopLoading()
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
                                webView?.destroy()
                            }
                            webView = null
                        }
                    }

                    // Inject the saved Google session cookies so the page loads as the
                    // signed-in user (the WebView cookie jar may be empty after a
                    // session clear or a fresh process, even when AppPrefs has them).
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(created, true)
                        val sessionCookies = YouTubeAuth.buildCookieHeader()
                        if (sessionCookies.isNotBlank()) {
                            cookieDomains.forEach { domain ->
                                runCatching { cm.setCookie(domain, sessionCookies) }
                            }
                            runCatching { cm.flush() }
                        }
                    }

                    @SuppressLint("SetJavaScriptEnabled")
                    created.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // Desktop Chrome UA: the desktop watch page is the one that
                        // serves the age-confirm interstitial and a playable response
                        // for signed-in users; the mobile page stays gated.
                        userAgentString = YouTubeAuth.UA_WEB_CREATOR
                    }

                    var attempts = 0
                    val pollRunnable = object : Runnable {
                        override fun run() {
                            if (finished || !cont.isActive) return
                            val wv = webView ?: return
                            attempts++
                            wv.evaluateJavascript(POLL_PLAYER_RESPONSE_JS) { raw ->
                                if (finished || !cont.isActive) return@evaluateJavascript
                                val parsed = decodeJsString(raw)?.let { text ->
                                    runCatching { JSONObject(text) }.getOrNull()
                                }
                                val status = parsed?.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                                val hasSd = parsed?.optJSONObject("streamingData") != null
                                if (status.equals("OK", ignoreCase = true) && hasSd) {
                                    finishWith(parsed)
                                } else if (attempts < 24) {
                                    mainHandler.postDelayed(this, 420L)
                                } else {
                                    finishWith(null)
                                }
                            }
                        }
                    }

                    created.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!finished && cont.isActive) {
                                mainHandler.postDelayed(pollRunnable, 350L)
                            }
                        }
                    }

                    created.loadUrl(pageUrl)
                }
            }
        }
    }

    private const val POLL_PLAYER_RESPONSE_JS =
        "(function(){try{" +
            "var p=window.ytInitialPlayerResponse;" +
            "if(!p&&window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&" +
            "window.ytplayer.config.args.raw_player_response){p=window.ytplayer.config.args.raw_player_response;}" +
            "if(p&&p.playabilityStatus&&p.playabilityStatus.status!=='OK'){" +
            "var btns=document.querySelectorAll('button,a,tp-yt-paper-button,ytd-button-renderer');" +
            "for(var i=0;i<btns.length;i++){" +
            "var t=((btns[i].innerText||'')+(btns[i].textContent||'')).toLowerCase();" +
            "if(t.indexOf('i understand')>=0||t.indexOf('understand and wish')>=0||" +
            "t.indexOf('proceed')>=0||t.indexOf('confirm')>=0||t.indexOf('yes')>=0){" +
            "btns[i].click();break;}}}" +
            "return p?JSON.stringify(p):'';" +
            "}catch(e){return '';}})()"

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return null
        return runCatching {
            org.json.JSONArray("[$raw]").optString(0).takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
