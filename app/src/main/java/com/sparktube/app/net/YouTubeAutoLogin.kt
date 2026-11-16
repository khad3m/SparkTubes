package com.sparktube.app.net

import android.content.Context
import com.sparktube.ytauth.GoogleLoginEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Establishes a signed-in Google/YouTube session automatically from the
 * credentials saved via the Auto Sign-in screen (:ytauth engine). Called when
 * an age-restricted video needs the user's session so playback can proceed
 * without manual steps. Throttled to one headless attempt per 10 minutes.
 */
object YouTubeAutoLogin {

    @Volatile private var lastAttemptAt: Long = 0L
    @Volatile private var lastError: String? = null

    fun lastErrorOrNull(): String? = lastError

    suspend fun ensureSignedIn(context: Context, force: Boolean = false): Boolean {
        if (YouTubeAuth.isLoggedIn() && !force) return true
        val creds = YouTubeAuth.storedCredentials(context) ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < 10 * 60_000L) return YouTubeAuth.isLoggedIn()
        lastAttemptAt = now

        val result = withContext(Dispatchers.Main) {
            // v1.7.10: any unexpected engine error (WebView init failure, etc.)
            // must degrade to a typed Failure — never propagate into the
            // playback path and never hang it (callers also cap this at 45 s).
            runCatching {
                GoogleLoginEngine.login(
                    context.applicationContext,
                    creds.first,
                    creds.second,
                    creds.third.ifBlank { null }
                )
            }.getOrElse {
                GoogleLoginEngine.LoginResult.Failure("engine_error", "${it.javaClass.simpleName}: ${it.message}")
            }
        }

        return when (result) {
            is GoogleLoginEngine.LoginResult.Success -> {
                YouTubeAuth.saveCookies(result.cookies, result.account)
                lastError = null
                runCatching { YouTubeAuth.visitorData() }
                true
            }

            is GoogleLoginEngine.LoginResult.Failure -> {
                lastError = "${result.kind}: ${result.message}"
                false
            }
        }
    }
}
