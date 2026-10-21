package com.karthi.tamizhflix.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpPageFetcher : PageFetcher {
    companion object { const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36" }
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
        .callTimeout(40, TimeUnit.SECONDS).followSslRedirects(false)
        .build()

    fun cookieHeader(url: String) = jar.loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }

    override suspend fun fetch(url: String, referer: String): Page = suspendCancellableCoroutine { continuation ->
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
                        if (!it.isSuccessful) throw IOException("Source returned HTTP ${it.code}. Try again or choose another source.")
                        val body = it.body ?: throw IOException("The source returned an empty response.")
                        val mime = body.contentType()?.toString().orEmpty()
                        val binary = mime.startsWith("video/") || mime.startsWith("application/octet-stream") ||
                            mime.contains("mpegurl") || mime.startsWith("application/mp4")
                        val html = if (binary) "" else {
                            val source = body.source()
                            source.request(2_000_001)
                            val bytes = source.buffer.readByteArray(source.buffer.size.coerceAtMost(2_000_001))
                            if (bytes.size > 2_000_000) throw IOException("The source page is too large.")
                            bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        }
                        val page = Page(it.request.url.toString(), html, mime, it.header("Content-Disposition"),
                            body.contentLength().takeIf { size -> size >= 0 })
                        if (continuation.isActive) continuation.resume(page)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
