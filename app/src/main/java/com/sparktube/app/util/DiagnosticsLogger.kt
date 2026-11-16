package com.sparktube.app.util

import android.content.Context
import android.os.Build
import com.sparktube.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v1.7.12 diagnostics: everything since app start — browse, search, playback,
 * unlock ladder, auto-login — is appended to a rotated on-device file so the
 * user can export it (Settings → Diagnostics log) and share it for
 * exact-reason debugging.
 *
 * Privacy: emails are masked, passwords/cookies are never logged.
 */
object DiagnosticsLogger {

    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val TAIL_CHARS = 24_000
    private const val RING_LINES = 400

    private val writer = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val emailMask = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")
    private var dir: File? = null
    private val ring = ArrayDeque<String>(RING_LINES)
    private val lock = Any()

    private val currentFile: File? get() = dir?.let { File(it, "diag.log") }
    private val oldFile: File? get() = dir?.let { File(it, "diag.old") }

    /** Installs the logger, writes a session header and chains the crash handler. */
    fun install(context: Context) {
        dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
        val prefs = runCatching { AppPrefs.countryOrDefault }.getOrDefault("?")
        val creds = runCatching {
            com.sparktube.app.net.YouTubeAuth.storedCredentials(context) != null
        }.getOrDefault(false)
        val loggedIn = runCatching { com.sparktube.app.net.YouTubeAuth.isLoggedIn() }.getOrDefault(false)
        log("I", "Session", "start app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} " +
            "country=$prefs hasCreds=$creds loggedIn=$loggedIn")
        wrapCrashHandler()
    }

    /** Chains an uncaught-exception handler so crashes land in the diag log too. */
    private fun wrapCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                log("E", "Crash", "${thread.name}: ${throwable.javaClass.name}: ${throwable.message ?: ""}")
                throwable.stackTrace.take(14).forEach {
                    log("E", "Crash", "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
                }
            }
            previous.uncaughtException(thread, throwable)
        }
    }

    fun info(tag: String, msg: String) = log("I", tag, msg)
    fun warn(tag: String, msg: String) = log("W", tag, msg)
    fun error(tag: String, msg: String) = log("E", tag, msg)

    fun log(level: String, tag: String, message: String) {
        val safe = message.replace(emailMask) { m ->
            val v = m.value
            val at = v.indexOf('@')
            v.take(1) + "***" + v.substring(at)
        }
        val line = "${stamp.format(Date())} $level/$tag: ${safe.take(400)}"
        synchronized(lock) {
            if (ring.size >= RING_LINES) ring.removeFirst()
            ring.addLast(line)
        }
        writer.execute {
            runCatching { appendToFile(line) }
        }
    }

    private fun appendToFile(line: String) {
        val f = currentFile ?: return
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            oldFile?.delete()
            f.renameTo(oldFile ?: return)
        }
        f.appendText(line + "\n")
    }

    /**
     * Readable tail for the Diagnostics screen: previous rotated file
     * (shortened) + the current file tail. Falls back to the in-memory ring.
     */
    fun tailText(): String {
        val head = runCatching {
            oldFile?.let { f ->
                if (f.exists()) {
                    val text = f.readText()
                    if (text.length > TAIL_CHARS / 4) "…${text.takeLast(TAIL_CHARS / 4)}" else text
                } else ""
            } ?: ""
        }.getOrDefault("")
        val tail = runCatching {
            val f = currentFile
            if (f != null && f.exists()) {
                val text = f.readText()
                if (text.length > TAIL_CHARS) "…${text.takeLast(TAIL_CHARS)}" else text
            } else ""
        }.getOrDefault("")
        if (head.isBlank() && tail.isBlank()) {
            val snapshot = synchronized(lock) { ring.joinToString("\n") }
            return snapshot.ifBlank { "No log entries yet." }
        }
        return head + tail
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        writer.execute {
            runCatching {
                oldFile?.delete()
                currentFile?.delete()
            }
        }
    }
}
