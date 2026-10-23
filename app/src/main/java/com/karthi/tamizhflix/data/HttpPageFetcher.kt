package com.karthi.tamizhflix.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpPageFetcher : PageFetcher {
    companion object { const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36" }
    private sealed interface FetchResult {
        data class Redirect(val url: String) : FetchResult
        data class Loaded(val page: Page) : FetchResult
    }
    private class HttpStatusException(val status: Int) : IOException("Source returned HTTP $status. Try again or choose another source.")
    private val transientStatus = setOf(429, 500, 502, 503, 504, 520, 521, 522, 523, 524)
    private val cookies = mutableListOf<Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this@HttpPageFetcher.cookies) {
            cookies.forEach { next ->
                this@HttpPageFetcher.cookies.removeAll { it.name == next.name && it.domain == next.domain && it.path == next.path }
                this@HttpPageFetcher.cookies.add(next)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
            cookies.filter { it.matches(url) }
        }
    }
    private val client = OkHttpClient.Builder().cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .build()

    fun cookieHeader(url: String) = jar.loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }

    override suspend fun fetch(url: String, referer: String): Page {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps) { "The source returned an insecure link. Choose another server." }
        for (attempt in 0..1) {
            try { return followRedirects(url, referer) }
            catch (e: IOException) {
                if (attempt == 1 || e is HttpStatusException && e.status !in transientStatus) throw e
                delay(400)
            }
        }
        error("Unreachable retry state")
    }

    private suspend fun followRedirects(url: String, referer: String): Page {
        var current = url
        repeat(10) {
            when (val result = fetchOnce(current, referer)) {
                is FetchResult.Loaded -> return result.page
                is FetchResult.Redirect -> {
                    current = result.url
                    mediaTypeForUrl(current)?.let { return Page(current, mediaType = it) }
                }
            }
        }
        throw IOException("The source redirected too many times. Try another server.")
    }

    private fun mediaTypeForUrl(url: String): String? = when (url.toHttpUrl().encodedPath.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "m3u8" -> "application/vnd.apple.mpegurl"
        else -> null
    }

    private suspend fun fetchOnce(url: String, referer: String): FetchResult = suspendCancellableCoroutine { continuation ->
        val parsed = url.toHttpUrl()
        require(parsed.isHttps) { "The source returned an insecure link. Choose another server." }
        val request = Request.Builder().url(parsed).header("User-Agent", USER_AGENT)
            .apply { if (referer.isNotBlank()) header("Referer", referer) }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code in 300..399) {
                            val target = it.header("Location")?.let(it.request.url::resolve)
                                ?: throw IOException("The source redirected without a valid address.")
                            if (!target.isHttps || target.username.isNotEmpty() || target.password.isNotEmpty())
                                throw IOException("The source redirected to an insecure link. Choose another server.")
                            if (continuation.isActive) continuation.resume(FetchResult.Redirect(target.toString()))
                            return
                        }
                        if (!it.isSuccessful) throw HttpStatusException(it.code)
                        val body = it.body ?: throw IOException("The source returned an empty response.")
                        val mime = body.contentType()?.toString().orEmpty()
                        val binary = mime.startsWith("video/") || mime.startsWith("application/octet-stream") ||
                            mime.contains("mpegurl") || mime.startsWith("application/mp4") ||
                            mime.startsWith("application/x-matroska") ||
                            (mime.isBlank() && mediaTypeForUrl(it.request.url.toString()) != null)
                        val html = if (binary) "" else {
                            val source = body.source()
                            source.request(2_000_001)
                            val bytes = source.buffer.readByteArray(source.buffer.size.coerceAtMost(2_000_001))
                            if (bytes.size > 2_000_000) throw IOException("The source page is too large.")
                            bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        }
                        val page = Page(it.request.url.toString(), html, mime, it.header("Content-Disposition"),
                            body.contentLength().takeIf { size -> size >= 0 })
                        if (continuation.isActive) continuation.resume(FetchResult.Loaded(page))
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
