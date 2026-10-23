package com.karthi.tamizhflix.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in smoke checks. Only fetches HTML and media headers; never saves movie bytes. */
class LiveCatalogueTest {
    @Test fun tamilSearchAndResolver() = checkSource(Source.TAMIL, "Varavu")
    @Test fun dubbedSearchAndResolver() = checkSource(Source.DUBBED, "Virus")
    @Test fun vikramOnDutyIncludesLaterEpisodePages() = runBlocking {
        assumeTrue(System.getenv("TAMIZHFLIX_LIVE_TEST") == "1")
        val repo = CatalogueRepository(HttpPageFetcher())
        val result = repo.resolve(Link("Vikram On Duty", "https://moviesda.ski/vikram-on-duty-2026-tamil-web-series/"))
        assertTrue("Expected paginated episode choices", result is Resolution.Choices && result.links.size > 20)
    }
    private fun checkSource(source: Source, title: String) = runBlocking {
        assumeTrue(System.getenv("TAMIZHFLIX_LIVE_TEST") == "1")
        val repo = CatalogueRepository(HttpPageFetcher())
        val results = repo.search(source, title, "")
        assertTrue("Expected search results for $source", results.movies.isNotEmpty())
        var link = results.movies.first()
        repeat(12) {
            when (val resolution = repo.resolve(link)) {
                is Resolution.Choices -> link = resolution.links.first()
                is Resolution.File -> {
                    assertTrue(resolution.link.url.startsWith("https://"))
                    return@runBlocking
                }
            }
        }
        error("Did not reach a file for $source")
    }
}
