package com.karthi.tamizhflix.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CatalogueTest {
    private fun fixture(name: String, url: String) = CatalogueParser.document(Page(url,
        javaClass.getResource("/fixtures/$name")!!.readText()))
    @Test fun moviesdaListingExcludesNavigationAndKeepsPagination() {
        val doc = fixture("moviesda-list.html", "https://moviesda.ski/tamil-movies/v/")
        assertEquals(20, CatalogueParser.entries(doc).size)
        assertEquals(11, CatalogueParser.pages(doc).size)
        assertTrue(CatalogueParser.entries(doc).all { it.url.startsWith("https://moviesda.ski/") })
    }
    @Test fun dubbedListingUsesPathPagination() {
        val doc = fixture("isaidub-list.html", "https://isaidub.green/tamil-atoz-dubbed-movies/v")
        assertEquals(20, CatalogueParser.entries(doc).size)
        assertEquals(listOf("https://isaidub.green/tamil-atoz-dubbed-movies/v/2", "https://isaidub.green/tamil-atoz-dubbed-movies/v/3"), CatalogueParser.pages(doc))
    }
    @Test fun retainsEveryFileChoice() {
        val doc = fixture("qualities.html", "https://moviesda.ski/varavu-720p-hd-movie/")
        assertEquals(2, CatalogueParser.choices(doc).size)
        assertTrue(CatalogueParser.choices(doc).first().title.contains("850 MB"))
    }
    @Test fun resolvesRelativeLinksAgainstPageAndRejectsUnsafeSchemes() {
        val doc = CatalogueParser.document(Page("https://example.com/files/page/", """
            <div class=dlink><a href='../movie'>Movie</a><a href='javascript:bad()'>Bad</a>
            <a href='http://example.com/bad'>Insecure</a><a href='#'>Empty</a></div>
        """))
        assertEquals(listOf(Link("Movie", "https://example.com/files/movie", "https://example.com/files/page/")), CatalogueParser.choices(doc))
    }
    @Test fun discoversDubbedCatalogueHost() = runBlocking {
        val urls = mutableListOf<String>()
        val home = javaClass.getResource("/fixtures/isaidub-home.html")!!.readText()
        val repo = CatalogueRepository(PageFetcher { url, _ ->
            urls.add(url)
            Page(url, if (url == Source.DUBBED.home) home else "<div class=f><a href='/movie/virus/'>Virus (1999)</a></div>")
        })
        assertEquals(1, repo.search(Source.DUBBED, "virus", "").movies.size)
        assertEquals("https://isaidub.green/tamil-atoz-dubbed-movies/v", urls[1])
    }
    @Test fun followsLaterPaginationAndDeduplicatesMovies() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = CatalogueRepository(PageFetcher { url, _ ->
            calls.add(url)
            val next = when { url.endsWith("/home.html") -> ""; url.contains("page=2") -> ""; else -> "<div class=pagination><a href='?page=2'>2</a></div>" }
            Page(url, "<div class=f><a href='/vikram/'>Vikram (2022)</a></div>$next")
        })
        assertEquals(1, repo.search(Source.TAMIL, "Vikram", "").movies.size)
        assertEquals(3, calls.size)
    }
    @Test fun partialFailureIsReported() = runBlocking {
        val repo = CatalogueRepository(PageFetcher { url, _ ->
            if (url.contains("page=2")) throw IOException("Offline")
            Page(url, "<div class=pagination><a href='?page=2'>2</a></div>")
        })
        assertNotNull(repo.search(Source.TAMIL, "Vikram", "").warning)
    }
    @Test fun numberedTitlesNeedYear() = runBlocking {
        val repo = CatalogueRepository(PageFetcher { _, _ -> error("Must not fetch") })
        try { repo.search(Source.TAMIL, "96", ""); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("year"))
        }
    }
    @Test fun resolverFollowsSingleLinksToVerifiedMedia() = runBlocking {
        val repo = CatalogueRepository(PageFetcher { url, _ ->
            if (url.endsWith("movie")) Page(url, mediaType = "video/mp4", bytes = 42)
            else Page(url, "<div class=dlink><a href='/movie'>Server</a></div>")
        })
        val file = repo.resolve(Link("Title", "https://example.com/start")) as Resolution.File
        assertEquals("https://example.com/start", file.link.referer)
        assertEquals(42L, file.bytes)
    }
    @Test fun resolverDoesNotMistakeHtmlForMediaBasedOnUrl() = runBlocking {
        val repo = CatalogueRepository(PageFetcher { url, _ -> Page(url, "<p>Error</p>", "text/html") })
        try { repo.resolve(Link("Title", "https://example.com/movie.mp4")); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("No download choices")) }
    }
    @Test fun hlsIsNotSavedAsMovie() = runBlocking {
        val repo = CatalogueRepository(PageFetcher { url, _ -> Page(url, mediaType = "application/vnd.apple.mpegurl") })
        try { repo.resolve(Link("Title", "https://example.com/stream")); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("HLS")) }
    }
    @Test fun hidesStreamingAndDuplicateServers() {
        val doc = fixture("servers.html", "https://movies.downloadpage.xyz/download/page/101643")
        val choices = CatalogueParser.choices(doc)
        assertEquals(1, choices.size)
        assertEquals("Download Server 1", choices.single().title)
    }
    @Test fun cyclicResolverStops() = runBlocking {
        var calls = 0
        val repo = CatalogueRepository(PageFetcher { url, _ ->
            calls++
            Page(url, "<div class=dlink><a href='/start'>Loop</a></div>")
        })
        try { repo.resolve(Link("Title", "https://example.com/start")); fail() }
        catch (_: IllegalStateException) { assertEquals(1, calls) }
    }
}
