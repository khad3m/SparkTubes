package com.sparktube.app.data

import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.net.YouTubePlayerUnlock
import com.sparktube.app.util.AppPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PageResult(
    val items: List<StreamInfoItem>,
    val nextPage: Page?
)

data class ChannelPageResult(
    val items: List<ChannelEntry>,
    val nextPage: Page?
)

/** YouTube playlist search result row (Search → Playlists tab, v1.7.0). */
data class RemotePlaylistEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val videoCount: Long
)

data class RemotePlaylistPageResult(
    val items: List<RemotePlaylistEntry>,
    val nextPage: Page?
)

data class ChannelUi(
    val url: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCount: Long,
    val description: String
)

data class CommentUi(
    val id: String,
    val author: String,
    val avatarUrl: String,
    val text: String,
    val textIsHtml: Boolean,
    val likes: Long,
    val date: String,
    val pinned: Boolean,
    val hearted: Boolean,
    val replyCount: Int,
    val repliesPage: Page? = null,
    val isReply: Boolean = false
)

data class CommentsPage(
    val items: List<CommentUi>,
    val nextPage: Page?,
    val total: Int,
    val disabled: Boolean
)

/** One timestamped lyric line parsed from LRCLIB `[mm:ss.xx] text`. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val syncedLines: List<LyricLine>,
    val plainLyrics: String
)

/** SponsorBlock segment for automatic skipping on the video watch page. */
data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

/** Timestamped subtitle cue for live video captions (supports Bangla Auto-Translate & Original CC). */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * All YouTube access goes through this repository.
 * Every list returned here is already filtered: live streams never make it out,
 * and Music endpoints strictly return songs only (via YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
 * + LiveFilter.sanitizeMusic).
 */
object YtRepository {

    private val service: StreamingService get() = ServiceList.YouTube

    private const val STREAM_INFO_CACHE_TTL_MS = 3 * 60 * 1000L
    private data class CachedStreamInfo(val info: StreamInfo, val atMs: Long)
    private val streamInfoCache =
        object : LinkedHashMap<String, CachedStreamInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStreamInfo>?): Boolean =
                size > if (com.sparktube.app.util.DeviceTuning.lowRam()) 10 else 30
        }

    private val HOME_KIOSKS = listOf(
        "trending_movies_and_shows",
        "trending_podcasts_episodes",
        "trending_gaming"
    )

    suspend fun applyCountry(countryCode: String) = withContext(Dispatchers.IO) {
        NewPipe.setPreferredContentCountry(ContentCountry(countryCode))
    }

    suspend fun trending(countryCode: String): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(countryCode))

            var lastError: Exception? = null
            val lists = HOME_KIOSKS.mapNotNull { kioskId ->
                try {
                    fetchKiosk(kioskId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    emptyList()
                }
            }.filter { it.isNotEmpty() }

            if (lists.isEmpty()) {
                throw lastError ?: IOException("No content available for this region")
            }

            val blended = mutableListOf<StreamInfoItem>()
            val seen = mutableSetOf<String>()
            val maxSize = lists.maxOf { it.size }
            for (index in 0 until maxSize) {
                for (list in lists) {
                    if (index < list.size && list[index].url !in seen) {
                        seen.add(list[index].url)
                        blended.add(list[index])
                    }
                }
            }
            blended
        }

    private const val MAX_PER_CHANNEL = 3
    private const val RELATED_FETCH_TIMEOUT_MS = 10_000L

    suspend fun personalizedFeed(
        countryCode: String,
        snap: RecommendEngine.Snapshot
    ): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val trending = trending(countryCode)
        if (!snap.hasSignal) {
            // Even without personal history, rotate within tiers so refresh shows variety
            return@withContext trending.chunked(6).flatMap { it.shuffled() }
        }

        val watched = snap.recentWatchedUrls.toHashSet()
        val seen = HashSet<String>()
        val candidates = mutableListOf<StreamInfoItem>()

        // 1) Dynamic search seeds from user's top keywords & preferred channels (shuffled per load)
        val topKeywords = snap.keywordWeights.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }
            .shuffled()
            .take(4)
        val topChannels = snap.channelWeights.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key }
            .shuffled()
            .take(2)
        val seedQueries = buildList {
            if (topKeywords.size >= 2) add("${topKeywords[0]} ${topKeywords[1]}")
            if (topKeywords.size >= 4) add("${topKeywords[2]} ${topKeywords[3]}")
            topChannels.forEach { add(it) }
        }.distinct().take(3)

        withTimeoutOrNull(RELATED_FETCH_TIMEOUT_MS) {
            coroutineScope {
                val relJobs = snap.relatedSources.take(4).map { sourceUrl ->
                    async { runCatching { related(sourceUrl) }.getOrDefault(emptyList()) }
                }
                val searchJobs = seedQueries.map { q ->
                    async { runCatching { searchVideos(q, null).items }.getOrDefault(emptyList()) }
                }
                (relJobs + searchJobs).awaitAll()
            }
        }?.forEach { list ->
            list.forEach { item ->
                val u = item.url.orEmpty()
                if (u.isNotBlank() && u !in watched && seen.add(u)) {
                    candidates.add(item)
                }
            }
        }

        val trendingFresh = trending.filter { it.url !in watched && seen.add(it.url) }
        val pool = candidates + trendingFresh
        if (pool.isEmpty()) {
            return@withContext trending.shuffled()
        }
        val ranked = pool
            .map { it to (RecommendEngine.score(it, snap) + Math.random() * 0.45) }
            .sortedByDescending { (_, score) -> score }

        val perChannel = HashMap<String, Int>()
        val head = mutableListOf<StreamInfoItem>()
        val tail = mutableListOf<StreamInfoItem>()
        ranked.forEach { (item, _) ->
            val key = item.uploaderName?.trim()?.lowercase().orEmpty()
                .ifEmpty { item.url }
            val count = perChannel.getOrDefault(key, 0)
            if (count >= MAX_PER_CHANNEL) {
                tail.add(item)
            } else {
                perChannel[key] = count + 1
                head.add(item)
            }
        }

        head + tail
    }

    /**
     * Strict Music-Only Trending feed:
     * Never uses YouTube's general TrendingVideos kiosk (which mixes vlogs, gaming, and movie trailers).
     * Always queries YouTube Music's `MUSIC_SONGS` catalog and filters via `LiveFilter.sanitizeMusic`.
     */
    suspend fun musicTrending(countryCode: String): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(countryCode))
            val countryName = Countries.nameOf(countryCode)
            val queries = listOf(
                "$countryName top songs hits",
                "top hits songs official audio"
            )
            val combined = mutableListOf<StreamInfoItem>()
            val seen = HashSet<String>()
            for (q in queries) {
                runCatching {
                    val handler = service.searchQHFactory.fromQuery(
                        q,
                        listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                        ""
                    )
                    val res = executeMusicSearch(handler, null)
                    res.items.forEach { item ->
                        if (!item.url.isNullOrBlank() && seen.add(item.url!!)) {
                            combined.add(item)
                        }
                    }
                }
                if (combined.size >= 24) break
            }
            PageResult(combined, null)
        }

    /** Video search. Live results are stripped before they reach the UI. */
    suspend fun searchVideos(query: String, page: Page? = null): PageResult = try {
        searchVideosInternal(query, page).also {
            com.sparktube.app.util.DiagnosticsLogger.log(
                "I", "Search", "q='$query' page=${page?.id ?: "1"} items=${it.items.size}"
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        com.sparktube.app.util.DiagnosticsLogger.log(
            "E", "Search", "FAIL q='$query' ${e.javaClass.simpleName}: ${e.message?.take(200) ?: ""}"
        )
        throw e
    }

    private suspend fun searchVideosInternal(query: String, page: Page? = null): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            executeSearch(handler, page)
        }

    /**
     * Strict YouTube Music songs search:
     * Uses `MUSIC_SONGS` handler + `LiveFilter.sanitizeMusic` so no non-music videos appear.
     */
    suspend fun searchMusic(query: String, page: Page? = null): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                ""
            )
            executeMusicSearch(handler, page)
        }

    /** Channel-only search for the Channels tab of the search screen. */
    suspend fun searchChannels(query: String, page: Page? = null): ChannelPageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.CHANNELS),
                ""
            )
            executeChannelSearch(handler, page)
        }

    /**
     * Playlist search for the Search → Playlists tab (v1.7.0): uses the
     * PLAYLISTS search filter so users can open full playlists directly
     * from search results.
     */
    suspend fun searchPlaylists(query: String, page: Page? = null): RemotePlaylistPageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
                ""
            )
            val raw: List<InfoItem>
            val nextPage: Page?
            if (page == null) {
                val info = SearchInfo.getInfo(service, handler)
                raw = info.relatedItems
                nextPage = info.nextPage
            } else {
                val result = SearchInfo.getMoreItems(service, handler, page)
                raw = result.items
                nextPage = result.nextPage
            }
            val items = raw.filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
                .filter { !it.url.isNullOrBlank() }
                .map {
                    RemotePlaylistEntry(
                        url = it.url.orEmpty(),
                        title = it.name.orEmpty(),
                        uploader = it.uploaderName.orEmpty(),
                        thumbnailUrl = it.thumbnails.maxByOrNull { t -> t.height }?.url.orEmpty(),
                        videoCount = it.streamCount
                    )
                }
            RemotePlaylistPageResult(items, nextPage)
        }

    /**
     * All videos of a YouTube playlist, live content removed — used to build
     * the play queue when opening a playlist from Search (video or audio).
     */
    suspend fun playlistItems(url: String): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, url)
            LiveFilter.sanitize(info.relatedItems)
        }

    // ----- Shorts / Reels feed (v1.7.1) -----

    private val shortsHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** One short-video row parsed out of the FEshorts browse response. */
    data class ShortsFeedItem(
        val videoId: String,
        val title: String,
        val uploader: String,
        val thumbnailUrl: String
    )

    /** Depth-first collection of every JSONObject stored under [key]. */
    private fun collectObjectsByKey(node: Any?, key: String, out: MutableList<org.json.JSONObject>) {
        when (node) {
            is org.json.JSONObject -> {
                val direct = node.optJSONObject(key)
                if (direct != null) out.add(direct)
                for (k in node.keys()) collectObjectsByKey(node.opt(k), key, out)
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) collectObjectsByKey(node.opt(i), key, out)
            }
        }
    }

    /** Depth-first collection of every string stored under [key]. */
    private fun collectStringValues(node: Any?, key: String, out: MutableList<String>) {
        when (node) {
            is org.json.JSONObject -> {
                val direct = node.optString(key).orEmpty()
                if (direct.isNotBlank()) out.add(direct)
                for (k in node.keys()) collectStringValues(node.opt(k), key, out)
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) collectStringValues(node.opt(i), key, out)
            }
        }
    }

    /** First non-blank "content" string found anywhere inside the node. */
    private fun firstContentString(node: Any?): String {
        val out = mutableListOf<String>()
        collectStringValues(node, "content", out)
        return out.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun parseShortsFeed(json: org.json.JSONObject): List<ShortsFeedItem> {
        val items = mutableListOf<ShortsFeedItem>()
        val seen = HashSet<String>()

        // Legacy format: reelItemRenderer
        val reels = mutableListOf<org.json.JSONObject>()
        collectObjectsByKey(json, "reelItemRenderer", reels)
        for (reel in reels) {
            val id = reel.optString("videoId").orEmpty()
            if (id.length != 11 || !seen.add(id)) continue
            val overlay = reel.optJSONObject("overlayMetadata")
            val title = firstContentString(overlay?.optJSONObject("primaryText"))
                .ifBlank { firstContentString(reel.optJSONObject("header")) }
            val uploader = firstContentString(overlay?.optJSONObject("secondaryText"))
            val thumbs = mutableListOf<org.json.JSONObject>()
            collectObjectsByKey(reel.optJSONObject("thumbnail"), "thumbnail", thumbs)
            val thumbUrl = thumbs.mapNotNull { it.optString("url").orEmpty().ifBlank { null } }
                .lastOrNull()
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            items.add(ShortsFeedItem(id, title, uploader, thumbUrl))
        }

        // Current format: shortsLockupViewModel (embedded in search results
        // and on the shorts tab). videoId lives inside the reelWatchEndpoint;
        // the cover thumbnail inside its `thumbnail.thumbnails`.
        val lockups = mutableListOf<org.json.JSONObject>()
        collectObjectsByKey(json, "shortsLockupViewModel", lockups)
        for (lockup in lockups) {
            val ids = mutableListOf<String>()
            collectStringValues(lockup, "videoId", ids)
            val id = ids.firstOrNull() ?: ""
            if (id.length != 11 || !seen.add(id)) continue
            val overlay = lockup.optJSONObject("overlayMetadata")
            val title = firstContentString(overlay?.optJSONObject("primaryText")).ifBlank {
                // accessibilityText fallback: "TITLE, 48K views - play Short"
                val a11y = lockup.optString("accessibilityText").orEmpty()
                a11y.substringBeforeLast(" - play Short").substringBeforeLast(",").trim()
            }
            val views = firstContentString(overlay?.optJSONObject("secondaryText"))
            val thumbUrls = mutableListOf<org.json.JSONObject>()
            collectObjectsByKey(lockup, "thumbnails", thumbUrls)
            val thumbUrl = thumbUrls.mapNotNull { it.optString("url").orEmpty().ifBlank { null } }
                .lastOrNull()
                ?: "https://i.ytimg.com/vi/$id/frame0.jpg"
            items.add(ShortsFeedItem(id, title, views, thumbUrl))
        }
        return items
    }

    /**
     * The Shorts / Reels feed (v1.7.1) via the InnerTube WEB search endpoint —
     * the same endpoint the youtube.com shorts tab uses (FEshorts browse is
     * no longer accepted anonymously).
     *  - `gl` = the app's country preference (e.g. BD for Bangladesh)
     *  - when the user signed in with Google, session cookies + SAPISIDHASH
     *    are sent, so the results follow the same account personalization as
     *    the signed-in web experience (user-specific reels).
     * Several discovery queries are merged and de-duplicated for a feed feel.
     */
    suspend fun shortsFeed(countryCode: String): List<ShortsFeedItem> =
        withContext(Dispatchers.IO) {
            val queries = listOf(
                "indian model reels shorts",
                "hindi romantic movie scene shorts",
                "hindi trending reels shorts",
                "instagram reels girl fashion shorts",
                "bollywood romantic status shorts",
                "bollywood trending shorts"
            )
            val buckets = coroutineScope {
                queries.map { q ->
                    async { shortsSearchInnerTube(q, countryCode) }
                }.awaitAll()
            }
            val out = LinkedHashMap<String, ShortsFeedItem>()
            val maxLen = buckets.maxOfOrNull { it.size } ?: 0
            for (idx in 0 until maxLen) {
                for (bucket in buckets) {
                    val item = bucket.getOrNull(idx) ?: continue
                    if (item.videoId.length == 11) {
                        out.putIfAbsent(item.videoId, item)
                    }
                }
            }
            out.values.toList()
        }

    suspend fun shortsFeedMore(countryCode: String, pageIndex: Int): List<ShortsFeedItem> =
        withContext(Dispatchers.IO) {
            val pool = listOf(
                listOf("desi model reels shorts", "new hindi romantic song status shorts"),
                listOf("bollywood actress reels shorts", "trending love status hindi shorts"),
                listOf("viral indian dance reels shorts", "sad romantic movie scene hindi shorts"),
                listOf("fashion influencer reels india shorts", "hindi hit songs shorts")
            )
            val batch = pool[pageIndex.coerceAtLeast(0) % pool.size]
            val buckets = coroutineScope {
                batch.map { q -> async { shortsSearchInnerTube(q, countryCode) } }.awaitAll()
            }
            val out = LinkedHashMap<String, ShortsFeedItem>()
            val maxLen = buckets.maxOfOrNull { it.size } ?: 0
            for (idx in 0 until maxLen) {
                for (bucket in buckets) {
                    val item = bucket.getOrNull(idx) ?: continue
                    if (item.videoId.length == 11) out.putIfAbsent(item.videoId, item)
                }
            }
            out.values.toList()
        }

    /**
     * Shorts-only search (v1.7.1): query → shortsLockupViewModel rows, with
     * country preference and account personalization (cookies) applied.
     */
    suspend fun searchShorts(
        query: String,
        countryCode: String = AppPrefs.countryOrDefault
    ): List<ShortsFeedItem> = shortsSearchInnerTube(query, countryCode)

    private suspend fun shortsSearchInnerTube(
        query: String,
        countryCode: String
    ): List<ShortsFeedItem> = withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply {
                put("query", query)
                put(
                    "context",
                    org.json.JSONObject().put(
                        "client",
                        org.json.JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", "2.20250312.01.00")
                            .put("hl", "en")
                            .put("gl", countryCode.uppercase())
                            .put("utcOffsetMinutes", 0)
                    )
                )
            }
            val builder = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", com.sparktube.app.net.OkHttpDownloader.USER_AGENT)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20250312.01.00")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/shorts")
                .header("Accept-Language", "en-US,en;q=0.9")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            // Session cookies (+ SAPISIDHASH when signed in): personalized,
            // user-specific shorts — the same account the user logged into.
            com.sparktube.app.net.YouTubeAuth.buildCookieHeader().let {
                if (it.isNotBlank()) builder.header("Cookie", it)
            }
            if (com.sparktube.app.net.YouTubeAuth.isLoggedIn()) {
                com.sparktube.app.net.YouTubeAuth.buildAuthorizationHeader()?.let {
                    builder.header("Authorization", it)
                }
                builder.header("X-Goog-AuthUser", "0")
            }

            shortsHttpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string() ?: return@withContext emptyList()
                parseShortsFeed(org.json.JSONObject(text))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearCache() {
        synchronized(streamInfoCache) {
            streamInfoCache.clear()
        }
    }

    fun invalidateStreamCache(url: String) {
        synchronized(streamInfoCache) {
            streamInfoCache.remove(url)
        }
    }

    /** Full stream details for playback (with automatic Multi-Client InnerTube & Google Auth fallback). */
    suspend fun streamInfo(url: String): StreamInfo = try {
        streamInfoInternal(url).also {
            com.sparktube.app.util.DiagnosticsLogger.log(
                "I", "StreamInfo",
                "ok ${url.takeLast(30)} streams=${it.audioStreams.size}+${it.videoStreams.size} dur=${it.duration}s"
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        com.sparktube.app.util.DiagnosticsLogger.log(
            "E", "StreamInfo",
            "FAIL ${url.takeLast(30)} ${e.javaClass.simpleName}: ${e.message?.take(200) ?: ""}"
        )
        throw e
    }

    private suspend fun streamInfoInternal(url: String): StreamInfo =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(streamInfoCache) {
                streamInfoCache[url]
            }?.let { cached ->
                if (now - cached.atMs < STREAM_INFO_CACHE_TTL_MS) return@withContext cached.info
            }
            YouTubePlayerUnlock.clearNewPipeJavaScriptExceptions()
            val info = try {
                val first = StreamInfo.getInfo(service, url)
                if (first.videoStreams.isEmpty() && first.videoOnlyStreams.isEmpty() && first.audioStreams.isEmpty()) {
                    YouTubePlayerUnlock.clearNewPipeJavaScriptExceptions()
                    YouTubePlayerUnlock.forceUnlockForUrl(url)
                    StreamInfo.getInfo(service, url)
                } else {
                    first
                }
            } catch (firstErr: Exception) {
                YouTubePlayerUnlock.clearNewPipeJavaScriptExceptions()
                YouTubePlayerUnlock.forceUnlockForUrl(url)
                StreamInfo.getInfo(service, url)
            }
            synchronized(streamInfoCache) {
                streamInfoCache[url] = CachedStreamInfo(info, now)
            }
            info
        }

    /**
     * Search-based "related videos" used when the extractor returns an empty related list.
     * Uses the video title (+ uploader) so results stay on-topic.
     */
    suspend fun relatedFallback(title: String, uploader: String, excludeUrl: String): List<StreamInfoItem> {
        val cleanTitle = title
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { title }
        if (cleanTitle.isBlank()) return emptyList()
        val excludeId = extractVideoId(excludeUrl)
        val queries = listOf(cleanTitle, "$cleanTitle ${uploader.removeSuffix(" - Topic")}".trim())
        val seen = LinkedHashSet<String>()
        val out = ArrayList<StreamInfoItem>()
        for (q in queries) {
            val res = runCatching { searchVideos(q).items }.getOrDefault(emptyList())
            for (item in res) {
                val id = extractVideoId(item.url ?: "") ?: continue
                if (id == excludeId || !seen.add(id)) continue
                out.add(item)
            }
            if (out.size >= 15) break
        }
        return out.take(20)
    }

    suspend fun suggestions(query: String): List<String> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    service.suggestionExtractor.suggestionList(query)
                }.getOrDefault(emptyList())
            }
        }

    suspend fun channelInfo(url: String): ChannelUi =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, url)
            ChannelUi(
                url = info.url ?: url,
                name = info.name ?: "",
                avatarUrl = info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
                subscriberCount = info.subscriberCount,
                description = info.description.orEmpty()
            )
        }

    suspend fun channelVideos(channelUrl: String): Pair<List<StreamInfoItem>, Page?> =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            val tab = info.tabs.firstOrNull { handler ->
                handler.contentFilters.firstOrNull() == ChannelTabs.VIDEOS
            } ?: return@withContext emptyList<StreamInfoItem>() to null
            val page = ChannelTabInfo.getInfo(service, tab)
            LiveFilter.sanitize(page.relatedItems) to page.nextPage
        }

    suspend fun channelVideosMore(
        channelUrl: String,
        page: Page
    ): Pair<List<StreamInfoItem>, Page?> =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            val tab = info.tabs.firstOrNull { handler ->
                handler.contentFilters.firstOrNull() == ChannelTabs.VIDEOS
            } ?: return@withContext emptyList<StreamInfoItem>() to null
            val result = ChannelTabInfo.getMoreItems(service, tab, page)
            LiveFilter.sanitize(result.items) to result.nextPage
        }

    suspend fun comments(url: String): CommentsPage =
        withContext(Dispatchers.IO) {
            val info = CommentsInfo.getInfo(service, url)
            CommentsPage(
                items = info.relatedItems.map { it.toCommentUi() },
                nextPage = info.nextPage,
                total = info.commentsCount,
                disabled = info.isCommentsDisabled
            )
        }

    suspend fun commentsMore(url: String, page: Page): CommentsPage =
        withContext(Dispatchers.IO) {
            val result = CommentsInfo.getMoreItems(service, url, page)
            CommentsPage(
                items = result.items.map { it.toCommentUi() },
                nextPage = result.nextPage,
                total = -1,
                disabled = false
            )
        }

    suspend fun commentReplies(videoUrl: String, page: Page): CommentsPage =
        withContext(Dispatchers.IO) {
            val result = CommentsInfo.getMoreItems(service, videoUrl, page)
            CommentsPage(
                items = result.items.map { it.toCommentUi(isReply = true) },
                nextPage = result.nextPage,
                total = -1,
                disabled = false
            )
        }

    private fun CommentsInfoItem.toCommentUi(isReply: Boolean = false): CommentUi {
        val body = commentText
        val avatar = uploaderAvatars
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.height }
            ?.url
            .orEmpty()
        return CommentUi(
            id = commentId.orEmpty(),
            author = uploaderName.orEmpty(),
            avatarUrl = avatar,
            text = body?.content.orEmpty(),
            textIsHtml = body?.type == Description.HTML,
            likes = likeCount.toLong(),
            date = com.sparktube.app.util.Formatters.formatRelativeTime(textualUploadDate),
            pinned = isPinned,
            hearted = isHeartedByUploader,
            replyCount = replyCount.toInt(),
            repliesPage = if (isReply) null else replies,
            isReply = isReply
        )
    }

    suspend fun related(url: String): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            runCatching { streamInfo(url).relatedItems }
                .getOrDefault(emptyList())
                .let { LiveFilter.sanitize(it) }
        }

    // ----- Feature: Subtitles with Auto-Translate to Bangla (বাংলা) -----

    private val vttCueHeaderRegex = Regex(
        "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})"
    )

    /**
     * Fetches and parses video subtitles from NewPipeExtractor's `SubtitlesStream` list.
     * When `mode == AppPrefs.SUBTITLE_MODE_BANGLA`, prefers native Bengali captions if present,
     * otherwise appends YouTube's `&tlang=bn` server-side auto-translate parameter, and falls back
     * to client-side GTX translation into Bengali (`bn`) if needed.
     */
    suspend fun fetchSubtitles(
        subtitles: List<SubtitlesStream>,
        mode: String
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (mode == AppPrefs.SUBTITLE_MODE_OFF || subtitles.isEmpty()) return@withContext emptyList()
        try {
            val valid = subtitles.filter { it.isUrl && !it.content.isNullOrBlank() }
            if (valid.isEmpty()) return@withContext emptyList()

            val chosen = if (mode == AppPrefs.SUBTITLE_MODE_BANGLA) {
                valid.firstOrNull { it.languageTag.startsWith("bn", ignoreCase = true) }
                    ?: valid.firstOrNull { !it.isAutoGenerated && it.languageTag.startsWith("en", ignoreCase = true) }
                    ?: valid.firstOrNull { !it.isAutoGenerated }
                    ?: valid.first()
            } else {
                valid.firstOrNull { !it.isAutoGenerated } ?: valid.first()
            }

            val isAlreadyBangla = chosen.languageTag.startsWith("bn", ignoreCase = true)
            val baseUrl = chosen.content
            val targetUrl = if (mode == AppPrefs.SUBTITLE_MODE_BANGLA && !isAlreadyBangla) {
                if (baseUrl.contains("tlang=")) {
                    baseUrl.replace(Regex("tlang=[^&]+"), "tlang=bn")
                } else if (baseUrl.contains("?")) {
                    "$baseUrl&tlang=bn"
                } else {
                    "$baseUrl?tlang=bn"
                }
            } else {
                baseUrl
            }

            val rawTranslated = downloadSubtitleText(targetUrl)
            var cues = parseSubtitlePayload(rawTranslated)

            // Fallback: if &tlang=bn returned empty on a specific format, fetch original and auto-translate to Bangla
            if (cues.isEmpty() && mode == AppPrefs.SUBTITLE_MODE_BANGLA && targetUrl != baseUrl) {
                val rawOrig = downloadSubtitleText(baseUrl)
                val origCues = parseSubtitlePayload(rawOrig)
                if (origCues.isNotEmpty()) {
                    cues = translateCuesToBangla(origCues)
                }
            } else if (cues.isNotEmpty() && mode == AppPrefs.SUBTITLE_MODE_BANGLA && !isAlreadyBangla && !containsBengaliScript(cues)) {
                cues = translateCuesToBangla(cues)
            }

            cues
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun containsBengaliScript(cues: List<SubtitleCue>): Boolean =
        cues.take(15).any { cue -> cue.text.any { ch -> ch in '\u0980'..'\u09FF' } }

    private fun downloadSubtitleText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", OkHttpDownloader.USER_AGENT)
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSubtitlePayload(raw: String): List<SubtitleCue> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.startsWith("WEBVTT", ignoreCase = true) || vttCueHeaderRegex.containsMatchIn(trimmed)) {
            val vtt = parseWebVtt(trimmed)
            if (vtt.isNotEmpty()) return vtt
        }
        return parseXmlTimedText(trimmed)
    }

    private fun parseWebVtt(vtt: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        val lines = vtt.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val m = vttCueHeaderRegex.find(line)
            if (m != null) {
                val h1 = m.groupValues[1].toLongOrNull() ?: 0L
                val m1 = m.groupValues[2].toLongOrNull() ?: 0L
                val s1 = m.groupValues[3].toLongOrNull() ?: 0L
                val ms1 = m.groupValues[4].toLongOrNull() ?: 0L
                val startMs = ((h1 * 3600L + m1 * 60L + s1) * 1000L) + ms1

                val h2 = m.groupValues[5].toLongOrNull() ?: 0L
                val m2 = m.groupValues[6].toLongOrNull() ?: 0L
                val s2 = m.groupValues[7].toLongOrNull() ?: 0L
                val ms2 = m.groupValues[8].toLongOrNull() ?: 0L
                val endMs = ((h2 * 3600L + m2 * 60L + s2) * 1000L) + ms2

                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val clean = Jsoup.parse(lines[i]).text().trim()
                    if (clean.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                        textBuilder.append(clean)
                    }
                    i++
                }
                val finalTxt = textBuilder.toString().trim()
                if (finalTxt.isNotBlank() && endMs > startMs) {
                    out.add(SubtitleCue(startMs, endMs, finalTxt))
                }
            } else {
                i++
            }
        }
        return mergeOverlappingCues(out)
    }

    private fun parseXmlTimedText(xml: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val elements = doc.select("p, text")
        for (el in elements) {
            val rawText = Parser.unescapeEntities(el.text(), false).trim()
            if (rawText.isBlank()) continue

            val startMs: Long
            val endMs: Long
            when {
                el.hasAttr("t") -> {
                    startMs = el.attr("t").toLongOrNull() ?: continue
                    val dur = el.attr("d").toLongOrNull() ?: 3000L
                    endMs = startMs + dur
                }
                el.hasAttr("start") -> {
                    val startSec = el.attr("start").toDoubleOrNull() ?: continue
                    val durSec = el.attr("dur").toDoubleOrNull() ?: 3.0
                    startMs = (startSec * 1000.0).toLong()
                    endMs = startMs + (durSec * 1000.0).toLong()
                }
                el.hasAttr("begin") -> {
                    startMs = parseTimeCodeToMs(el.attr("begin")) ?: continue
                    endMs = parseTimeCodeToMs(el.attr("end"))
                        ?: (el.attr("dur").let { parseTimeCodeToMs(it) }?.let { startMs + it } ?: (startMs + 3000L))
                }
                else -> continue
            }
            if (endMs > startMs) {
                out.add(SubtitleCue(startMs, endMs, rawText))
            }
        }
        return mergeOverlappingCues(out)
    }

    private fun parseTimeCodeToMs(code: String): Long? {
        val c = code.trim()
        if (c.isEmpty()) return null
        if (c.endsWith("s") && !c.contains(":")) {
            return c.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        val parts = c.replace(',', '.').split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    ((h * 3600L + m * 60L) * 1000L) + (s * 1000.0).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    (m * 60L * 1000L) + (s * 1000.0).toLong()
                }
                else -> c.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeOverlappingCues(cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.sortedBy { it.startMs }
        val result = ArrayList<SubtitleCue>(sorted.size)
        for (cue in sorted) {
            val prev = result.lastOrNull()
            if (prev != null && prev.text == cue.text && cue.startMs <= prev.endMs + 200L) {
                result[result.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, cue.endMs))
            } else {
                result.add(cue)
            }
        }
        return result
    }

    /** Client-side fallback auto-translator into Bengali (`bn`) in compact batches. */
    private fun translateCuesToBangla(cues: List<SubtitleCue>): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>(cues.size)
        val batchSize = 20
        var idx = 0
        while (idx < cues.size && idx < 240) {
            val slice = cues.subList(idx, minOf(idx + batchSize, cues.size))
            val joined = slice.joinToString("\n") { it.text.replace('\n', ' ') }
            val translatedLines = runCatching {
                val q = URLEncoder.encode(joined, "UTF-8")
                val u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=bn&dt=t&q=$q"
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 6_000
                conn.setRequestProperty("User-Agent", OkHttpDownloader.USER_AGENT)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val outer = JSONArray(body).optJSONArray(0)
                val sb = StringBuilder()
                if (outer != null) {
                    for (j in 0 until outer.length()) {
                        val seg = outer.optJSONArray(j)?.optString(0, "").orEmpty()
                        sb.append(seg)
                    }
                }
                sb.toString().lines()
            }.getOrNull()

            slice.forEachIndexed { i, cue ->
                val bn = translatedLines?.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() } ?: cue.text
                out.add(cue.copy(text = bn))
            }
            idx += batchSize
        }
        if (idx < cues.size) {
            out.addAll(cues.subList(idx, cues.size))
        }
        return out
    }

    // ----- Feature 1: Real-Time Synced Lyrics (LRCLIB API) -----

    private val lrcRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")

    /**
     * Queries LRCLIB (`https://lrclib.net/api/search`) for time-synced `.lrc` lyrics
     * matching the track title and artist. Cleans YouTube noise ("Official Video", "Lyrics", etc.).
     */
    suspend fun fetchSyncedLyrics(
        rawTitle: String,
        rawArtist: String,
        durationSec: Long = 0L
    ): LyricsResult? = withContext(Dispatchers.IO) {
        try {
            val (title, artist) = cleanTrackAndArtist(rawTitle, rawArtist)
            if (title.isBlank()) return@withContext null

            fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

            // 1) Exact lookup (best accuracy, uses duration to pick the right version)
            if (artist.isNotBlank()) {
                val dur = if (durationSec > 0L) "&duration=$durationSec" else ""
                lrclibGet("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$dur")
                    ?.let { obj -> toLyricsResult(listOf(obj))?.let { return@withContext it } }
            }
            // 2) Structured search
            if (artist.isNotBlank()) {
                lrclibSearch("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
                    ?.let { arr -> toLyricsResult(sortByDuration(arr, durationSec))?.let { return@withContext it } }
            }
            // 3) Title only
            lrclibSearch("https://lrclib.net/api/search?track_name=${enc(title)}")
                ?.let { arr -> toLyricsResult(sortByDuration(arr, durationSec))?.let { return@withContext it } }
            // 4) Free-text fallback
            lrclibSearch("https://lrclib.net/api/search?q=${enc("$title $artist".trim())}")
                ?.let { arr -> toLyricsResult(sortByDuration(arr, durationSec))?.let { return@withContext it } }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Strips YouTube noise and splits "Artist - Song" titles. */
    private fun cleanTrackAndArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle
        var artist = rawArtist
            .removeSuffix(" - Topic")
            .replace(Regex("(?i)\\bvevo\\b|\\bofficial\\b"), "")
            .trim()

        // "Artist - Song" style titles
        val dash = Regex("\\s[-\u2013\u2014|]\\s").find(title)
        if (dash != null) {
            val left = title.substring(0, dash.range.first).trim()
            val right = title.substring(dash.range.last + 1).trim()
            if (left.isNotBlank() && right.isNotBlank()) {
                artist = left
                title = right
            }
        }
        title = title
            .replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?}"), " ")
            .replace(Regex("(?i)\\b(official|music|video|audio|lyrics?|lyrical|full\\s*song|hd|hq|4k|mv|visualizer|remastered)\\b"), " ")
            .replace(Regex("(?i)\\b(feat|ft)\\.?\\s.*$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawTitle.trim() }
        artist = artist
            .replace(Regex("(?i)\\b(feat|ft)\\.?\\s.*$"), "")
            .split(Regex("\\s*[,&]\\s*|\\s+x\\s+"))
            .firstOrNull()
            .orEmpty()
            .trim()
        return title to artist
    }

    private fun lrclibRequest(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "SparkTube/1.6.3 (https://github.com/devfahim00/SparkTube)")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun lrclibGet(url: String): JSONObject? =
        lrclibRequest(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun lrclibSearch(url: String): List<JSONObject>? {
        val arr = lrclibRequest(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        if (arr.length() == 0) return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun sortByDuration(items: List<JSONObject>, durationSec: Long): List<JSONObject> {
        if (durationSec <= 0L) return items
        return items.sortedBy { kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec.toDouble()) }
    }

    private fun toLyricsResult(items: List<JSONObject>): LyricsResult? {
        var bestSynced = ""
        var bestPlain = ""
        for (obj in items) {
            val sy = obj.optString("syncedLyrics", "")
            val pl = obj.optString("plainLyrics", "")
            if (sy.isNotBlank() && bestSynced.isBlank() && sy != "null") bestSynced = sy
            if (pl.isNotBlank() && bestPlain.isBlank() && pl != "null") bestPlain = pl
            if (bestSynced.isNotBlank()) break
        }
        val parsed = parseLrc(bestSynced)
        if (parsed.isEmpty() && bestPlain.isBlank()) return null
        return LyricsResult(parsed, bestPlain)
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val m = lrcRegex.find(line.trim()) ?: return@forEach
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val fracRaw = m.groupValues[3]
            val ms = when (fracRaw.length) {
                1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                3 -> fracRaw.toLongOrNull() ?: 0L
                else -> 0L
            }
            val text = m.groupValues[4].trim()
            if (text.isNotBlank()) {
                out.add(LyricLine(min * 60_000L + sec * 1_000L + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    // ----- Feature 5: SponsorBlock & ReturnYouTubeDislike -----

    fun extractVideoId(url: String): String? {
        val m = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)
        return m?.groupValues?.getOrNull(1)
    }

    /** Fetches SponsorBlock skip segments (`sponsor`, `selfpromo`, `intro`, `outro`, `interaction`). */
    suspend fun fetchSponsorSegments(videoUrl: String): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            val videoId = extractVideoId(videoUrl) ?: return@withContext emptyList()
            try {
                val cats = URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"intro\",\"outro\",\"interaction\"]", "UTF-8")
                val api = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$cats"
                val conn = URL(api).openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 6_000
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext emptyList()
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = JSONArray(text)
                val list = mutableListOf<SponsorSegment>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val seg = obj.optJSONArray("segment") ?: continue
                    if (seg.length() >= 2) {
                        val startMs = (seg.optDouble(0, 0.0) * 1000.0).toLong()
                        val endMs = (seg.optDouble(1, 0.0) * 1000.0).toLong()
                        val cat = obj.optString("category", "sponsor")
                        if (endMs > startMs) {
                            list.add(SponsorSegment(startMs, endMs, cat))
                        }
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** Fetches community dislike count via ReturnYouTubeDislike API. */
    suspend fun fetchDislikeCount(videoUrl: String): Long =
        withContext(Dispatchers.IO) {
            val videoId = extractVideoId(videoUrl) ?: return@withContext -1L
            try {
                val conn = URL("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 6_000
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext -1L
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject(text).optLong("dislikes", -1L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                -1L
            }
        }

    private fun fetchKiosk(kioskId: String): List<StreamInfoItem> {
        val extractor = service.kioskList.getExtractorById(kioskId, null)
        extractor.fetchPage()
        val items = extractor.initialPage.items
        return LiveFilter.sanitize(items.filterIsInstance<InfoItem>())
    }

    private fun executeSearch(
        handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler,
        page: Page?
    ): PageResult {
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            PageResult(LiveFilter.sanitize(info.relatedItems), info.nextPage)
        } else {
            val result = SearchInfo.getMoreItems(service, handler, page)
            PageResult(LiveFilter.sanitize(result.items), result.nextPage)
        }
    }

    private fun executeMusicSearch(
        handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler,
        page: Page?
    ): PageResult {
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            PageResult(LiveFilter.sanitizeMusic(info.relatedItems), info.nextPage)
        } else {
            val result = SearchInfo.getMoreItems(service, handler, page)
            PageResult(LiveFilter.sanitizeMusic(result.items), result.nextPage)
        }
    }

    private fun executeChannelSearch(
        handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler,
        page: Page?
    ): ChannelPageResult {
        val raw: List<InfoItem>
        val nextPage: Page?
        if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            raw = info.relatedItems
            nextPage = info.nextPage
        } else {
            val result = SearchInfo.getMoreItems(service, handler, page)
            raw = result.items
            nextPage = result.nextPage
        }
        val seen = HashSet<String>()
        val channels = raw.filterIsInstance<ChannelInfoItem>()
            .filter { !it.url.isNullOrBlank() && seen.add(it.url!!) }
            .map { item ->
                ChannelEntry(
                    url = item.url.orEmpty(),
                    name = item.name.orEmpty(),
                    avatarUrl = item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    subscriberCount = item.subscriberCount
                )
            }
        return ChannelPageResult(channels, nextPage)
    }
}
