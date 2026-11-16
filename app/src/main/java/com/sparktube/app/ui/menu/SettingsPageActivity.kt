package com.sparktube.app.ui.menu

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.ui.auth.AutoLoginActivity
import com.sparktube.app.ui.auth.YouTubeLoginActivity
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.databinding.ActivitySettingsPageBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.CrashReporter
import com.sparktube.app.util.Themes
import com.sparktube.app.util.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full settings page per area (general / video / music) with glassmorphic rows,
 * Local Storage & Custom Download Folder selector (System Folder Picker + Custom Path),
 * Settings-controlled In-App Update check (auto-check toggle + manual check),
 * Bangla Auto-Translate Subtitles default setting, SponsorBlock auto-skip toggle,
 * and Smooth Audio Crossfade selector.
 */
class SettingsPageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsPageBinding

    private val pageType: Int by lazy {
        intent.getIntExtra(EXTRA_TYPE, TYPE_GENERAL)
    }

    private val folderTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val resolvedPath = resolveTreeUriToFilePath(treeUri)
            AppPrefs.customDownloadTreeUri = treeUri.toString()
            AppPrefs.customDownloadPath = resolvedPath
            AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_CUSTOM
            Toast.makeText(
                this,
                "Download folder set to: $resolvedPath",
                Toast.LENGTH_SHORT
            ).show()
            render()
        }
    }

    private fun resolveTreeUriToFilePath(treeUri: Uri): String {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":")
            val type = split.getOrNull(0).orEmpty()
            val rel = split.getOrNull(1).orEmpty()
            if (type.equals("primary", ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (rel.isBlank()) base else "$base/$rel"
            } else {
                val extBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                if (rel.isNotBlank()) "/storage/$type/$rel" else "$extBase/SparkTube"
            }
        }.getOrElse {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube").absolutePath
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsPageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.text = when (pageType) {
            TYPE_VIDEO -> getString(R.string.menu_video_settings)
            TYPE_MUSIC -> getString(R.string.menu_music_settings)
            else -> getString(R.string.menu_settings)
        }
        binding.backButton.setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
        render()
    }

    private fun render() {
        val content = binding.content
        content.removeAllViews()
        when (pageType) {
            TYPE_VIDEO -> renderVideo(content)
            TYPE_MUSIC -> renderMusic(content)
            else -> renderGeneral(content)
        }
    }

    // ----- 2-Column Icon Grid Helpers (v1.7.3) -----

    private data class GridItem(
        val iconRes: Int,
        val title: String,
        val value: String,
        val onClick: () -> Unit
    )

    private fun menuGrid(items: List<GridItem>): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
        }
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { if (i > 0) it.topMargin = dp(10) }
            }
            val left = gridCard(items[i]).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = dp(5) }
            }
            row.addView(left)
            if (i + 1 < items.size) {
                val right = gridCard(items[i + 1]).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.marginStart = dp(5) }
                }
                row.addView(right)
            } else {
                row.addView(
                    android.widget.Space(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).also { it.marginStart = dp(5) }
                    }
                )
            }
            container.addView(row)
            i += 2
        }
        return container
    }

    private fun gridCard(item: GridItem): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_card_soft)
            isClickable = true
            isFocusable = true
            Motion.pressable(this, 0.96f)
            setOnClickListener { item.onClick() }

            val topRow = LinearLayout(this@SettingsPageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val badge = FrameLayout(this@SettingsPageActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                setBackgroundResource(R.drawable.bg_glass_circle)
                addView(
                    android.widget.ImageView(this@SettingsPageActivity).apply {
                        setImageResource(item.iconRes)
                        setColorFilter(Themes.accentColor(this@SettingsPageActivity))
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
                    }
                )
            }
            topRow.addView(badge)
            topRow.addView(android.widget.Space(this@SettingsPageActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            if (item.value.isNotBlank()) {
                val isOn = item.value.equals(getString(R.string.on), ignoreCase = true)
                topRow.addView(
                    TextView(this@SettingsPageActivity).apply {
                        text = item.value
                        textSize = 10.5f
                        maxLines = 1
                        maxWidth = dp(86)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(
                            if (isOn) Themes.accentColor(this@SettingsPageActivity)
                            else getColor(R.color.on_surface_variant)
                        )
                        setBackgroundResource(R.drawable.bg_glass_pill)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                )
            }
            addView(topRow)

            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = item.title
                    textSize = 14.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(14) }
                }
            )
        }

    // ----- General -----

    private fun renderGeneral(content: LinearLayout) {

        // Premium chip grid: Theme (v1.7.1)
        content.addView(sectionLabel(getString(R.string.settings_theme)))
        content.addView(
            chipGrid(
                listOf(
                    AppPrefs.THEME_AUTO to getString(R.string.theme_auto),
                    AppPrefs.THEME_DARK to getString(R.string.theme_dark),
                    AppPrefs.THEME_LIGHT to getString(R.string.theme_light),
                    AppPrefs.THEME_PITCH_BLACK to getString(R.string.theme_pitch_black)
                ).map { (value, label) ->
                    Triple(label, AppPrefs.theme == value) {
                        Themes.setTheme(value, this)
                        render()
                    }
                }
            )
        )
        // Premium chip grid: Accent colour (v1.7.1)
        content.addView(sectionLabel(getString(R.string.settings_accent)))
        content.addView(
            chipGrid(
                listOf(
                    "red" to getString(R.string.accent_red),
                    "blue" to getString(R.string.accent_blue),
                    "purple" to getString(R.string.accent_purple),
                    "green" to getString(R.string.accent_green),
                    "orange" to getString(R.string.accent_orange),
                    "pink" to getString(R.string.accent_pink),
                    "teal" to getString(R.string.accent_teal)
                ).map { (value, label) ->
                    Triple(label, AppPrefs.accent == value) {
                        Themes.setAccent(value, this)
                        render()
                    }
                },
                perRow = 4
            )
        )
        content.addView(
            menuGrid(
                listOf(
                    GridItem(R.drawable.ic_info, getString(R.string.youtube_account_title), YouTubeAuth.accountSummary()) {
                        showYouTubeAccountDialog()
                    },
                    GridItem(
                        R.drawable.ic_palette,
                        getString(R.string.settings_animations),
                        if (AppPrefs.animations) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.animations = !AppPrefs.animations
                        render()
                    },
                    GridItem(
                        R.drawable.ic_shorts,
                        getString(R.string.settings_shorts),
                        if (AppPrefs.shortsEnabled) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.shortsEnabled = !AppPrefs.shortsEnabled
                        render()
                    },
                    GridItem(
                        R.drawable.ic_download,
                        getString(R.string.settings_download_folder),
                        AppPrefs.downloadFolderSummary().substringAfterLast("/storage/emulated/0/")
                    ) {
                        showDownloadFolderPicker()
                    },
                    GridItem(
                        R.drawable.ic_update,
                        "Auto Updates",
                        if (AppPrefs.autoCheckUpdates) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.autoCheckUpdates = !AppPrefs.autoCheckUpdates
                        render()
                    },
                    GridItem(R.drawable.ic_update, getString(R.string.menu_check_update), "v${BuildConfig.VERSION_NAME}") {
                        checkForUpdatesNow()
                    },
                    GridItem(R.drawable.ic_crash_test, getString(R.string.settings_crash_logs), "${crashLogCountLabel()} logs") {
                        showCrashLogsSheet()
                    },
                    GridItem(R.drawable.ic_info, getString(R.string.diag_title), getString(R.string.diag_grid_sub)) {
                        DiagnosticsActivity.start(this@SettingsPageActivity)
                    },
                    GridItem(R.drawable.ic_delete, getString(R.string.menu_clear_data), "Cache · History") {
                        showClearDataSheet()
                    }
                )
            )
        )
    }

    private fun showYouTubeAccountDialog() {
        // v1.8.0: interactive browser login is now the recommended path — the
        // headless engine cannot complete on many devices (diagnostics proven),
        // and the cloud unlock tier handles 18+ videos server-side.
        val options = arrayOf(
            "Browser login (recommended)",
            "Cloud unlock — Worker URL (18+)",
            "Auto login (headless)",
            getString(R.string.youtube_account_sign_out)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.youtube_account_connected)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> YouTubeLoginActivity.start(this)
                    1 -> showCloudUnlockDialog()
                    2 -> AutoLoginActivity.start(this)
                    else -> {
                        YouTubeAuth.clearSession()
                        YtRepository.clearCache()
                        render()
                        Toast.makeText(this, R.string.youtube_signed_out_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v1.8.0: paste the URL of the user-deployed SparkTube Cloudflare worker. */
    private fun showCloudUnlockDialog() {
        val input = EditText(this).apply {
            setText(AppPrefs.workerUrl)
            setSelection(text.length)
            setSingleLine()
            hint = "https://sparktube-unlock.yourname.workers.dev"
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Cloud unlock — Worker URL")
            .setMessage(
                "Ships pre-configured with the SparkTube project worker, which holds its own " +
                    "YouTube session — 18+ videos resolve through it automatically. " +
                    "Paste your own deployed worker URL to replace it, or leave empty to restore the default."
            )
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty().removeSuffix("/")
                AppPrefs.workerUrl = entered
                render()
                Toast.makeText(
                    this,
                    if (entered.isBlank()) "Cloud unlock: default worker restored" else "Cloud unlock: $entered",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloadFolderPicker() {
        val options = arrayOf(
            getString(R.string.download_folder_public),
            getString(R.string.download_folder_media),
            "Browse & pick custom folder (System Picker)…",
            "Type custom folder path…",
            getString(R.string.download_folder_private)
        )
        val currentIdx = when (AppPrefs.downloadFolderMode) {
            AppPrefs.FOLDER_MODE_PUBLIC_MEDIA -> 1
            AppPrefs.FOLDER_MODE_CUSTOM -> 2
            AppPrefs.FOLDER_MODE_APP_PRIVATE -> 4
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_download_folder)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_PUBLIC_DOWNLOADS
                        render()
                    }
                    1 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_PUBLIC_MEDIA
                        render()
                    }
                    2 -> {
                        runCatching {
                            folderTreeLauncher.launch(null)
                        }.onFailure {
                            showCustomPathDialog()
                        }
                    }
                    3 -> {
                        showCustomPathDialog()
                    }
                    4 -> {
                        AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_APP_PRIVATE
                        render()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomPathDialog() {
        val defaultPath = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube").absolutePath
        }.getOrDefault("/storage/emulated/0/Download/SparkTube")
        val input = EditText(this).apply {
            setText(AppPrefs.customDownloadPath.ifBlank { defaultPath })
            setSelection(text.length)
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_folder_custom)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (entered.isNotBlank()) {
                    val dir = File(entered)
                    runCatching { dir.mkdirs() }
                    AppPrefs.customDownloadPath = dir.absolutePath
                    AppPrefs.downloadFolderMode = AppPrefs.FOLDER_MODE_CUSTOM
                    Toast.makeText(this, "Download folder: ${dir.absolutePath}", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkForUpdatesNow() {
        Toast.makeText(this, "আপডেট চেক করা হচ্ছে...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest()
            if (isFinishing || isDestroyed) return@launch
            if (latest == null) {
                Toast.makeText(this@SettingsPageActivity, "আপডেট চেক করা যায়নি। ইন্টারনেট সংযোগ দেখুন।", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (UpdateChecker.isNewer(BuildConfig.VERSION_NAME, latest.tag)) {
                com.sparktube.app.ui.common.UpdateDownloadUi.showAvailableSheet(
                    this@SettingsPageActivity, latest, lifecycleScope
                )
            } else {
                Toast.makeText(this@SettingsPageActivity, "আপনি সর্বশেষ ভার্সন (v${BuildConfig.VERSION_NAME}) ব্যবহার করছেন ✓", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----- Crash logs -----

    private fun crashLogCountLabel(): String =
        CrashReporter.listLogs(this).size.toString()

    private fun showCrashLogsSheet() {
        val logs = CrashReporter.listLogs(this)
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.settings_crash_logs)
                textSize = 18f
                setTextColor(getColor(R.color.on_surface))
                setTypeface(null, Typeface.BOLD)
            }
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.crash_logs_location_hint)
                textSize = 12f
                setTextColor(getColor(R.color.on_surface_variant))
                setPadding(0, dp(4), 0, dp(2))
            }
        )

        if (logs.isEmpty()) {
            root.addView(
                TextView(this).apply {
                    text = getString(R.string.crash_logs_empty)
                    textSize = 14f
                    setTextColor(getColor(R.color.on_surface_variant))
                    setPadding(dp(4), dp(14), dp(4), dp(6))
                }
            )
        } else {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(12) }
            }
            actions.addView(
                crashSheetAction(getString(R.string.crash_logs_share_all)) {
                    shareAllCrashLogs(logs)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.marginEnd = dp(5) }
                }
            )
            actions.addView(
                crashSheetAction(getString(R.string.crash_logs_delete_all)) {
                    confirmDeleteCrashLogs(logs.size, sheet)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.marginStart = dp(5) }
                }
            )
            root.addView(actions)

            logs.forEach { log ->
                root.addView(
                    crashLogRow(log) { shareCrashLog(log) }
                )
            }
        }

        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(420)
        sheet.show()
    }

    private fun crashLogRow(
        log: CrashReporter.CrashLogFile,
        onClick: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_card_soft)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }
            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = log.displayName
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                }
            )
            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = crashRowSubtitle(log)
                    textSize = 12f
                    setTextColor(getColor(R.color.on_surface_variant))
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }

    private fun crashSheetAction(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextColor(getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_card_soft)
            setOnClickListener { onClick() }
        }

    private fun crashRowSubtitle(log: CrashReporter.CrashLogFile): String {
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(log.timeMs))
        return "${formatLogSize(log.sizeBytes)} • $date"
    }

    private fun formatLogSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun shareCrashLog(log: CrashReporter.CrashLogFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, log.uri)
            putExtra(Intent.EXTRA_SUBJECT, log.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.crash_log_share)))
    }

    private fun shareAllCrashLogs(logs: List<CrashReporter.CrashLogFile>) {
        val uris = ArrayList<Uri>(logs.map { it.uri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.crash_logs_share_all)))
    }

    private fun confirmDeleteCrashLogs(count: Int, sheet: BottomSheetDialog) {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_logs_delete_all)
            .setMessage(R.string.crash_logs_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                CrashReporter.deleteAllLogs(this)
                Toast.makeText(
                    this,
                    getString(R.string.crash_logs_deleted, count),
                    Toast.LENGTH_SHORT
                ).show()
                sheet.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Clear data -----

    private fun showClearDataSheet() {
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.menu_clear_data)
                textSize = 18f
                setTextColor(getColor(R.color.on_surface))
                setTypeface(null, Typeface.BOLD)
            }
        )

        fun clearRow(label: Int, title: Int, message: Int, done: Int, action: (Context) -> Unit) {
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setBackgroundResource(R.drawable.bg_card_soft)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(10) }
                    setOnClickListener {
                        confirm(title, message, done, action)
                        sheet.dismiss()
                    }
                    addView(
                        TextView(this@SettingsPageActivity).apply {
                            text = getString(label)
                            textSize = 15f
                            setTextColor(getColor(R.color.on_surface))
                            setTypeface(null, Typeface.BOLD)
                        }
                    )
                }
            )
        }

        clearRow(
            R.string.clear_history, R.string.clear_history, R.string.clear_history_confirm,
            R.string.history_cleared
        ) { LocalStore.clearHistory(it); RecommendEngine.clearPlays(it) }
        clearRow(
            R.string.clear_music_history, R.string.clear_music_history,
            R.string.clear_music_history_confirm, R.string.music_history_cleared
        ) { LocalStore.clearMusicHistory(it) }
        clearRow(
            R.string.clear_search_history, R.string.clear_search_history,
            R.string.clear_search_history_confirm, R.string.search_history_cleared
        ) { RecommendEngine.clearSearches(it) }
        clearRow(
            R.string.clear_favorites, R.string.clear_favorites, R.string.clear_favorites_confirm,
            R.string.favorites_cleared
        ) { LocalStore.clearFavorites(it) }
        clearRow(
            R.string.clear_subscriptions, R.string.clear_subscriptions,
            R.string.clear_subscriptions_confirm, R.string.subscriptions_cleared
        ) { LocalStore.clearSubscriptions(it) }
        clearRow(
            R.string.clear_downloads, R.string.clear_downloads, R.string.clear_downloads_confirm,
            R.string.downloads_cleared
        ) { DownloadCenter.clearAll(it) }
        clearRow(
            R.string.clear_recommendations, R.string.clear_recommendations,
            R.string.clear_recommendations_confirm, R.string.recommendations_cleared
        ) {
            RecommendEngine.clearPlays(it)
            RecommendEngine.clearSearches(it)
        }

        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(420)
        sheet.show()
    }

    private fun confirm(
        titleRes: Int,
        messageRes: Int,
        doneRes: Int,
        action: (Context) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                action(this)
                Toast.makeText(this, doneRes, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Video -----

    private fun renderVideo(content: LinearLayout) {
        // Premium chip grid: default quality per network (v1.7.1)
        val qualityChoices = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        fun qualityChips(currentHeight: Int, apply: (Int) -> Unit) =
            chipGrid(
                qualityChoices.map { height ->
                    Triple(
                        if (height == 0) getString(R.string.quality_auto) else "${height}p",
                        currentHeight == height
                    ) {
                        apply(height)
                        render()
                    }
                },
                perRow = 4
            )
        content.addView(sectionLabel(getString(R.string.settings_default_quality_wifi)))
        content.addView(qualityChips(AppPrefs.defaultVideoHeightWifi) { AppPrefs.defaultVideoHeightWifi = it })
        content.addView(sectionLabel(getString(R.string.settings_default_quality_data)))
        content.addView(qualityChips(AppPrefs.defaultVideoHeightData) { AppPrefs.defaultVideoHeightData = it })

        // Premium chip grid: subtitles (v1.7.1)
        content.addView(sectionLabel(getString(R.string.subtitles_section)))
        content.addView(
            chipGrid(
                listOf(
                    AppPrefs.SUBTITLE_MODE_BANGLA to getString(R.string.subtitles_bangla_auto),
                    AppPrefs.SUBTITLE_MODE_ORIGINAL to getString(R.string.subtitles_original),
                    AppPrefs.SUBTITLE_MODE_OFF to getString(R.string.subtitles_off)
                ).map { (value, label) ->
                    Triple(label, AppPrefs.subtitleMode == value) {
                        AppPrefs.subtitleMode = value
                        render()
                    }
                },
                perRow = 3
            )
        )
        content.addView(
            menuGrid(
                listOf(
                    GridItem(R.drawable.ic_info, getString(R.string.youtube_account_title), YouTubeAuth.accountSummary()) {
                        showYouTubeAccountDialog()
                    },
                    GridItem(
                        R.drawable.ic_download,
                        getString(R.string.settings_download_folder),
                        AppPrefs.downloadFolderSummary().substringAfterLast("/storage/emulated/0/")
                    ) {
                        showDownloadFolderPicker()
                    },
                    GridItem(
                        R.drawable.ic_tune,
                        getString(R.string.settings_smart_streaming),
                        if (AppPrefs.smartStreaming) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.smartStreaming = !AppPrefs.smartStreaming
                        render()
                    },
                    GridItem(
                        R.drawable.ic_video_cam,
                        getString(R.string.settings_hw_acceleration),
                        if (AppPrefs.hardwareAcceleration) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.hardwareAcceleration = !AppPrefs.hardwareAcceleration
                        render()
                    },
                    GridItem(
                        R.drawable.ic_play_arrow,
                        getString(R.string.settings_autoplay_next_video),
                        if (AppPrefs.videoAutoplayNext) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.videoAutoplayNext = !AppPrefs.videoAutoplayNext
                        render()
                    },
                    GridItem(
                        R.drawable.ic_player_replay,
                        "SponsorBlock",
                        if (AppPrefs.sponsorBlock) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.sponsorBlock = !AppPrefs.sponsorBlock
                        render()
                    }
                )
            )
        )
    }

    // ----- Music -----

    private fun renderMusic(content: LinearLayout) {
        // Premium chip grid: music audio quality (v1.7.1)
        content.addView(sectionLabel(getString(R.string.settings_audio_quality)))
        content.addView(
            chipGrid(
                listOf(
                    AppPrefs.AUDIO_QUALITY_HIGH to getString(R.string.quality_high),
                    AppPrefs.AUDIO_QUALITY_MEDIUM to getString(R.string.quality_medium),
                    AppPrefs.AUDIO_QUALITY_LOW to getString(R.string.quality_low)
                ).map { (value, label) ->
                    Triple(label, AppPrefs.musicAudioQuality == value) {
                        AppPrefs.musicAudioQuality = value
                        render()
                    }
                },
                perRow = 3
            )
        )

        // Premium chip grid: crossfade (v1.7.1)
        content.addView(sectionLabel(getString(R.string.settings_crossfade)))
        content.addView(
            chipGrid(
                listOf(0, 2, 4, 6, 8).map { sec ->
                    Triple(
                        if (sec == 0) getString(R.string.crossfade_off) else getString(R.string.crossfade_fmt, sec),
                        AppPrefs.crossfadeSeconds == sec
                    ) {
                        AppPrefs.crossfadeSeconds = sec
                        render()
                    }
                },
                perRow = 5
            )
        )
        content.addView(
            menuGrid(
                listOf(
                    GridItem(R.drawable.ic_info, getString(R.string.youtube_account_title), YouTubeAuth.accountSummary()) {
                        showYouTubeAccountDialog()
                    },
                    GridItem(
                        R.drawable.ic_download,
                        getString(R.string.settings_download_folder),
                        AppPrefs.downloadFolderSummary().substringAfterLast("/storage/emulated/0/")
                    ) {
                        showDownloadFolderPicker()
                    },
                    GridItem(
                        R.drawable.ic_headset,
                        getString(R.string.settings_autoplay_next_song),
                        if (AppPrefs.musicAutoplayNext) getString(R.string.on) else getString(R.string.off)
                    ) {
                        AppPrefs.musicAutoplayNext = !AppPrefs.musicAutoplayNext
                        PlaybackCenter.applyMusicAutoplay()
                        render()
                    }
                )
            )
        )
    }

    // ----- Pickers -----

    private fun subtitleModeLabel(): String = when (AppPrefs.subtitleMode) {
        AppPrefs.SUBTITLE_MODE_BANGLA -> getString(R.string.subtitles_bangla_auto)
        AppPrefs.SUBTITLE_MODE_ORIGINAL -> getString(R.string.subtitles_original)
        else -> getString(R.string.subtitles_off)
    }

    private fun showSubtitleModePicker() {
        val options = listOf(
            AppPrefs.SUBTITLE_MODE_BANGLA to getString(R.string.subtitles_bangla_auto),
            AppPrefs.SUBTITLE_MODE_ORIGINAL to getString(R.string.subtitles_original),
            AppPrefs.SUBTITLE_MODE_OFF to getString(R.string.subtitles_off)
        )
        val current = options.indexOfFirst { it.first == AppPrefs.subtitleMode }
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitles_section)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), if (current >= 0) current else 0) { dialog, which ->
                AppPrefs.subtitleMode = options[which].first
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun crossfadeLabel(): String {
        val sec = AppPrefs.crossfadeSeconds
        return if (sec <= 0) getString(R.string.crossfade_off) else getString(R.string.crossfade_fmt, sec)
    }

    private fun showCrossfadePicker() {
        val options = listOf(0, 2, 4, 6, 8)
        val labels = options.map {
            if (it == 0) getString(R.string.crossfade_off) else getString(R.string.crossfade_fmt, it)
        }.toTypedArray()
        val current = options.indexOfFirst { it == AppPrefs.crossfadeSeconds }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_crossfade)
            .setSingleChoiceItems(labels, if (current >= 0) current else 2) { dialog, which ->
                AppPrefs.crossfadeSeconds = options[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeLabel(): String = when (AppPrefs.theme) {
        AppPrefs.THEME_LIGHT -> getString(R.string.theme_light)
        AppPrefs.THEME_PITCH_BLACK -> getString(R.string.theme_pitch_black)
        AppPrefs.THEME_AUTO -> getString(R.string.theme_auto)
        else -> getString(R.string.theme_dark)
    }

    private fun showThemePicker() {
        val options = listOf(
            AppPrefs.THEME_AUTO to getString(R.string.theme_auto),
            AppPrefs.THEME_DARK to getString(R.string.theme_dark),
            AppPrefs.THEME_LIGHT to getString(R.string.theme_light),
            AppPrefs.THEME_PITCH_BLACK to getString(R.string.theme_pitch_black)
        )
        val current = options.indexOfFirst { it.first == AppPrefs.theme }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setTheme(options[which].first, this)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun accentLabel(): String = when (AppPrefs.accent) {
        "blue" -> getString(R.string.accent_blue)
        "purple" -> getString(R.string.accent_purple)
        "green" -> getString(R.string.accent_green)
        "orange" -> getString(R.string.accent_orange)
        "pink" -> getString(R.string.accent_pink)
        "teal" -> getString(R.string.accent_teal)
        else -> getString(R.string.accent_red)
    }

    private fun showAccentPicker() {
        val accents = listOf(
            "red" to getString(R.string.accent_red),
            "blue" to getString(R.string.accent_blue),
            "purple" to getString(R.string.accent_purple),
            "green" to getString(R.string.accent_green),
            "orange" to getString(R.string.accent_orange),
            "pink" to getString(R.string.accent_pink),
            "teal" to getString(R.string.accent_teal)
        )
        val current = accents.indexOfFirst { it.first == AppPrefs.accent }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_accent)
            .setSingleChoiceItems(accents.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setAccent(accents[which].first, this)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun qualityValueLabel(height: Int): String =
        if (height == 0) {
            getString(R.string.quality_auto_short)
        } else {
            "${height}p"
        }

    private fun wifiQualityLabel(): String = qualityValueLabel(AppPrefs.defaultVideoHeightWifi)

    private fun dataQualityLabel(): String = qualityValueLabel(AppPrefs.defaultVideoHeightData)

    private fun showDefaultQualityPicker(forWifi: Boolean) {
        val choices = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        val labels = choices.map {
            if (it == 0) getString(R.string.quality_auto) else "${it}p"
        }.toTypedArray()
        val currentHeight = if (forWifi) AppPrefs.defaultVideoHeightWifi else AppPrefs.defaultVideoHeightData
        val current = choices.indexOfFirst { it == currentHeight }
        AlertDialog.Builder(this)
            .setTitle(
                if (forWifi) R.string.settings_default_quality_wifi
                else R.string.settings_default_quality_data
            )
            .setSingleChoiceItems(labels, if (current >= 0) current else 0) { dialog, which ->
                if (forWifi) {
                    AppPrefs.defaultVideoHeightWifi = choices[which]
                } else {
                    AppPrefs.defaultVideoHeightData = choices[which]
                }
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun audioQualityLabel(): String = when (AppPrefs.musicAudioQuality) {
        AppPrefs.AUDIO_QUALITY_MEDIUM -> getString(R.string.quality_medium)
        AppPrefs.AUDIO_QUALITY_LOW -> getString(R.string.quality_low)
        else -> getString(R.string.quality_high)
    }

    private fun showAudioQualityPicker() {
        val options = listOf(
            AppPrefs.AUDIO_QUALITY_HIGH to getString(R.string.quality_high),
            AppPrefs.AUDIO_QUALITY_MEDIUM to getString(R.string.quality_medium),
            AppPrefs.AUDIO_QUALITY_LOW to getString(R.string.quality_low)
        )
        val current = options.indexOfFirst { it.first == AppPrefs.musicAudioQuality }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_audio_quality)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                AppPrefs.musicAudioQuality = options[which].first
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Premium chip-grid builders (v1.7.1) -----

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(Themes.accentColor(this@SettingsPageActivity))
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(2), dp(12), 0, dp(4))
    }

    /** One row of premium pill chips (active = accent-filled). */
    private fun chipRow(chips: List<Triple<String, Boolean, () -> Unit>>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }
        chips.forEach { (label, active, action) ->
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    minHeight = dp(42)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setTextColor(if (active) getColor(R.color.white) else getColor(R.color.on_surface))
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(21).toFloat()
                        if (active) {
                            setColor(Themes.accentColor(this@SettingsPageActivity))
                        } else {
                            setColor(Themes.elevatedColor(this@SettingsPageActivity))
                            setStroke(1, getColor(R.color.glass_stroke))
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.marginEnd = dp(6) }
                    isClickable = true
                    isFocusable = true
                    Motion.pressable(this, 0.94f)
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        action()
                    }
                }
            )
        }
        return row
    }

    /** Wraps chips into rows of [perRow]. */
    private fun chipGrid(
        chips: List<Triple<String, Boolean, () -> Unit>>,
        perRow: Int = 3
    ): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < chips.size) {
            column.addView(chipRow(chips.subList(i, minOf(i + perRow, chips.size))))
            i += perRow
        }
        return column
    }

    // ----- Glass Row builder -----

    private fun menuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_card_soft)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val TYPE_GENERAL = 0
        const val TYPE_VIDEO = 1
        const val TYPE_MUSIC = 2
        private const val EXTRA_TYPE = "extra_type"

        fun start(context: Context, type: Int) {
            val intent = Intent(context, SettingsPageActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
            }
            context.startActivity(intent)
        }
    }
}
