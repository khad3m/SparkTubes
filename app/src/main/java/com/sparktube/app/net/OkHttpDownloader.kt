package com.sparktube.app.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OkHttp based [Downloader] implementation used by NewPipeExtractor,
 * upgraded with:
 * - Automatic Google / YouTube session cookie + `SAPISIDHASH` header injection ([YouTubeAuth])
 * - Outgoing `/youtubei/v1/player` `contentCheckOk` & `racyCheckOk` injection ([YouTubePlayerUnlock])
 * - Automatic Multi-Client InnerTube Fallback (`ANDROID_VR`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`, `IOS`)
 *   whenever a video returns `LOGIN_REQUIRED`, `AGE_VERIFICATION_REQUIRED`, or lacks direct stream URLs.
 */
object OkHttpDownloader : Downloader() {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
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

        // Inject YouTube session cookies & SAPISIDHASH authorization if signed in
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
            val rawText = response.body?.string() ?: ""
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
