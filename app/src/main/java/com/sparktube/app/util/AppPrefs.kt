package com.sparktube.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

object AppPrefs {

    private const val PREFS_NAME = "sparktube_prefs"
    private const val KEY_COUNTRY = "country"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_VIDEO_AUTOPLAY_NEXT = "video_autoplay_next"
    private const val KEY_EQ_PRESET = "eq_preset_index"
    private const val KEY_SMART_STREAMING = "smart_streaming"
    private const val KEY_MUSIC_AUDIO_QUALITY = "music_audio_quality"
    private const val KEY_MUSIC_AUTOPLAY_NEXT = "music_autoplay_next"
    private const val KEY_MUSIC_REPEAT_MODE = "music_repeat_mode"
    private const val KEY_MUSIC_SHUFFLE = "music_shuffle"
    private const val KEY_ANIMATIONS = "animations"
    private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
    private const val KEY_SPONSOR_BLOCK = "sponsor_block"
    private const val KEY_SUBTITLE_MODE = "subtitle_mode"
    private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"

    // Download folder configuration
    private const val KEY_DOWNLOAD_FOLDER_MODE = "download_folder_mode"
    private const val KEY_CUSTOM_DOWNLOAD_PATH = "custom_download_path"
    private const val KEY_CUSTOM_DOWNLOAD_TREE_URI = "custom_download_tree_uri"

    // Google / YouTube session authentication cookies & account metadata
            private const val KEY_YOUTUBE_VISITOR_DATA = "youtube_visitor_data"
    private const val KEY_YOUTUBE_DATASYNC_ID = "youtube_datasync_id"
    private const val KEY_YOUTUBE_SESSION_INDEX = "youtube_session_index"
    private const val KEY_YOUTUBE_STS = "youtube_sts"

    // Default video quality: separate values for Wi-Fi and mobile data.
    private const val KEY_DEFAULT_VIDEO_HEIGHT_WIFI = "default_video_height_wifi"
    private const val KEY_DEFAULT_VIDEO_HEIGHT_DATA = "default_video_height_data"
    // Legacy single-quality key (pre 1.1.0) used for the one-time migration.
    private const val KEY_DEFAULT_VIDEO_HEIGHT_LEGACY = "default_video_height"

    // Fullscreen video scale mode: fit / crop / stretch.
    private const val KEY_FULLSCREEN_SCALE = "fullscreen_scale"

    // Hardware acceleration: on by default (hardware decoders); user can switch to software decoding.
    private const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration"

    // Shorts / reels: feed + search tab + navbar shortcut (on by default).
    private const val KEY_SHORTS_FILL = "shorts_fill_mode"
    private const val KEY_SHORTS_ENABLED = "shorts_enabled"

    const val DEFAULT_COUNTRY = "US"

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_PITCH_BLACK = "pitch_black"

    const val AUDIO_QUALITY_HIGH = "high"
    const val AUDIO_QUALITY_MEDIUM = "medium"
    const val AUDIO_QUALITY_LOW = "low"

    const val SCALE_FIT = "fit"
    const val SCALE_CROP = "crop"
    const val SCALE_STRETCH = "stretch"

    const val SUBTITLE_MODE_BANGLA = "bn"
    const val SUBTITLE_MODE_ORIGINAL = "orig"
    const val SUBTITLE_MODE_OFF = "off"

    /** Public `/storage/emulated/0/Download/SparkTube` folder (default, visible in all file managers). */
    const val FOLDER_MODE_PUBLIC_DOWNLOADS = "public_downloads"
    /** Public `/storage/emulated/0/Music/SparkTube` & `/storage/emulated/0/Movies/SparkTube`. */
    const val FOLDER_MODE_PUBLIC_MEDIA = "public_media"
    /** User-selected custom directory on device storage. */
    const val FOLDER_MODE_CUSTOM = "custom"
    /** App-private `/Android/data/com.sparktube.app/files/.../SparkTube`. */
    const val FOLDER_MODE_APP_PRIVATE = "app_private"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyQuality()
    }

    private fun migrateLegacyQuality() {
        if (!sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY)) return
        if (!sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_WIFI) && !sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_DATA)) {
            val legacy = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY, 720)
            sp.edit()
                .putInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, legacy.coerceAtLeast(720))
                .putInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, legacy.coerceAtMost(480))
                .apply()
        }
        sp.edit().remove(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY).apply()
    }

    var country: String?
        get() = sp.getString(KEY_COUNTRY, null)
        set(value) {
            sp.edit().putString(KEY_COUNTRY, value).apply()
        }

    val countryOrDefault: String
        get() = country ?: DEFAULT_COUNTRY

    var theme: String
        get() = sp.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) {
            sp.edit().putString(KEY_THEME, value).apply()
        }

    var accent: String
        get() = sp.getString(KEY_ACCENT, "red") ?: "red"
        set(value) {
            sp.edit().putString(KEY_ACCENT, value).apply()
        }

    var defaultVideoHeightWifi: Int
        get() = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, 720)
        set(value) {
            sp.edit().putInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, value).apply()
        }

    var defaultVideoHeightData: Int
        get() = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, 480)
        set(value) {
            sp.edit().putInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, value).apply()
        }

    fun defaultVideoHeightNow(context: Context): Int =
        if (Net.isOnWifi(context)) defaultVideoHeightWifi else defaultVideoHeightData

    var smartStreaming: Boolean
        get() = sp.getBoolean(KEY_SMART_STREAMING, true)
        set(value) {
            sp.edit().putBoolean(KEY_SMART_STREAMING, value).apply()
        }

    /** Selected equalizer preset (0 = Flat). Persisted so it survives restarts and new tracks. */
    var eqPresetIndex: Int
        get() = sp.getInt(KEY_EQ_PRESET, 0)
        set(value) {
            sp.edit().putInt(KEY_EQ_PRESET, value).apply()
        }

    var videoAutoplayNext: Boolean
        get() = sp.getBoolean(KEY_VIDEO_AUTOPLAY_NEXT, true)
        set(value) {
            sp.edit().putBoolean(KEY_VIDEO_AUTOPLAY_NEXT, value).apply()
        }

    var sponsorBlock: Boolean
        get() = sp.getBoolean(KEY_SPONSOR_BLOCK, true)
        set(value) {
            sp.edit().putBoolean(KEY_SPONSOR_BLOCK, value).apply()
        }

    /** Subtitle mode: "bn" (Bangla Auto-Translate), "orig" (Original CC), or "off". */
    var subtitleMode: String
        get() = sp.getString(KEY_SUBTITLE_MODE, SUBTITLE_MODE_OFF) ?: SUBTITLE_MODE_OFF
        set(value) {
            sp.edit().putString(KEY_SUBTITLE_MODE, value).apply()
        }

    /** Audio crossfade duration in seconds (0 = Off, 2, 4, 6, 8). Default is 4s. */
    var crossfadeSeconds: Int
        get() = sp.getInt(KEY_CROSSFADE_SECONDS, 4).coerceIn(0, 12)
        set(value) {
            sp.edit().putInt(KEY_CROSSFADE_SECONDS, value.coerceIn(0, 12)).apply()
        }

    /** Download storage mode: public_downloads (default), public_media, custom, or app_private. */
    var downloadFolderMode: String
        get() = sp.getString(KEY_DOWNLOAD_FOLDER_MODE, FOLDER_MODE_PUBLIC_DOWNLOADS) ?: FOLDER_MODE_PUBLIC_DOWNLOADS
        set(value) {
            sp.edit().putString(KEY_DOWNLOAD_FOLDER_MODE, value).apply()
        }

    /** Resolved filesystem directory path when [downloadFolderMode] == [FOLDER_MODE_CUSTOM]. */
    var customDownloadPath: String
        get() = sp.getString(KEY_CUSTOM_DOWNLOAD_PATH, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CUSTOM_DOWNLOAD_PATH, value).apply()
        }

    /** Persistable SAF tree URI string when selected via system folder picker. */
    var customDownloadTreeUri: String
        get() = sp.getString(KEY_CUSTOM_DOWNLOAD_TREE_URI, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CUSTOM_DOWNLOAD_TREE_URI, value).apply()
        }

    /** Persisted InnerTube visitorData token (`X-Goog-Visitor-Id`). */
    var youtubeVisitorData: String
        get() = if (this::sp.isInitialized) sp.getString(KEY_YOUTUBE_VISITOR_DATA, "") ?: "" else ""
        set(value) {
            if (this::sp.isInitialized) sp.edit().putString(KEY_YOUTUBE_VISITOR_DATA, value).apply()
        }

    /** Persisted InnerTube `DATASYNC_ID` (`delegated_session_id||user_session_id` or `user_session_id||`). */
    var youtubeDatasyncId: String
        get() = if (this::sp.isInitialized) sp.getString(KEY_YOUTUBE_DATASYNC_ID, "") ?: "" else ""
        set(value) {
            if (this::sp.isInitialized) sp.edit().putString(KEY_YOUTUBE_DATASYNC_ID, value).apply()
        }

    /** Persisted InnerTube `SESSION_INDEX` (`X-Goog-AuthUser`). */
    var youtubeSessionIndex: String
        get() = if (this::sp.isInitialized) sp.getString(KEY_YOUTUBE_SESSION_INDEX, "") ?: "" else ""
        set(value) {
            if (this::sp.isInitialized) sp.edit().putString(KEY_YOUTUBE_SESSION_INDEX, value).apply()
        }

    /** Persisted YouTube player signatureTimestamp (`sts`). */
    var youtubeSts: Int
        get() = if (this::sp.isInitialized) sp.getInt(KEY_YOUTUBE_STS, 0) else 0
        set(value) {
            if (this::sp.isInitialized) sp.edit().putInt(KEY_YOUTUBE_STS, value).apply()
        }

    /** Human-readable summary of the active download directory. */
    fun downloadFolderSummary(): String = when (downloadFolderMode) {
        FOLDER_MODE_CUSTOM -> customDownloadPath.ifBlank { "Custom Folder" }
        FOLDER_MODE_PUBLIC_MEDIA -> "/storage/emulated/0/{Music,Movies}/SparkTube"
        FOLDER_MODE_APP_PRIVATE -> "App Private (Android/data/.../SparkTube)"
        else -> {
            val pub = runCatching {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SparkTube").absolutePath
            }.getOrDefault("/storage/emulated/0/Download/SparkTube")
            pub
        }
    }

    var musicAudioQuality: String
        get() = sp.getString(KEY_MUSIC_AUDIO_QUALITY, AUDIO_QUALITY_HIGH) ?: AUDIO_QUALITY_HIGH
        set(value) {
            sp.edit().putString(KEY_MUSIC_AUDIO_QUALITY, value).apply()
        }

    var musicAutoplayNext: Boolean
        get() = sp.getBoolean(KEY_MUSIC_AUTOPLAY_NEXT, true)
        set(value) {
            sp.edit().putBoolean(KEY_MUSIC_AUTOPLAY_NEXT, value).apply()
        }

    /** Music repeat mode: 0 = off, 1 = repeat all, 2 = repeat one. */
    var musicRepeatMode: Int
        get() = sp.getInt(KEY_MUSIC_REPEAT_MODE, 0)
        set(value) {
            sp.edit().putInt(KEY_MUSIC_REPEAT_MODE, value).apply()
        }

    /** Music queue shuffle toggle. */
    var musicShuffle: Boolean
        get() = sp.getBoolean(KEY_MUSIC_SHUFFLE, false)
        set(value) {
            sp.edit().putBoolean(KEY_MUSIC_SHUFFLE, value).apply()
        }

    var animations: Boolean
        get() = sp.getBoolean(KEY_ANIMATIONS, true)
        set(value) {
            sp.edit().putBoolean(KEY_ANIMATIONS, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()
        }

    var fullscreenScale: String
        get() = sp.getString(KEY_FULLSCREEN_SCALE, SCALE_STRETCH) ?: SCALE_STRETCH
        set(value) {
            sp.edit().putString(KEY_FULLSCREEN_SCALE, value).apply()
        }

    /** Hardware acceleration for playback: true = hardware decoders (default), false = software decoding. */
    var hardwareAcceleration: Boolean
        get() = sp.getBoolean(KEY_HARDWARE_ACCELERATION, true)
        set(value) {
            sp.edit().putBoolean(KEY_HARDWARE_ACCELERATION, value).apply()
        }

    /** Shorts / reels videos: search tab + navbar shortcut (on by default). */
    var shortsEnabled: Boolean
        get() = sp.getBoolean(KEY_SHORTS_ENABLED, true)
        set(value) {
            sp.edit().putBoolean(KEY_SHORTS_ENABLED, value).apply()
        }

    /** Shorts player scale mode: false = fit (whole video), true = fill/crop (v1.7.2). */
    var shortsFillMode: Boolean
        get() = sp.getBoolean(KEY_SHORTS_FILL, false)
        set(value) {
            sp.edit().putBoolean(KEY_SHORTS_FILL, value).apply()
        }
}
