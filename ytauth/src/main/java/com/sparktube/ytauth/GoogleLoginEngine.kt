package com.sparktube.ytauth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Headless Google sign-in engine (:ytauth library).
 *
 * Drives an off-screen WebView through accounts.google.com exactly like a
 * real browser session:
 *   email -> password -> 2FA (TOTP code generated from the stored Base32
 *   secret) -> redirect to YouTube -> capture the full session cookie set
 *   (SID / HSID / SSID / SAPISID / __Secure-1PSID / __Secure-3PSID / ...).
 *
 * The captured cookie string is returned to the caller; the engine never
 * persists credentials itself (storage is delegated to [SecureStore] /
 * the host app).
 */
object GoogleLoginEngine {

    /** Standard Chrome Android UA (no "; wv") so Google allows sign-in. */
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=false" +
            "&hl=en&continue=https%3A%2F%2Fwww.youtube.com%2F"

    private val cookieUrls = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://music.youtube.com",
        "https://accounts.youtube.com",
        "https://youtube.com"
    )

    sealed class LoginResult {
        data class Success(val cookies: String, val account: String) : LoginResult()
        data class Failure(val kind: String, val message: String) : LoginResult()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Callback-based core. All callbacks are delivered on the main thread. */
    fun loginAsync(
        context: Context,
        email: String,
        password: String,
        totpSecret: String?,
        timeoutMs: Long = 90_000L,
        callback: (LoginResult) -> Unit
    ) {
        mainHandler.post {
            var webView: WebView? = null
            var finished = false
            var pollCount = 0
            // Per-stage action guards so a stage is acted on at most 3 times.
            val stageActions = HashMap<String, Int>()

            fun finish(result: LoginResult) {
                if (finished) return
                finished = true
                mainHandler.post {
                    runCatching {
                        webView?.stopLoading()
                        webView?.destroy()
                    }
                    webView = null
                }
                callback(result)
            }

            val created = runCatching { WebView(context.applicationContext) }.getOrNull()
            if (created == null) {
                finish(LoginResult.Failure("webview_unavailable", "Could not create WebView"))
                return@post
            }
            webView = created

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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = UA
            }

            val poll = object : Runnable {
                override fun run() {
                    if (finished) return
                    pollCount++
                    if (pollCount * POLL_INTERVAL_MS > timeoutMs) {
                        finish(LoginResult.Failure("timeout", "Sign-in did not complete in time"))
                        return
                    }
                    created.evaluateJavascript(DETECT_STAGE_JS) { raw ->
                        if (finished) return@evaluateJavascript
                        val json = runCatching {
                            JSONObject(decodeJsString(raw) ?: "{}")
                        }.getOrNull()
                        val stage = json?.optString("stage").orEmpty().ifBlank { "wait" }
                        when (stage) {
                            "youtube" -> {
                                val cookies = captureCookies()
                                if (hasAuthCookies(cookies)) {
                                    finish(LoginResult.Success(cookies, email))
                                } else if (pollCount > 60) {
                                    finish(
                                        LoginResult.Failure(
                                            "no_session_cookies",
                                            "Redirected to YouTube but session cookies were not issued"
                                        )
                                    )
                                } else {
                                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                                }
                            }

                            "bad_password" -> finish(
                                LoginResult.Failure(
                                    "bad_credentials",
                                    "Google rejected the email or password"
                                )
                            )

                            "challenge" -> finish(
                                LoginResult.Failure(
                                    "challenge_phone",
                                    "Google asked for phone/2FA approval that cannot be automated; approve the sign-in in your Google app or re-check the credentials"
                                )
                            )

                            "totp" -> {
                                if (totpSecret.isNullOrBlank()) {
                                    finish(
                                        LoginResult.Failure(
                                            "totp_required",
                                            "2-Step Verification is enabled; save your 2FA secret (base32) in Auto Sign-in first"
                                        )
                                    )
                                    return@evaluateJavascript
                                }
                                if ((stageActions["totp"] ?: 0) >= 3) {
                                    finish(
                                        LoginResult.Failure(
                                            "totp_rejected",
                                            "The 2FA code was not accepted — verify the 2FA secret"
                                        )
                                    )
                                    return@evaluateJavascript
                                }
                                stageActions["totp"] = (stageActions["totp"] ?: 0) + 1
                                val code = runCatching { Totp.generate(totpSecret) }.getOrNull()
                                if (code.isNullOrBlank()) {
                                    finish(
                                        LoginResult.Failure(
                                            "totp_bad_secret",
                                            "The 2FA secret is not valid base32"
                                        )
                                    )
                                    return@evaluateJavascript
                                }
                                created.evaluateJavascript(fillScript("totp", code)) { }
                                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                            }

                            "email", "password", "account_choose" -> {
                                val key = stage
                                if ((stageActions[key] ?: 0) >= 3) {
                                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                                    return@evaluateJavascript
                                }
                                stageActions[key] = (stageActions[key] ?: 0) + 1
                                when (key) {
                                    "email" -> created.evaluateJavascript(fillScript("email", email)) { }
                                    "password" -> created.evaluateJavascript(fillScript("password", password)) { }
                                    "account_choose" -> created.evaluateJavascript(chooseAccountScript(email)) { }
                                }
                                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                            }

                            else -> mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }
            }

            created.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!finished && pollCount == 0) {
                        mainHandler.postDelayed(poll, 600L)
                    }
                }
            }

            created.loadUrl(LOGIN_URL)
        }
    }

    /** Suspend wrapper for coroutine callers. */
    suspend fun login(
        context: Context,
        email: String,
        password: String,
        totpSecret: String?,
        timeoutMs: Long = 90_000L
    ): LoginResult = suspendCancellableCoroutine { cont ->
        loginAsync(context, email, password, totpSecret, timeoutMs) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    private const val POLL_INTERVAL_MS = 800L

    private fun captureCookies(): String {
        val combined = LinkedHashMap<String, String>()
        runCatching {
            val cm = CookieManager.getInstance()
            runCatching { cm.flush() }
            cookieUrls.forEach { domain ->
                val raw = cm.getCookie(domain).orEmpty()
                if (raw.isNotBlank()) parseCookies(raw).forEach { (k, v) -> combined[k] = v }
            }
        }
        return combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseCookies(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
        return map
    }

    private fun hasAuthCookies(cookieHeader: String): Boolean {
        val map = parseCookies(cookieHeader)
        val hasSapisid = listOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID").any { !map[it].isNullOrBlank() }
        val hasSid = listOf("SID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO").any { !map[it].isNullOrBlank() }
        return hasSapisid && hasSid
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return null
        return runCatching { org.json.JSONArray("[$raw]").optString(0).takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun fillScript(kind: String, value: String): String {
        val selector = when (kind) {
            "email" -> "input[type=\"email\"]"
            "totp" -> "#totpPin"
            else -> "input[type=\"password\"]"
        }
        val buttonId = when (kind) {
            "email" -> "identifierNext"
            "totp" -> "totpNext"
            else -> "passwordNext"
        }
        return "(function(){try{" +
            "var el=document.querySelector('$selector');" +
            "if(!el)return 'no_el';" +
            "el.focus();" +
            "var d=Object.getOwnPropertyDescriptor(el.__proto__||HTMLInputElement.prototype,'value');" +
            "if(d&&d.set)d.set.call(el,${jsString(value)});else el.value=${jsString(value)};" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "var btn=document.getElementById('$buttonId');" +
            "if(btn){btn.click();return 'ok';}" +
            "var form=el.closest('form');" +
            "if(form){if(form.requestSubmit){form.requestSubmit();}else{form.submit();}return 'ok_form';}" +
            "return 'no_btn';" +
            "}catch(e){return 'err:'+e;}})()"
    }

    private fun chooseAccountScript(email: String): String {
        return "(function(){try{" +
            "var all=document.querySelectorAll('div[data-identifier]');" +
            "for(var i=0;i<all.length;i++){" +
            "if((all[i].getAttribute('data-identifier')||'').toLowerCase()===" + jsString(email.lowercase()) + "){" +
            "all[i].click();return 'ok';}}" +
            "var els=document.querySelectorAll('div,li,button');" +
            "for(var j=0;j<els.length;j++){" +
            "var t=(els[j].innerText||'').toLowerCase();" +
            "if(t.indexOf('use another account')>=0){els[j].click();return 'ok_another';}}" +
            "if(all.length){all[0].click();return 'ok_first';}" +
            "return 'none';" +
            "}catch(e){return 'err';}})()"
    }

    private const val DETECT_STAGE_JS =
        "(function(){try{" +
            "var url=location.href||'';" +
            "var body=document.body?(document.body.innerText||''):'';" +
            "function visible(el){return el&&el.offsetParent!==null;}" +
            "var email=document.querySelector('input[type=\"email\"]');" +
            "var pass=document.querySelector('input[type=\"password\"]');" +
            "var totp=document.querySelector('#totpPin');" +
            "if(/youtube\\.com/.test(url)&&!/accounts\\.google\\.com/.test(url))return JSON.stringify({stage:'youtube'});" +
            "if(visible(totp))return JSON.stringify({stage:'totp'});" +
            "if(visible(pass))return JSON.stringify({stage:'password'});" +
            "if(visible(email))return JSON.stringify({stage:'email'});" +
            "if(/Choose an account/i.test(body))return JSON.stringify({stage:'account_choose'});" +
            "if(/couldn.?t sign you in|Wrong password/i.test(body))return JSON.stringify({stage:'bad_password'});" +
            "if(/2-Step Verification|Confirm your sign-in|confirm it.s really you|recovery email|recovery phone/i.test(body))return JSON.stringify({stage:'challenge'});" +
            "return JSON.stringify({stage:'wait'});" +
            "}catch(e){return JSON.stringify({stage:'error',msg:String(e&&e.message||e)});}})()"
}
