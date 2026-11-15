package com.sparktube.app.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
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
import com.sparktube.app.util.Themes
import java.util.Locale

/**
 * Crash-safe In-App Google / YouTube Sign-In Activity:
 * - Uses a standard Chrome Mobile User-Agent (without `; wv`) so Google OAuth / ServiceLogin
 *   allows normal sign-in without `disallowed_useragent` errors.
 * - Captures `SAPISID`, `__Secure-3PAPISID`, `SID`, and session cookies via [CookieManager]
 *   and stores them in [YouTubeAuth] so `NewPipeExtractor`, `OkHttpDownloader`, `ExoPlayer`,
 *   and `DownloadCenter` can play & download age-restricted and login-required videos.
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
            // Standard Chrome Android User-Agent (without "; wv") so Google Login allows sign-in
            userAgentString = YouTubeAuth.UA_WEB_CHROME
        }

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
                // Block external intent:// schemes from crashing or leaving the login flow
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
                val u = url.orEmpty().lowercase(Locale.US)
                // Auto-detect completed login once redirected back to youtube.com
                if (u.contains("youtube.com") && !u.contains("accounts.google.com") &&
                    !u.contains("consent.youtube.com")
                ) {
                    view?.postDelayed({
                        if (!isFinishing && !isDestroyed && !savedSuccessfully) {
                            captureCookiesIfPresent(finishActivity = true, silentIfMissing = true)
                        }
                    }, 900L)
                }
            }
        }

        createdWebView.loadUrl(GOOGLE_YOUTUBE_LOGIN_URL)
    }

    private fun collectAllYouTubeCookies(): String {
        return runCatching {
            val cm = CookieManager.getInstance()
            runCatching { cm.flush() }
            val urls = listOf(
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://music.youtube.com",
                "https://accounts.youtube.com",
                "https://youtube.com"
            )
            val combined = LinkedHashMap<String, String>()
            urls.forEach { domain ->
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
            runCatching { CookieManager.getInstance().flush() }
            YouTubeAuth.saveCookies(raw, getString(R.string.youtube_account_connected))
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
