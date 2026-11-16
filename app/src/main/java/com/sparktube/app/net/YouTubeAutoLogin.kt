package com.sparktube.app.net

import android.content.Context
import com.sparktube.ytauth.GoogleLoginEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Establishes a signed-in Google/YouTube session automatically from the
 * credentials saved via the Auto Sign-in screen (:ytauth engine).
 *
 * v1.7.11 fast path: [kickoff] starts the login in the background the moment
 * playback resolution begins, so an age-restricted video never waits for a
 * cold login start — the catch-path [ensureSignedIn] then simply joins the
 * in-flight attempt. Every attempt is bounded at 30 s and throttled to one
 * per 10 minutes.
 */
object YouTubeAutoLogin {

    @Volatile private var lastAttemptAt: Long = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var inFlight: Job? = null

    fun lastErrorOrNull(): String? = lastError

    /**
     * Non-blocking pre-play trigger: starts the automatic sign-in in the
     * background while the player resolves, so age-gated failures find the
     * session already being established. Safe to call on every playback.
     */
    fun kickoff(context: Context) {
        if (YouTubeAuth.isLoggedIn()) return
        if (YouTubeAuth.storedCredentials(context) == null) {
            com.sparktube.app.util.DiagnosticsLogger.log("I", "AutoLogin", "kickoff skipped: no saved credentials")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < 10 * 60_000L) return
        if (inFlight?.isActive == true) return
        lastAttemptAt = now
        com.sparktube.app.util.DiagnosticsLogger.log("I", "AutoLogin", "kickoff: starting pre-play background login")
        inFlight = GlobalScope.launch(Dispatchers.Main) {
            runCatching { performLogin(context.applicationContext) }
        }
    }

    suspend fun ensureSignedIn(context: Context, force: Boolean = false): Boolean {
        if (YouTubeAuth.isLoggedIn() && !force) return true
        if (!force) {
            // A pre-play kickoff may already be running — wait for it instead
            // of starting a second competing login.
            val running = inFlight
            if (running?.isActive == true) {
                running.join()
                if (YouTubeAuth.isLoggedIn()) return true
            }
        }
        val creds = YouTubeAuth.storedCredentials(context) ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < 10 * 60_000L) return YouTubeAuth.isLoggedIn()
        lastAttemptAt = now
        return performLogin(context.applicationContext)
    }

    private suspend fun performLogin(context: Context): Boolean {
        val creds = YouTubeAuth.storedCredentials(context) ?: return false
        val result = withContext(Dispatchers.Main) {
            com.sparktube.app.util.DiagnosticsLogger.log("I", "AutoLogin", "attempt email=${creds.first.take(1)}*** timeout=30s")
            // Any unexpected engine error degrades to a typed Failure; the
            // whole attempt is bounded at 30 s so the playback path can
            // never hang (watchdog in :ytauth is the backstop).
            runCatching {
                GoogleLoginEngine.login(
                    context,
                    creds.first,
                    creds.second,
                    creds.third.ifBlank { null },
                    timeoutMs = 30_000L
                )
            }.getOrElse {
                GoogleLoginEngine.LoginResult.Failure(
                    "engine_error",
                    "${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }

        return when (result) {
            is GoogleLoginEngine.LoginResult.Success -> {
                YouTubeAuth.saveCookies(result.cookies, result.account)
                lastError = null
                com.sparktube.app.util.DiagnosticsLogger.log(
                    "I", "AutoLogin", "success cookies=${result.cookies.size} account=${result.account.take(40)}"
                )
                runCatching { YouTubeAuth.visitorData() }
                true
            }

            is GoogleLoginEngine.LoginResult.Failure -> {
                lastError = "${result.kind}: ${result.message}"
                com.sparktube.app.util.DiagnosticsLogger.log(
                    "E", "AutoLogin", "failed kind=${result.kind} msg=${result.message.take(150)}"
                )
                false
            }
        }
    }
}
