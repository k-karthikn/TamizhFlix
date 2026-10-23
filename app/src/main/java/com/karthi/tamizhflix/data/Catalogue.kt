package com.karthi.tamizhflix.data

import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.coroutineContext

enum class Source(val label: String, val home: String) {
    TAMIL("Tamil", "https://moviesda.ski/home.html"),
    DUBBED("Tamil dubbed", "https://isaidub.com/")
}

data class Link(val title: String, val url: String, val referer: String = "")
data class Page(val url: String, val html: String = "", val mediaType: String? = null,
                val disposition: String? = null, val bytes: Long? = null)
data class SearchResult(val movies: List<Link>, val warning: String? = null)
sealed interface Resolution {
    data class Choices(val links: List<Link>) : Resolution
    data class File(val link: Link, val mime: String, val disposition: String?, val bytes: Long?) : Resolution
}

/** A transport boundary keeps parsing and traversal testable without Android or live sites. */
fun interface PageFetcher { suspend fun fetch(url: String, referer: String): Page }

object CatalogueParser {
    fun document(page: Page): Document = Jsoup.parse(page.html, page.url)

    private fun safeUrl(base: String, raw: String?): String? {
        val value = Parser.unescapeEntities(raw.orEmpty().trim(), false)
        if (value.isBlank() || value.startsWith("#")) return null
        val url = base.toHttpUrlOrNull()?.resolve(value) ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.newBuilder().fragment(null).build().toString()
    }

    fun links(doc: Document, selector: String): List<Link> =
        doc.select(selector).mapNotNull { element ->
            val url = safeUrl(doc.location(), element.attr("href")) ?: return@mapNotNull null
            val title = element.text().trim()
            if (title.isBlank() || url == doc.location()) null else Link(title, url, doc.location())
        }.distinctBy { it.url }

    fun entries(doc: Document): List<Link> =
        links(doc, ".f a[href], .folder a[href]").filterNot {
            it.title.matches(Regex("(?i)([a-z]|\\d+|Tamil \\d{4}( Dubbed)? Movies)")) ||
                it.url.toHttpUrl().encodedPath == "/"
        }

    fun pages(doc: Document): List<String> =
        doc.select(".pagination a[href], .pagecontent a[href], a[rel=next]").mapNotNull { anchor ->
            val url = safeUrl(doc.location(), anchor.attr("href")) ?: return@mapNotNull null
            val parsed = url.toHttpUrl()
            val title = anchor.text().trim().lowercase()
            val navigation = title.matches(Regex("\\d+")) || title in setOf("next", "previous", "prev", "»", "«") ||
                anchor.attr("rel").contains("next", ignoreCase = true) || parsed.queryParameter("page") != null
            url.takeIf { navigation && parsed.host == doc.location().toHttpUrl().host && url != doc.location() }
        }.distinct()

    fun choices(doc: Document): List<Link> {
        val downloads = links(doc, ".dlink a[href], a.coral[href]").filterNot {
            it.title.contains("watch", ignoreCase = true) || it.title.contains("play online", ignoreCase = true)
        }
        if (downloads.isNotEmpty()) return downloads
        val folders = entries(doc)
        if (folders.isNotEmpty()) return folders

        val found = linkedMapOf<String, Link>()
        fun add(raw: String?, title: String) {
            val url = safeUrl(doc.location(), raw) ?: return
            if (url != doc.location()) found.putIfAbsent(url, Link(title.trim().ifBlank { "Download link" }, url, doc.location()))
        }
        val meaningful = Regex("download|server|quality|get link|get url|continue|click here|720p|1080p|360p|\\.(?:mp4|mkv|m3u8)|/file/", RegexOption.IGNORE_CASE)
        val redirect = Regex("(?:location(?:\\.href)?\\s*=|location\\.(?:assign|replace)\\(|window\\.open\\()\\s*['\"]([^'\"]+)")
        doc.select("a[download][href], a.download[href], a[class*=download][href], a[class*=server][href], .download a[href], .server a[href]")
            .forEach { add(it.attr("href"), it.text().ifBlank { it.attr("aria-label") }) }
        doc.select("a[href]").forEach { anchor ->
            if (meaningful.containsMatchIn(anchor.text() + " " + anchor.attr("href")))
                add(anchor.attr("href"), anchor.text().ifBlank { anchor.attr("aria-label") })
        }
        if (found.isNotEmpty()) return found.values.toList()

        doc.select("video[src], source[src]").forEach { add(it.attr("src"), "Video stream") }
        doc.select("meta[property=og:video][content], meta[property=og:video:url][content]")
            .forEach { add(it.attr("content"), "Video stream") }
        doc.select("form[action]").forEach { form ->
            val action = form.attr("action")
            if (!form.attr("method").equals("post", true) && meaningful.containsMatchIn(form.text() + " " + action)) {
                val url = safeUrl(doc.location(), action)?.toHttpUrlOrNull() ?: return@forEach
                val builder = url.newBuilder()
                form.select("input[type=hidden][name]").forEach { builder.addQueryParameter(it.attr("name"), it.attr("value")) }
                add(builder.build().toString(), form.text().ifBlank { "Continue" })
            }
        }
        doc.select("[data-url], [data-href], [onclick]").forEach { element ->
            add(element.attr("data-url").ifBlank { element.attr("data-href") }, element.text().ifBlank { "Continue" })
            redirect.find(element.attr("onclick"))?.let { add(it.groupValues[1], element.text().ifBlank { "Continue" }) }
        }
        doc.select("meta[http-equiv]").filter { it.attr("http-equiv").equals("refresh", true) }.forEach { meta ->
            Regex("url\\s*=\\s*['\"]?([^'\";]+)", RegexOption.IGNORE_CASE).find(meta.attr("content"))
                ?.let { add(it.groupValues[1], "Continue") }
        }
        doc.select("script:not([src])").forEach { script ->
            redirect.findAll(script.data().take(100_000)).forEach { add(it.groupValues[1], "Continue") }
        }
        if (found.isNotEmpty()) return found.values.toList()
        doc.select("iframe[src]").forEach { add(it.attr("src"), "Embedded player") }
        return found.values.toList()
    }

    fun normalized(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

class CatalogueRepository(
    private val fetcher: PageFetcher,
    private val domains: DomainProvider = BuiltInDomains
) {
    private data class CachedChoices(val links: List<Link>, val expiresAt: Long)
    private val choiceCache = mutableMapOf<String, CachedChoices>()

    private suspend fun paginatedChoices(first: Page, firstChoices: List<Link>, pages: List<String>): List<Link> {
        choiceCache[first.url]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.links }
        val found = linkedMapOf<String, Link>()
        firstChoices.forEach { found.putIfAbsent(it.url, it) }
        val pending = ArrayDeque<String>().apply { addAll(pages) }
        val visited = mutableSetOf(first.url)
        val unavailable = mutableListOf<String>()
        val original = first.url.toHttpUrl()
        val basePath = original.encodedPath.trimEnd('/')
        while (pending.isNotEmpty() && visited.size < 50) {
            coroutineContext.ensureActive()
            val url = pending.removeFirst()
            val parsed = url.toHttpUrl()
            val tail = parsed.encodedPath.removePrefix("$basePath/").trimEnd('/')
            val sameListing = parsed.encodedPath == original.encodedPath ||
                tail.matches(Regex("(?:page/)?\\d+"))
            if (url in visited || parsed.host != original.host || !sameListing) continue
            visited.add(url)
            val page = try { fetcher.fetch(url, first.url) } catch (_: java.io.IOException) {
                unavailable.add(url)
                continue
            }
            val doc = CatalogueParser.document(page)
            CatalogueParser.choices(doc).forEach { found.putIfAbsent(it.url, it) }
            CatalogueParser.pages(doc).filterNot { it in visited || it in pending }.forEach(pending::add)
        }
        if (unavailable.isEmpty() && pending.isEmpty()) {
            choiceCache[first.url] = CachedChoices(found.values.toList(), System.currentTimeMillis() + 15 * 60_000)
        } else {
            (unavailable + pending).distinct().forEach { url ->
                val parsed = url.toHttpUrl()
                val number = parsed.queryParameter("page") ?: parsed.pathSegments.lastOrNull().orEmpty()
                found.putIfAbsent(url, Link("Open episode page ${number.ifBlank { "?" }}", url, first.url))
            }
        }
        return found.values.toList()
    }

    suspend fun search(source: Source, query: String, year: String): SearchResult {
        val needle = CatalogueParser.normalized(query)
        require(needle.isNotBlank()) { "Enter a movie title." }
        require(year.isBlank() || year.matches(Regex("(19|20)\\d{2}"))) { "Enter a four-digit year." }
        require(year.isNotBlank() || needle.first() in 'a'..'z') {
            "Add a release year for titles beginning with a number or Tamil letter."
        }
        val homeUrl = domains.home(source)
        val home = try {
            fetcher.fetch(homeUrl, "")
        } catch (e: java.io.IOException) {
            val refreshedUrl = domains.home(source, forceRefresh = true)
            if (refreshedUrl == homeUrl) throw e
            fetcher.fetch(refreshedUrl, "")
        }
        val doc = CatalogueParser.document(home)
        val cataloguePath = if (source == Source.DUBBED) "/tamil-atoz-dubbed-movies/" else "/tamil-atoz-movies/"
        val root = CatalogueParser.links(doc, "a[href]").firstOrNull {
            it.url.toHttpUrl().encodedPath == cataloguePath
        }?.url ?: home.url
        val path = if (year.isNotBlank()) {
            if (source == Source.DUBBED) "/tamil-$year-dubbed-movies/" else "/tamil-$year-movies/"
        } else if (source == Source.DUBBED) "/tamil-atoz-dubbed-movies/${needle.first()}"
        else "/tamil-movies/${needle.first()}/"
        val start = root.toHttpUrl().resolve(path)!!.toString()
        val pending = ArrayDeque<String>().apply { add(start) }
        val visited = mutableSetOf<String>()
        val movies = linkedMapOf<String, Link>()
        var failed = 0
        while (pending.isNotEmpty() && visited.size < 80) {
            coroutineContext.ensureActive()
            val url = pending.removeFirst()
            if (!visited.add(url)) continue
            val page = try { fetcher.fetch(url, home.url) } catch (e: java.io.IOException) {
                if (visited.size == 1) throw e
                failed++
                continue
            }
            val listing = CatalogueParser.document(page)
            CatalogueParser.entries(listing).filter {
                CatalogueParser.normalized(it.title).contains(needle)
            }.forEach { movies[it.url] = it }
            CatalogueParser.pages(listing).filterNot { it in visited || it in pending }.forEach(pending::add)
        }
        val warning = when {
            pending.isNotEmpty() -> "Search reached its page limit. Add a year to narrow your search."
            failed > 0 -> "$failed catalogue pages could not be loaded. Retry to search all pages."
            else -> null
        }
        return SearchResult(movies.values.toList(), warning)
    }

    suspend fun resolve(start: Link): Resolution {
        var link = start
        val visited = mutableSetOf<String>()
        repeat(25) {
            coroutineContext.ensureActive()
            check(visited.add(link.url)) { "The source links form a loop. Try another version." }
            val page = fetcher.fetch(link.url, link.referer)
            val mime = page.mediaType.orEmpty().substringBefore(';').lowercase()
            if (mime.contains("mpegurl") || page.url.toHttpUrl().encodedPath.endsWith(".m3u8", true)) {
                error("This server offers HLS streaming only. Choose a regular movie file.")
            }
            val extension = page.url.toHttpUrl().encodedPath.substringAfterLast('.', "").lowercase()
            val fileMime = when (extension) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }
            val isMedia = mime.startsWith("video/") || mime in setOf("application/octet-stream", "application/mp4", "application/x-matroska") ||
                (mime !in setOf("text/html", "application/xhtml+xml", "text/plain") && extension in setOf("mp4", "m4v", "mkv", "webm", "avi", "mov"))
            if (isMedia) {
                return Resolution.File(link.copy(url = page.url), mime.ifBlank { fileMime }, page.disposition, page.bytes)
            }
            val doc = CatalogueParser.document(page)
            val firstChoices = CatalogueParser.choices(doc)
            val pages = CatalogueParser.pages(doc)
            val choices = (if (pages.isNotEmpty() && firstChoices.isNotEmpty())
                paginatedChoices(page, firstChoices, pages) else firstChoices).filterNot { it.url in visited }
            check(choices.isNotEmpty()) { "No download choices found. The source may be unavailable or its page layout has changed." }
            if (choices.size > 1) return Resolution.Choices(choices)
            link = choices.single()
        }
        error("Too many redirects between download pages. Try another version.")
    }
}
