package com.karthi.tamizhflix.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DomainConfigTest {
    @Test fun parsesUserConfigAndBuildsHomeUrls() {
        val config = DomainConfig.parse("""
            {"domains":{"moviesda":"https://moviesda.ski","isaidub":"https://isaidub.com"},
             "config_url":"https://example.com/ignored.json"}
        """)
        assertEquals("https://moviesda.ski/home.html", config.home(Source.TAMIL))
        assertEquals("https://isaidub.com/", config.home(Source.DUBBED))
    }

    @Test fun rejectsIncompleteOrUnsafeUpdates() {
        listOf(
            "{\"domains\":{\"moviesda\":\"https://new.example\"}}",
            "{\"domains\":{\"moviesda\":\"http://new.example\",\"isaidub\":\"https://other.example\"}}",
            "{\"domains\":{\"moviesda\":\"https://new.example/path\",\"isaidub\":\"https://other.example\"}}"
        ).forEach { json ->
            try { DomainConfig.parse(json); fail("Accepted invalid config: $json") }
            catch (_: RuntimeException) { }
        }
    }

    @Test fun searchUsesUpdatedDomainAndRetriesWhenOldHomeFails() = runBlocking {
        val domains = object : DomainProvider {
            override suspend fun home(source: Source, forceRefresh: Boolean) =
                if (forceRefresh) "https://new.example/home.html" else "https://old.example/home.html"
        }
        val calls = mutableListOf<String>()
        val repository = CatalogueRepository(PageFetcher { url, _ ->
            calls += url
            if (url.startsWith("https://old.example")) throw IOException("old domain unavailable")
            Page(url, "<div class=f><a href='/vikram/'>Vikram (2022)</a></div>")
        }, domains)
        assertEquals(1, repository.search(Source.TAMIL, "vikram", "").movies.size)
        assertEquals("https://old.example/home.html", calls[0])
        assertEquals("https://new.example/home.html", calls[1])
        assertEquals("https://new.example/tamil-movies/v/", calls[2])
    }
}
