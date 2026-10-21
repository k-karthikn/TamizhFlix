package com.karthi.tamizhflix.data

import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.coroutineContext

enum class Source(val label: String, val home: String) {
    TAMIL("Tamil", "https://moviesda.ski/home.html"),
    DUBBED("Tamil dubbed", "https://isaiidub.com/")
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

    fun links(doc: Document, selector: String): List<Link> =
        doc.select(selector).mapNotNull { element ->
            val raw = element.attr("href")
            if (raw.isBlank() || raw.startsWith("#")) return@mapNotNull null
            val url = element.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
            val title = element.text().trim()
            if (title.isBlank()) null else Link(title, url.newBuilder().fragment(null).build().toString(), doc.location())
        }.distinctBy { it.url }

    fun entries(doc: Document): List<Link> =
        links(doc, ".f a[href], .folder a[href]").filterNot {
            it.title.matches(Regex("(?i)([a-z]|\\d+|Tamil \\d{4}( Dubbed)? Movies)")) ||
                it.url.toHttpUrl().encodedPath == "/"
        }

    fun pages(doc: Document): List<String> =
        links(doc, ".pagination a[href], .pagecontent a[href], a[rel=next]")
            .map { it.url }.filter { it.toHttpUrl().host == doc.location().toHttpUrl().host }

    fun choices(doc: Document): List<Link> {
        val downloads = links(doc, ".dlink a[href], a.coral[href]").filterNot {
            it.title.contains("watch", ignoreCase = true) || it.title.contains("play online", ignoreCase = true)
        }
        return downloads.ifEmpty { entries(doc) }
    }

    fun normalized(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

class CatalogueRepository(private val fetcher: PageFetcher) {
    suspend fun search(source: Source, query: String, year: String): SearchResult {
        val needle = CatalogueParser.normalized(query)
        require(needle.isNotBlank()) { "Enter a movie title." }
        require(year.isBlank() || year.matches(Regex("(19|20)\\d{2}"))) { "Enter a four-digit year." }
        require(year.isNotBlank() || needle.first() in 'a'..'z') {
            "Add a release year for titles beginning with a number or Tamil letter."
        }
        val home = fetcher.fetch(source.home, "")
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
        repeat(15) {
            coroutineContext.ensureActive()
            check(visited.add(link.url)) { "The source links form a loop. Try another version." }
            val page = fetcher.fetch(link.url, link.referer)
            val mime = page.mediaType.orEmpty().substringBefore(';').lowercase()
            if (mime.contains("mpegurl") || page.url.toHttpUrl().encodedPath.endsWith(".m3u8", true)) {
                error("This server offers HLS streaming only. Choose a regular movie file.")
            }
            if (mime.startsWith("video/") || mime == "application/octet-stream" ||
                mime == "application/mp4") {
                return Resolution.File(link.copy(url = page.url), mime, page.disposition, page.bytes)
            }
            val choices = CatalogueParser.choices(CatalogueParser.document(page))
                .filterNot { it.url in visited }
            check(choices.isNotEmpty()) { "No download choices found. The source may be unavailable or its page layout has changed." }
            if (choices.size > 1) return Resolution.Choices(choices)
            link = choices.single()
        }
        error("Too many redirects between download pages. Try another version.")
    }
}
