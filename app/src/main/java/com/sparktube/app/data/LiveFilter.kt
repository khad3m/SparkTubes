package com.sparktube.app.data

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

/**
 * SparkTube never shows live streams anywhere: not in feeds, not in search
 * results, not in the player. Ended-live (post live) content is hidden too.
 *
 * Also provides strict Music-Only filtering (`sanitizeMusic`) so the Music
 * tab, Music Search, and Radio Queue never mix in vlogs, trailers, gaming,
 * news, or long episodes.
 */
object LiveFilter {

    private val NON_MUSIC_KEYWORDS = listOf(
        "trailer", "teaser", "gameplay", "walkthrough", "highlights",
        "podcast", "episode", "ep.", "ep ", "interview", "vlog",
        "tutorial", "review", "unboxing", "lecture", "course",
        "news", "debate", "documentary", "full movie", "short film",
        "reaction", "reacts", "livestream", "press conference",
        "standup", "comedy", "recipe", "cooking"
    )

    fun isLiveType(type: StreamType?): Boolean = type == StreamType.LIVE_STREAM
        || type == StreamType.AUDIO_LIVE_STREAM
        || type == StreamType.POST_LIVE_STREAM
        || type == StreamType.POST_LIVE_AUDIO_STREAM

    fun isLive(item: StreamInfoItem): Boolean = isLiveType(item.streamType)

    fun isLive(info: StreamInfo): Boolean = isLiveType(info.streamType)

    /** Keeps only normal (non-live) stream items. */
    fun sanitize(items: List<InfoItem>): List<StreamInfoItem> =
        items.filterIsInstance<StreamInfoItem>()
            .filter { !isLive(it) }

    /**
     * Strict Music-Only filter:
     * - Rejects live / post-live streams
     * - Rejects shorts (< 55s) and long videos/compilations/podcasts (> 15 min = 900s)
     * - Rejects titles containing non-music video keywords (trailer, vlog, episode, tutorial, gaming, etc.)
     */
    fun isMusicTrack(item: StreamInfoItem): Boolean {
        if (isLive(item)) return false
        val dur = item.duration
        // When duration is reported (> 0), enforce song length bounds (55s .. 900s)
        if (dur in 1..54L || dur > 900L) return false
        val titleLower = item.name?.lowercase(Locale.US).orEmpty()
        if (titleLower.isBlank()) return false
        if (NON_MUSIC_KEYWORDS.any { kw -> titleLower.contains(kw) }) return false
        return true
    }

    /** Returns strictly music tracks (no general videos, vlogs, trailers, or long episodes). */
    fun sanitizeMusic(items: List<InfoItem>): List<StreamInfoItem> =
        items.filterIsInstance<StreamInfoItem>()
            .filter { isMusicTrack(it) }
}
