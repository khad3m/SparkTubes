package com.sparktube.app.data

import android.content.Context
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.9.7: background section preloader.
 *
 * While the app is running it lazily fetches every Home category and every
 * Music preset/quick-mix section (staggered, low priority) and keeps the
 * multi-section cache warm. On the next app open the first screen — and every
 * other section — renders instantly from the previous session's cache: no
 * boring loading state.
 */
object SectionPreloader {

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Let the user's first screen and its images load before we start. */
    private const val FIRST_DELAY_MS = 4500L
    /** Gentle stagger between sections — no bandwidth or CPU contention. */
    private const val STEP_DELAY_MS = 1400L
    /** Re-check cycle: sections newer than FRESH_MS are skipped. */
    private const val LOOP_IDLE_MS = 150_000L
    private const val FRESH_MS = 10 * 60_000L

    private val HOME_SECTIONS = listOf(
        "hindi trending songs", "new bangla song latest", "new bangla natok full",
        "latest hindi song new", "lofi songs", "bangla hindi item song",
        "bangla news today latest", "music", "gaming", "technology ai review",
        "podcast episodes", "movie trailers"
    )

    private val MUSIC_SECTIONS = listOf(
        "pop hits", "hip hop hits", "bollywood songs", "bangla songs", "rock hits",
        "edm hits", "lofi songs", "classical music", "indie songs", "acoustic songs",
        "instrumental music", "workout music", "punjabi hits", "romantic hindi songs"
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            delay(FIRST_DELAY_MS)
            while (true) {
                try {
                    prefetchAll(app)
                } catch (e: Exception) {
                    DiagnosticsLogger.log("W", "Preloader", "cycle: ${e.javaClass.simpleName}")
                }
                delay(LOOP_IDLE_MS)
            }
        }
    }

    private suspend fun prefetchAll(app: Context) {
        val country = AppPrefs.countryOrDefault
        val sections = buildList {
            HOME_SECTIONS.forEach { add(false to it) }
            MUSIC_SECTIONS.forEach { add(true to it) }
        }
        for ((music, q) in sections) {
            if (LocalStore.readFeedCache(app, music, country, q, FRESH_MS).isNotEmpty()) continue
            try {
                val page = YtRepository.searchVideos(q, null)
                LocalStore.saveFeedCache(app, music, country, q, page.items.map { it.toUiModel().toEntry() })
            } catch (e: Exception) {
                DiagnosticsLogger.log("W", "Preloader", "'$q' -> ${e.javaClass.simpleName}")
            }
            delay(STEP_DELAY_MS)
        }
    }
}
