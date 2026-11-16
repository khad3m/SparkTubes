package com.sparktube.app.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sparktube.app.data.YtRepository
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.net.YouTubeAutoLogin
import com.sparktube.app.util.Themes
import kotlin.concurrent.thread

/**
 * Auto Google Sign-in screen (v1.7.9, :ytauth library).
 *
 * Enter the Google account email, password and (recommended) the 2FA base32
 * secret once. Credentials are stored encrypted with the Android Keystore.
 * From then on, age-restricted videos establish the signed-in session
 * automatically (headless WebView + TOTP) and play without manual steps.
 */
class AutoLoginActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AutoLoginActivity::class.java))
        }
    }

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var totpInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)

        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        }

        val root = ScrollView(this)
        root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(box)
        setContentView(root)

        val title = TextView(this).apply {
            text = "Sign in to Google — Automatic"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        box.addView(title)

        val subtitle = TextView(this).apply {
            text = "Credentials are stored encrypted on this device (Android Keystore) " +
                "and used only to create your YouTube session, so age-restricted (18+) " +
                "videos play automatically. Nothing is uploaded anywhere."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(16))
        }
        box.addView(subtitle)

        emailInput = EditText(this).apply {
            hint = "Google email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(YouTubeAuth.storedCredentials(this@AutoLoginActivity)?.first.orEmpty())
            setSingleLine(true)
        }
        box.addView(emailInput)

        passwordInput = EditText(this).apply {
            hint = "Google password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        box.addView(passwordInput)

        totpInput = EditText(this).apply {
            hint = "2FA secret (base32 — recommended, enables TOTP auto-fill)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        box.addView(totpInput)

        loginButton = Button(this).apply {
            text = "Save & sign in automatically"
            setOnClickListener { startAutoLogin() }
        }
        box.addView(loginButton)

        val manualButton = Button(this).apply {
            text = "Use manual browser login instead"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                YouTubeLoginActivity.start(this@AutoLoginActivity)
                finish()
            }
        }
        box.addView(manualButton)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
        }
        box.addView(statusText)

        val note = TextView(this).apply {
            text = "Tip: use a dedicated Google account for the app. If Google still asks " +
                "for phone approval, sign in once in the manual browser flow, or create " +
                "an app password / TOTP secret for this account."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        }
        box.addView(note)
    }

    private fun startAutoLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val totp = totpInput.text.toString().trim()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Email and password are required", Toast.LENGTH_SHORT).show()
            return
        }
        loginButton.isEnabled = false
        statusText.visibility = View.VISIBLE
        statusText.text = "Signing in automatically (headless browser + 2FA)…"

        // Store credentials encrypted first so future age-gate playback can
        // re-establish the session on its own.
        YouTubeAuth.storeCredentials(this, email, password, totp)

        thread {
            val ok = runCatching {
                kotlinx.coroutines.runBlocking {
                    YouTubeAutoLogin.ensureSignedIn(this@AutoLoginActivity, force = true)
                }
            }.getOrDefault(false)
            runOnUiThread {
                if (ok) {
                    YtRepository.clearCache()
                    Toast.makeText(this@AutoLoginActivity, "Signed in as $email", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    loginButton.isEnabled = true
                    statusText.text = YouTubeAutoLogin.lastErrorOrNull()
                        ?: "Automatic sign-in failed — check the credentials or use manual login."
                }
            }
        }
    }
}
