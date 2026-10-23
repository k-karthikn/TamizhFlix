package com.karthi.tamizhflix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    private fun release(tag: String, url: String, draft: Boolean = false) = """
        {"tag_name":"$tag","name":"TamizhFlix 1.4.0","draft":$draft,"prerelease":false,
         "assets":[{"browser_download_url":"$url"}]}
    """

    @Test fun newerSemanticReleaseCandidateIsSelected() {
        val update = ReleaseParser.parse(release("v1.4.0",
            "https://github.com/k-karthikn/TamizhFlix/releases/download/v1.4.0/TamizhFlix-v1.4.0.apk"),
            AppVersion(1, 3, 2))
        assertEquals(AppVersion(1, 4, 0), update?.version)
        assertEquals("TamizhFlix 1.4.0", update?.name)
    }

    @Test fun sameOrLowerVersionNeverPrompts() {
        val json = release("v1.4.0", "https://github.com/k-karthikn/TamizhFlix/releases/download/v1.4.0/app.apk")
        assertNull(ReleaseParser.parse(json, AppVersion(1, 4, 0)))
        assertNull(ReleaseParser.parse(json, AppVersion(1, 5, 0)))
        assertNull(ReleaseParser.parse(json, AppVersion(2, 0, 0)))
    }

    @Test fun rejectsDraftAndUntrustedAssets() {
        assertNull(ReleaseParser.parse(release("v1.4.0",
            "https://github.com/k-karthikn/TamizhFlix/releases/download/v1.4.0/app.apk", true), AppVersion(1, 3, 2)))
        assertNull(ReleaseParser.parse(release("v1.4.0", "https://example.com/app.apk"), AppVersion(1, 3, 2)))
        assertNull(ReleaseParser.parse(release("v7",
            "https://github.com/k-karthikn/TamizhFlix/releases/download/v7/app.apk"), AppVersion(1, 3, 2)))
    }

    @Test fun comparesNumericPartsAndSupportsZeroMajor() {
        assertEquals(AppVersion(0, 1, 0), AppVersion.parse("0.1.0"))
        val json = release("v1.10.0",
            "https://github.com/k-karthikn/TamizhFlix/releases/download/v1.10.0/app.apk")
        assertEquals(AppVersion(1, 10, 0), ReleaseParser.parse(json, AppVersion(1, 9, 9))?.version)
        assertNull(ReleaseParser.parse(json, AppVersion(1, 11, 0)))
        assertNull(AppVersion.parse("1.04.0"))
    }
}
