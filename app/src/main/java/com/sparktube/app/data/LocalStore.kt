package com.sparktube.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    /** True when this entry was favorited from the music player. */
    val isMusic: Boolean = false
)

data class ChannelEntry(
    val url: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCount: Long
)

/** A finished, paused, failed, or in-progress download tracked by the app. */
data class DownloadRecord(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    /** AUDIO | VIDEO | AV */
    val type: String,
    val quality: String,
    /** PENDING | RUNNING | PAUSED | DONE | FAILED */
    val status: String,
    /** Local file paths (one for AUDIO/VIDEO, two for AV pairs). */
    val filePaths: List<String>,
    val downloadIds: List<Long>,
    /** Original YouTube stream duration in seconds (for offline lists & queue). */
    val durationSec: Long = 0L,
    /** Original YouTube watch URL (for mode switching, retry re-extraction, sharing, and metadata). */
    val sourceUrl: String = "",
    /** Direct CDN stream URLs (one for AUDIO/VIDEO, two for AV) used for Pause/Resume/Retry. */
    val streamUrls: List<String> = emptyList()
)

/** A user-created playlist with its saved videos (all local, account-free). */
data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val items: List<VideoEntry>
)

/**
 * Tiny JSON-in-SharedPreferences store for watch history, favorites,
 * subscriptions and downloads. No accounts needed: everything stays on
 * the device.
 */
object LocalStore {

    private const val KEY_HISTORY = "history_json"
    private const val KEY_MUSIC_HISTORY = "music_history_json"
    private const val KEY_FAVORITES = "favorites_json"
    private const val KEY_SUBSCRIPTIONS = "subscriptions_json"
    private const val KEY_DOWNLOADS = "downloads_json"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val KEY_SEARCHES = "searches_json"
    private const val KEY_MUSIC_SEARCHES = "music_searches_json"
    private const val MAX_HISTORY = 200
    private const val MAX_FAVORITES = 500
    private const val MAX_SUBSCRIPTIONS = 200
    private const val MAX_SEARCHES = 20
    private const val MAX_PLAYLIST_VIDEOS = 500
    private const val KEY_HOME_CACHE = "home_feed_cache_v2"
    private const val KEY_MUSIC_CACHE = "music_feed_cache_v2"
    private const val MAX_FEED_CACHE_ITEMS = 18
    /** v1.9.7: how many DISTINCT sections (country|query) we keep cached per store. */
    private const val MAX_FEED_CACHE_SECTIONS = 14

    private fun sectionKey(country: String, query: String) = "$country|$query"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("sparktube_local", Context.MODE_PRIVATE)


    // ----- Tiny warm-start feed cache -----

    /** Stores only lightweight UI metadata, never media bytes. */
    fun saveFeedCache(context: Context, music: Boolean, country: String, query: String, items: List<VideoEntry>) {
        // v1.9.7: multi-section cache — every section keeps its own snapshot so the
        // app can open ANY previously-seen section instantly, not just the last one.
        val key = if (music) KEY_MUSIC_CACHE else KEY_HOME_CACHE
        val sp = prefs(context)
        val map = runCatching { JSONObject(sp.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val arr = JSONArray()
        items.take(MAX_FEED_CACHE_ITEMS).forEach { e ->
            arr.put(JSONObject().apply {
                put("url", e.url); put("title", e.title); put("uploader", e.uploader)
                put("thumb", e.thumbnailUrl); put("duration", e.durationSec); put("music", e.isMusic)
            })
        }
        map.put(sectionKey(country, query), JSONObject().apply {
            put("at", System.currentTimeMillis()); put("country", country)
            put("query", query); put("items", arr)
        })
        // v1.9.7: keep only the freshest MAX_FEED_CACHE_SECTIONS (old cache cleanup)
        val entries = mutableListOf<Pair<String, Long>>()
        val it = map.keys()
        while (it.hasNext()) { val k = it.next(); entries.add(k to map.optJSONObject(k)?.optLong("at", 0L) ?: 0L) }
        if (entries.size > MAX_FEED_CACHE_SECTIONS) {
            val keep = entries.sortedByDescending { it.second }.take(MAX_FEED_CACHE_SECTIONS).map { it.first }.toSet()
            for (k in entries.map { it.first }) if (k !in keep) map.remove(k)
        }
        sp.edit().putString(key, map.toString()).apply()
    }

    fun readFeedCache(context: Context, music: Boolean, country: String, query: String, ttlMs: Long): List<VideoEntry> {
        return runCatching {
            val raw = prefs(context).getString(if (music) KEY_MUSIC_CACHE else KEY_HOME_CACHE, null) ?: return emptyList()
            val o = JSONObject(raw).optJSONObject(sectionKey(country, query)) ?: return emptyList()
            if (o.optString("country") != country || o.optString("query") != query ||
                System.currentTimeMillis() - o.optLong("at", 0L) > ttlMs) return emptyList()
            val a = o.optJSONArray("items") ?: return emptyList()
            buildList { for (i in 0 until a.length()) { val x = a.getJSONObject(i); add(VideoEntry(
                x.optString("url"), x.optString("title"), x.optString("uploader"),
                x.optString("thumb"), x.optLong("duration"), x.optBoolean("music"))) } }
        }.getOrDefault(emptyList())
    }

    fun clearFeedCache(context: Context) {
        prefs(context).edit().remove(KEY_HOME_CACHE).remove(KEY_MUSIC_CACHE).apply()
    }

    // ----- History -----

    fun history(context: Context): List<VideoEntry> =
        read(prefs(context), KEY_HISTORY)

    fun addToHistory(context: Context, entry: VideoEntry) {
        val ctx = context.applicationContext
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == entry.url }
        list.add(0, entry)
        while (list.size > MAX_HISTORY) {
            list.removeAt(list.size - 1)
        }
        write(prefs(ctx), KEY_HISTORY, list)
    }

    fun removeFromHistory(context: Context, url: String) {
        val ctx = context.applicationContext
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == url }
        write(prefs(ctx), KEY_HISTORY, list)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    // ----- Music history -----

    /** Recently played songs (kept separate from the video watch history). */
    fun musicHistory(context: Context): List<VideoEntry> =
        read(prefs(context), KEY_MUSIC_HISTORY)

    fun addToMusicHistory(context: Context, entry: VideoEntry) {
        val ctx = context.applicationContext
        val list = musicHistory(ctx).toMutableList()
        list.removeAll { it.url == entry.url }
        list.add(0, entry)
        while (list.size > MAX_HISTORY) {
            list.removeAt(list.size - 1)
        }
        write(prefs(ctx), KEY_MUSIC_HISTORY, list)
    }

    fun clearMusicHistory(context: Context) {
        prefs(context).edit().remove(KEY_MUSIC_HISTORY).apply()
    }

    // ----- Search history -----

    private fun searchKey(music: Boolean) = if (music) KEY_MUSIC_SEARCHES else KEY_SEARCHES

    /** Recent search queries, newest first (case preserved, deduplicated). */
    fun searches(context: Context, music: Boolean = false): List<String> =
        readSearches(prefs(context), searchKey(music))

    fun addSearch(context: Context, query: String, music: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        val ctx = context.applicationContext
        val key = searchKey(music)
        val list = readSearches(prefs(ctx), key).toMutableList()
        list.removeAll { it.equals(q, ignoreCase = true) }
        list.add(0, q)
        while (list.size > MAX_SEARCHES) {
            list.removeAt(list.size - 1)
        }
        prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }

    fun removeSearch(context: Context, query: String, music: Boolean = false) {
        val ctx = context.applicationContext
        val key = searchKey(music)
        val list = readSearches(prefs(ctx), key).toMutableList()
        list.removeAll { it == query }
        prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }

    /** Clears BOTH the video and the music search history. */
    fun clearSearches(context: Context) {
        prefs(context).edit().remove(KEY_SEARCHES).remove(KEY_MUSIC_SEARCHES).apply()
    }

    // ----- Favorites -----

    fun favorites(context: Context): List<VideoEntry> =
        read(prefs(context), KEY_FAVORITES)

    /** Favorites saved from the music player. */
    fun musicFavorites(context: Context): List<VideoEntry> =
        favorites(context).filter { it.isMusic }

    /** Favorites saved from the video watch page. */
    fun videoFavorites(context: Context): List<VideoEntry> =
        favorites(context).filter { !it.isMusic }

    fun isFavorite(context: Context, url: String): Boolean =
        favorites(context).any { it.url == url }

    /** @return the new favorite state for the entry. */
    fun toggleFavorite(context: Context, entry: VideoEntry): Boolean {
        val ctx = context.applicationContext
        val list = favorites(ctx).toMutableList()
        val exists = list.any { it.url == entry.url }
        if (exists) {
            list.removeAll { it.url == entry.url }
            write(prefs(ctx), KEY_FAVORITES, list)
            return false
        }
        list.add(0, entry)
        while (list.size > MAX_FAVORITES) {
            list.removeAt(list.size - 1)
        }
        write(prefs(ctx), KEY_FAVORITES, list)
        return true
    }

    fun clearFavorites(context: Context) {
        prefs(context).edit().remove(KEY_FAVORITES).apply()
    }

    fun clearSubscriptions(context: Context) {
        prefs(context).edit().remove(KEY_SUBSCRIPTIONS).apply()
    }

    fun clearSearchesOnly(context: Context) {
        prefs(context).edit().remove(KEY_SEARCHES).remove(KEY_MUSIC_SEARCHES).apply()
    }

    // ----- Subscriptions -----

    fun subscriptions(context: Context): List<ChannelEntry> =
        readChannels(prefs(context), KEY_SUBSCRIPTIONS)

    fun isSubscribed(context: Context, channelUrl: String): Boolean =
        subscriptions(context).any { it.url == channelUrl }

    /** @return the new subscribed state for the channel. */
    fun toggleSubscription(context: Context, entry: ChannelEntry): Boolean {
        val ctx = context.applicationContext
        val list = subscriptions(ctx).toMutableList()
        val exists = list.any { it.url == entry.url }
        if (exists) {
            list.removeAll { it.url == entry.url }
            writeChannels(prefs(ctx), KEY_SUBSCRIPTIONS, list)
            return false
        }
        list.add(0, entry)
        writeChannels(prefs(ctx), KEY_SUBSCRIPTIONS, list)
        return true
    }

    // ----- Downloads registry -----

    fun downloads(context: Context): List<DownloadRecord> =
        readDownloads(prefs(context))

    /** Video / AV downloads shown in Library > Downloads. */
    fun videoDownloads(context: Context): List<DownloadRecord> =
        downloads(context).filter { it.type != "AUDIO" }

    /** Music (audio) downloads shown on the Music page. */
    fun musicDownloads(context: Context): List<DownloadRecord> =
        downloads(context).filter { it.type == "AUDIO" }

    fun addDownload(context: Context, record: DownloadRecord) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        list.removeAll { it.videoId == record.videoId && it.type == record.type && it.quality == record.quality }
        list.add(0, record)
        writeDownloads(prefs(ctx), list)
    }

    fun updateDownloadRecord(context: Context, updated: DownloadRecord) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        val idx = list.indexOfFirst {
            it.videoId == updated.videoId && it.type == updated.type && it.quality == updated.quality
        }
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(0, updated)
        }
        writeDownloads(prefs(ctx), list)
    }

    fun updateDownloadStatus(context: Context, downloadId: Long, status: String) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        val idx = list.indexOfFirst { record -> record.downloadIds.contains(downloadId) }
        if (idx >= 0) {
            val updated = list[idx].copy(status = status)
            list[idx] = updated
            writeDownloads(prefs(ctx), list)
        }
    }

    fun removeDownload(context: Context, record: DownloadRecord) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        list.removeAll { it.videoId == record.videoId && it.type == record.type && it.quality == record.quality }
        writeDownloads(prefs(ctx), list)
    }

    fun clearDownloads(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOADS).apply()
    }

    // ----- Playlists -----

    /** All playlists, newest first. */
    fun playlists(context: Context): List<Playlist> =
        readPlaylists(prefs(context))

    fun playlist(context: Context, id: Long): Playlist? =
        playlists(context).firstOrNull { it.id == id }

    fun createPlaylist(context: Context, name: String): Playlist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val ctx = context.applicationContext
        val list = playlists(ctx).toMutableList()
        if (list.any { it.name.equals(clean, ignoreCase = true) }) return null
        val playlist = Playlist(
            id = System.currentTimeMillis(),
            name = clean,
            createdAt = System.currentTimeMillis(),
            items = emptyList()
        )
        list.add(0, playlist)
        writePlaylists(prefs(ctx), list)
        return playlist
    }

    fun deletePlaylist(context: Context, id: Long) {
        val ctx = context.applicationContext
        val list = playlists(ctx).toMutableList()
        list.removeAll { it.id == id }
        writePlaylists(prefs(ctx), list)
    }

    /** v1.9.6: premium playlist management — inline rename. */
    fun renamePlaylist(context: Context, id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val ctx = context.applicationContext
        val list = playlists(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(name = clean)
        writePlaylists(prefs(ctx), list)
        return true
    }

    fun addToPlaylist(context: Context, id: Long, entry: VideoEntry): Boolean {
        val ctx = context.applicationContext
        val list = playlists(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val target = list[idx]
        if (target.items.any { it.url == entry.url }) return false
        val items = target.items.toMutableList()
        items.add(0, entry)
        while (items.size > MAX_PLAYLIST_VIDEOS) {
            items.removeAt(items.size - 1)
        }
        list[idx] = target.copy(items = items)
        writePlaylists(prefs(ctx), list)
        return true
    }

    fun removeFromPlaylist(context: Context, id: Long, url: String): Boolean {
        val ctx = context.applicationContext
        val list = playlists(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val target = list[idx]
        val items = target.items.filterNot { it.url == url }
        if (items.size == target.items.size) return false
        list[idx] = target.copy(items = items)
        writePlaylists(prefs(ctx), list)
        return true
    }

    fun isVideoInPlaylist(context: Context, id: Long, url: String): Boolean =
        playlist(context, id)?.items?.any { it.url == url } == true

    // ----- JSON helpers -----

    private fun read(sp: android.content.SharedPreferences, key: String): List<VideoEntry> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                VideoEntry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    thumbnailUrl = o.optString("thumb"),
                    durationSec = o.optLong("duration"),
                    isMusic = o.optBoolean("music", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(sp: android.content.SharedPreferences, key: String, list: List<VideoEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("uploader", entry.uploader)
                    .put("thumb", entry.thumbnailUrl)
                    .put("duration", entry.durationSec)
                    .put("music", entry.isMusic)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    // ----- Channel JSON helpers -----

    private fun readChannels(sp: android.content.SharedPreferences, key: String): List<ChannelEntry> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.getJSONObject(idx)
                ChannelEntry(
                    url = o.optString("url"),
                    name = o.optString("name"),
                    avatarUrl = o.optString("avatar"),
                    subscriberCount = o.optLong("subs")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeChannels(sp: android.content.SharedPreferences, key: String, list: List<ChannelEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("name", entry.name)
                    .put("avatar", entry.avatarUrl)
                    .put("subs", entry.subscriberCount)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun readSearches(sp: android.content.SharedPreferences, key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx -> arr.optString(idx) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ----- Playlist JSON helpers -----

    private fun readPlaylists(sp: android.content.SharedPreferences): List<Playlist> {
        val raw = sp.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.getJSONObject(idx)
                Playlist(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    createdAt = o.optLong("created"),
                    items = o.optJSONArray("items")?.let { items ->
                        (0 until items.length()).mapNotNull { i ->
                            val io = items.optJSONObject(i) ?: return@mapNotNull null
                            entryFromJson(io)
                        }
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writePlaylists(sp: android.content.SharedPreferences, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { playlist ->
            arr.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("created", playlist.createdAt)
                    .put("items", JSONArray(playlist.items.map { entryToJson(it) }))
            )
        }
        sp.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    private fun entryFromJson(o: JSONObject): VideoEntry = VideoEntry(
        url = o.optString("url"),
        title = o.optString("title"),
        uploader = o.optString("uploader"),
        thumbnailUrl = o.optString("thumb"),
        durationSec = o.optLong("duration"),
        isMusic = o.optBoolean("music", false)
    )

    private fun entryToJson(entry: VideoEntry): JSONObject = JSONObject()
        .put("url", entry.url)
        .put("title", entry.title)
        .put("uploader", entry.uploader)
        .put("thumb", entry.thumbnailUrl)
        .put("duration", entry.durationSec)
        .put("music", entry.isMusic)

    // ----- Download JSON helpers -----

    private fun readDownloads(sp: android.content.SharedPreferences): List<DownloadRecord> {
        val raw = sp.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.getJSONObject(idx)
                val vid = o.optString("videoId")
                DownloadRecord(
                    videoId = vid,
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    thumbnailUrl = o.optString("thumb"),
                    type = o.optString("type"),
                    quality = o.optString("quality"),
                    status = o.optString("status"),
                    filePaths = o.optJSONArray("files")?.let { files ->
                        (0 until files.length()).map { files.optString(it) }
                    } ?: emptyList(),
                    downloadIds = o.optJSONArray("ids")?.let { ids ->
                        (0 until ids.length()).map { ids.optLong(it) }
                    } ?: emptyList(),
                    durationSec = o.optLong("duration", 0L),
                    sourceUrl = o.optString("sourceUrl").ifBlank {
                        if (vid.length == 11) "https://www.youtube.com/watch?v=$vid" else ""
                    },
                    streamUrls = o.optJSONArray("streamUrls")?.let { urls ->
                        (0 until urls.length()).map { urls.optString(it) }
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeDownloads(sp: android.content.SharedPreferences, list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { record ->
            arr.put(
                JSONObject()
                    .put("videoId", record.videoId)
                    .put("title", record.title)
                    .put("uploader", record.uploader)
                    .put("thumb", record.thumbnailUrl)
                    .put("type", record.type)
                    .put("quality", record.quality)
                    .put("status", record.status)
                    .put("files", JSONArray(record.filePaths))
                    .put("ids", JSONArray(record.downloadIds))
                    .put("duration", record.durationSec)
                    .put("sourceUrl", record.sourceUrl)
                    .put("streamUrls", JSONArray(record.streamUrls))
            )
        }
        sp.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }
}
