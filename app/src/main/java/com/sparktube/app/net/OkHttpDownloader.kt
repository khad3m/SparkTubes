package com.sparktube.app.net

import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OkHttp based [Downloader] implementation used by NewPipeExtractor,
 * upgraded with:
 * - Automatic Google / YouTube session cookie + `SAPISIDHASH` + `X-Goog-Visitor-Id` injection ([YouTubeAuth])
 * - Passive `visitorData` and `datasyncId` harvesting from `/youtubei/v1/` responses
 * - Automatic `player_es6.vflset` -> `player_ias.vflset` normalization so NewPipeExtractor's
 *   JavaScript regexes and Mozilla Rhino engine always match the ES5 main player bundle
 * - Outgoing `/youtubei/v1/player`, `/youtubei/v1/reel/reel_item_watch`, and `/youtubei/v1/next`
 *   `contentCheckOk` & `racyCheckOk` injection ([YouTubePlayerUnlock])
 * - Automatic Multi-Client InnerTube Fallback (`TV_SAMSUNG`, `WEB_EMBEDDED_PLAYER`,
 *   `TVHTML5_SIMPLY_EMBEDDED_PLAYER`, `WEB_CREATOR`, `MWEB`, `ANDROID_VR`, `VISIONOS`)
 *   on both `/youtubei/v1/player` and `/youtubei/v1/reel/reel_item_watch`.
 */
object OkHttpDownloader : Downloader() {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        val ctx = com.sparktube.app.SparkTubeAppHolder.context
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Only HTTP metadata/HTML responses are cached here. ExoPlayer/googlevideo
            // media bytes do NOT use this cache, so a large video can never fill it.
            .cache(
                Cache(
                    File(ctx.cacheDir, "http_cache"),
                    if (com.sparktube.app.util.DeviceTuning.lowRam(ctx)) 4L * 1024L * 1024L
                    else 12L * 1024L * 1024L
                )
            )
            .build()
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        var url = request.url()
        // If NewPipe requests an ES6 player bundle directly, rewrite to the ES5 ias bundle
        if (url.contains("/player_es6.vflset/")) {
            url = url.replace("/player_es6.vflset/", "/player_ias.vflset/")
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        val existingCookies = mutableListOf<String>()
        request.headers().forEach { (name, values) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                existingCookies.addAll(values)
            } else {
                values.forEach { value -> builder.addHeader(name, value) }
            }
        }

        // Inject YouTube session cookies, SAPISIDHASH, and X-Goog-Visitor-Id if signed in
        YouTubeAuth.applyYouTubeAuthToRequest(url, builder, existingCookies)

        val rawBody = request.dataToSend()
        val body = YouTubePlayerUnlock.patchOutgoingRequestBody(url, rawBody)

        when (request.httpMethod().uppercase(Locale.ROOT)) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(
                (body ?: ByteArray(0)).toRequestBody("application/json".toMediaTypeOrNull())
            )
            "PUT" -> builder.put(
                (body ?: ByteArray(0)).toRequestBody("application/json".toMediaTypeOrNull())
            )
            else -> throw IOException("Unsupported HTTP method: ${request.httpMethod()}")
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val finalUrl = response.request.url.toString()
            var rawText = response.body?.string() ?: ""

            // Normalize player_es6.vflset -> player_ias.vflset in embed/iframe_api/watch HTML
            // so NewPipeExtractor v0.26.5's regex ("/s/player/.../player_ias.vflset/.../base.js")
            // and Rhino ES5 parser always succeed (yt-dlp parity: forcing "main" player JS variant).
            if (rawText.contains("player_es6.vflset")) {
                rawText = rawText.replace("player_es6.vflset", "player_ias.vflset")
            }

            if (finalUrl.contains("/youtubei/v1/")) {
                YouTubeAuth.sniffInnerTubeResponse(rawText)
            }

            val unlockedText = YouTubePlayerUnlock.ensurePlayable(finalUrl, body, rawText, client)
            val finalText = CommentAvatarFix.apply(finalUrl, unlockedText)
            val responseCode = if (unlockedText !== rawText && response.code != 200) 200 else response.code
            return Response(
                responseCode,
                response.message,
                response.headers.toMultimap(),
                finalText,
                finalUrl
            )
        }
    }
}
