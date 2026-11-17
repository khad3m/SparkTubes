package com.sparktube.app.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Disk-backed metadata cache (v1.9.4).
 *
 * Keeps the resolved InnerTube player metadata per video on disk so
 * reopening a video, re-entering the player or building the personalized
 * feeds is instant and costs zero InnerTube round-trips within the TTL.
 *
 * Layout: one small JSON file per key inside `cacheDir/metadata_cache`
 * (`{"ts": <saved-at>, "body": <payload>}`). Every read is TTL-guarded,
 * the whole store is capped at [MAX_DISK_BYTES] with oldest-first pruning,
 * and the settings Cache guard wipes it together with the image/HTTP caches.
 */
object MetadataCache {

    private const val DIR = "metadata_cache"

    /** Hard cap so the metadata cache can never balloon on low-storage devices. */
    private const val MAX_DISK_BYTES = 25L * 1024 * 1024

    fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR)

    /** Returns the cached payload for [key] if it exists and is younger than [ttlMs]. */
    fun read(context: Context, key: String, ttlMs: Long): String? {
        val f = fileFor(context, key)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (System.currentTimeMillis() - o.optLong("ts", 0L) > ttlMs) {
                f.delete()
                null
            } else {
                o.optString("body").ifBlank { null }
            }
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    /** Stores [body] under [key]; best-effort — cache failures never surface. */
    fun write(context: Context, key: String, body: String) {
        try {
            val f = fileFor(context, key)
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("body", body)
                    .toString()
            )
            prune(context)
        } catch (e: Exception) {
            // Cache writes are opportunistic; a full disk must not break playback.
        }
    }

    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    fun sizeBytes(context: Context): Long {
        val d = dir(context)
        if (!d.exists()) return 0L
        return d.walk().filter { it.isFile }.sumOf { it.length() }
    }

    private fun fileFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    /** Oldest-first pruning when the store grows past [MAX_DISK_BYTES]. */
    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        val ordered = files.sortedBy { f ->
            runCatching { JSONObject(f.readText()).optLong("ts", 0L) }.getOrDefault(0L)
        }
        var index = 0
        while (total > MAX_DISK_BYTES && index < ordered.size) {
            val f = ordered[index++]
            total -= f.length()
            f.delete()
        }
    }
}
