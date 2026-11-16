package com.sparktube.app.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityDiagnosticsBinding
import com.sparktube.app.util.DiagnosticsLogger
import com.sparktube.app.util.Themes

/**
 * v1.7.12: view / copy / share / clear the diagnostics log (browse, search,
 * playback, unlock ladder and auto-login events since app start).
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.apply(this)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.diag_title)
        DiagnosticsLogger.info("Diag", "diagnostics screen opened")

        binding.btnCopy.setOnClickListener {
            val text = DiagnosticsLogger.tailText()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SparkTube diagnostics", text))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val text = DiagnosticsLogger.tailText()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SparkTube diagnostics log")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        }
        binding.btnClear.setOnClickListener {
            DiagnosticsLogger.clear()
            Toast.makeText(this, R.string.diag_cleared, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        binding.logText.text = DiagnosticsLogger.tailText()
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
