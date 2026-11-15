package com.sparktube.app.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.sparktube.app.R
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityYoutubeLoginBinding
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.net.YouTubePlayerUnlock
import com.sparktube.app.util.Themes
import java.util.Locale

/**
 * Crash-safe In-App Google / YouTube Sign-In Activity (v1.7.8):
 * - Uses a standard Chrome Mobile User-Agent (without `; wv`) so Google OAuth / ServiceLogin
 *   allows normal sign-in without `disallowed_useragent` errors.
 * - Captures `SAPISID`, `__Secure-3PAPISID`, `SID`, `__Secure-1PSID`, `__Secure-3PSID`, `LOGIN_INFO`
 *   across all YouTube & Google domains via [CookieManager] AND extracts live `VISITOR_DATA`,
 *   `DATASYNC_ID`, `SESSION_INDEX`, and `STS` from `window.ytcfg` on login completion.
 */
class YouTubeLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubeLoginBinding
    private var webView: WebView? = null
    private var savedSuccessfully = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener {
            captureCookiesIfPresent(finishActivity = true, silentIfMissing = true)
        }

        binding.doneButton.setOnClickListener {
            if (!captureCookiesIfPresent(finishActivity = true, silentIfMissing = false)) {
                Toast.makeText(
                    this,
                    R.string.youtube_login_not_completed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    captureCookiesIfPresent(finishActivity = true, silentIfMissing = true)
                }
            }
        })

        val createdWebView = runCatching {
            WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }.getOrNull()

        if (createdWebView == null) {
            Toast.makeText(this, R.string.youtube_webview_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        webView = createdWebView
        binding.webViewContainer.addView(createdWebView)

        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(createdWebView, true)
        }

        createdWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = YouTubeAuth.UA_WEB_CHROME
        }

        createdWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onYtCfg(visitorData: String?, datasyncId: String?, sessionIndex: String?, sts: Int) {
                YouTubeAuth.updateSessionMetadata(
                    visitorData = visitorData?.takeIf { it.isNotBlank() },
                    datasyncId = datasyncId?.takeIf { it.isNotBlank() },
                    sessionIndex = sessionIndex?.takeIf { it.isNotBlank() },
                    sts = sts.takeIf { it > 18000 }
                )
            }
        }, "SparkLoginBridge")

        createdWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (isFinishing || isDestroyed) return
                binding.webProgressBar.isVisible = newProgress in 1..99
                binding.webProgressBar.progress = newProgress
            }
        }

        createdWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isFinishing || isDestroyed) return
                binding.headerSubtitle.text = url.orEmpty().ifBlank { getString(R.string.youtube_login_subtitle) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isFinishing || isDestroyed || savedSuccessfully) return
                extractYtCfgFromWebView(view)
                val u = url.orEmpty().lowercase(Locale.US)
                if (u.contains("youtube.com") && !u.contains("accounts.google.com") &&
                    !u.contains("consent.youtube.com")
                ) {
                    view?.postDelayed({
                        if (!isFinishing && !isDestroyed && !savedSuccessfully) {
                            extractYtCfgFromWebView(view)
                            captureCookiesIfPresent(finishActivity = true, silentIfMissing = true)
                        }
                    }, 900L)
                }
            }
        }

        createdWebView.loadUrl(GOOGLE_YOUTUBE_LOGIN_URL)
    }

    private fun extractYtCfgFromWebView(view: WebView?) {
        view ?: return
        val js = """
            (function() {
                try {
                    if (window.ytcfg && typeof window.ytcfg.get === 'function' && window.SparkLoginBridge) {
                        var vd = window.ytcfg.get('VISITOR_DATA') || '';
                        var ds = window.ytcfg.get('DATASYNC_ID') || '';
                        var si = String(window.ytcfg.get('SESSION_INDEX') !== undefined ? window.ytcfg.get('SESSION_INDEX') : '0');
                        var sts = Number(window.ytcfg.get('STS') || 0);
                        window.SparkLoginBridge.onYtCfg(vd, ds, si, sts);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun collectAllYouTubeCookies(): String {
        return runCatching {
            val cm = CookieManager.getInstance()
            runCatching { cm.flush() }
            val combined = LinkedHashMap<String, String>()
            YouTubeAuth.COOKIE_DOMAINS.forEach { domain ->
                val raw = cm.getCookie(domain).orEmpty()
                if (raw.isNotBlank()) {
                    combined.putAll(YouTubeAuth.parseCookieMap(raw))
                }
            }
            combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }.getOrDefault("")
    }

    private fun captureCookiesIfPresent(finishActivity: Boolean, silentIfMissing: Boolean): Boolean {
        val raw = collectAllYouTubeCookies()
        if (YouTubeAuth.hasAuthCookiesInString(raw)) {
            savedSuccessfully = true
            extractYtCfgFromWebView(webView)
            runCatching { CookieManager.getInstance().flush() }
            YouTubeAuth.saveCookies(raw, getString(R.string.youtube_account_connected))
            YouTubePlayerUnlock.clearCache()
            Thread { runCatching { YouTubeAuth.visitorData(allowNetworkFetch = true) } }.start()
            YtRepository.clearCache()
            Toast.makeText(
                this,
                R.string.youtube_login_success,
                Toast.LENGTH_SHORT
            ).show()
            setResult(Activity.RESULT_OK)
            if (finishActivity) finish()
            return true
        }
        if (finishActivity && silentIfMissing) {
            finish()
        }
        return false
    }

    override fun onDestroy() {
        runCatching {
            binding.webViewContainer.removeAllViews()
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
        super.onDestroy()
    }

    companion object {
        private const val GOOGLE_YOUTUBE_LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=en" +
                "&continue=https%3A%2F%2Fm.youtube.com%2F"

        fun start(context: Context) {
            context.startActivity(Intent(context, YouTubeLoginActivity::class.java))
        }
    }
}
