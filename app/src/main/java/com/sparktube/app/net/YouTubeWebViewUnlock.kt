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
 * When YouTube's InnerTube API rejects cookie-only requests with
 * LOGIN_REQUIRED / "cannot be watched anonymously", an authenticated WebView
 * running YouTube's own page JS (with the user's 22+ Google session cookies,
 * PoToken, and signatureTimestamp) resolves `window.ytInitialPlayerResponse`
 * directly.
 */
object YouTubeWebViewUnlock {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun extractPlayerJson(context: Context, videoId: String): JSONObject? {
        if (videoId.isBlank() || !YouTubeAuth.isLoggedIn()) return null
        return withTimeoutOrNull(9_000L) {
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

                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(created, true)
                    }

                    @SuppressLint("SetJavaScriptEnabled")
                    created.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = YouTubeAuth.UA_WEB_CHROME
                    }

                    var attempts = 0
                    val pollRunnable = object : Runnable {
                        override fun run() {
                            if (finished || !cont.isActive) return
                            val wv = webView ?: return
                            attempts++
                            wv.evaluateJavascript(
                                "(function(){try{var p=window.ytInitialPlayerResponse;if(!p&&window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&window.ytplayer.config.args.raw_player_response){p=window.ytplayer.config.args.raw_player_response;}if(p&&p.playabilityStatus&&p.playabilityStatus.status!=='OK'){var btns=document.querySelectorAll('button,a');for(var i=0;i<btns.length;i++){var t=(btns[i].innerText||'').toLowerCase();if(t.indexOf('understand')>=0||t.indexOf('proceed')>=0||t.indexOf('confirm')>=0){btns[i].click();break;}}}return p?JSON.stringify(p):'';}catch(e){return '';}})()"
                            ) { raw ->
                                if (finished || !cont.isActive) return@evaluateJavascript
                                val parsed = decodeJsString(raw)?.let { text ->
                                    runCatching { JSONObject(text) }.getOrNull()
                                }
                                val status = parsed?.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                                val hasSd = parsed?.optJSONObject("streamingData") != null
                                if (status.equals("OK", ignoreCase = true) && hasSd) {
                                    finishWith(parsed)
                                } else if (attempts < 12) {
                                    mainHandler.postDelayed(this, 450L)
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

                    created.loadUrl("https://m.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
                }
            }
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return null
        return runCatching {
            org.json.JSONArray("[$raw]").optString(0).takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
