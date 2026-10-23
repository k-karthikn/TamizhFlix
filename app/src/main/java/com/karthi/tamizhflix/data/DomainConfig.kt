package com.karthi.tamizhflix.data

import android.content.Context
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface DomainProvider {
    suspend fun home(source: Source, forceRefresh: Boolean = false): String
}

object BuiltInDomains : DomainProvider {
    override suspend fun home(source: Source, forceRefresh: Boolean) = source.home
}

data class DomainConfig(val moviesda: String, val isaidub: String) {
    fun home(source: Source): String = when (source) {
        Source.TAMIL -> "$moviesda/home.html"
        Source.DUBBED -> "$isaidub/"
    }

    companion object {
        fun parse(json: String): DomainConfig {
            val domains = JsonParser.parseString(json).asJsonObject.getAsJsonObject("domains")
            return DomainConfig(
                validate(domains.get("moviesda").asString),
                validate(domains.get("isaidub").asString)
            )
        }

        private fun validate(value: String): String {
            val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid source domain")
            require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == "/" && url.query == null && url.fragment == null &&
                url.host.contains('.') && !url.host.contains(':') &&
                !url.host.matches(Regex("[0-9.]+"))) { "Invalid source domain" }
            return "https://${url.host}"
        }
    }
}

/** The user's GitHub file is the update authority; its config_url field cannot redirect this trust anchor. */
class RemoteDomains(context: Context) : DomainProvider {
    private companion object {
        const val CONFIG_URL =
            "https://raw.githubusercontent.com/k-karthikn/padam-cli/refs/heads/master/config/config.json"
        const val SUCCESS_TTL = 60 * 60 * 1000L
        const val FAILURE_TTL = 5 * 60 * 1000L
    }

    private val preferences = context.applicationContext.getSharedPreferences("source_domains", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var cached = runCatching {
        preferences.getString("config", null)?.let(DomainConfig::parse)
    }.getOrNull()
    private var lastAttempt = preferences.getLong("updated_at", 0L)
    private var lastSuccess = cached != null

    override suspend fun home(source: Source, forceRefresh: Boolean): String = mutex.withLock {
        val now = System.currentTimeMillis()
        val ttl = if (lastSuccess) SUCCESS_TTL else FAILURE_TTL
        val recentlyAttempted = lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < ttl
        val skipRecentForced = forceRefresh && lastAttempt > 0 && now >= lastAttempt &&
            now - lastAttempt < 30_000L
        if ((!forceRefresh && recentlyAttempted) || skipRecentForced) {
            return@withLock cached?.home(source) ?: source.home
        }

        val updated = try { withContext(Dispatchers.IO) { fetchConfig() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        lastAttempt = now
        lastSuccess = updated != null
        if (updated != null) {
            cached = updated
            preferences.edit().putString("config", updated.toJson()).putLong("updated_at", now).apply()
        }
        (cached?.home(source) ?: source.home)
    }

    private fun fetchConfig(): DomainConfig {
        client.newCall(Request.Builder().url(CONFIG_URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Config returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty config")
            if (body.contentLength() > 64_000) throw IOException("Config is too large")
            val source = body.source()
            source.request(64_001)
            val bytes = source.buffer.readByteArray(source.buffer.size.coerceAtMost(64_001))
            if (bytes.size > 64_000) throw IOException("Config is too large")
            return DomainConfig.parse(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun DomainConfig.toJson() =
        "{\"domains\":{\"moviesda\":\"$moviesda\",\"isaidub\":\"$isaidub\"}}"
}
