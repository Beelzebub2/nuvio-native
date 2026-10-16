package com.nuvio.app.features.watchlater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeSynopsisTextTest {
    @Test fun `synopsis removes markup and collapses messy whitespace`() {
        assertEquals(
            "Born into a family of swordsmen. A second & final chance.",
            readableSynopsis("<p>Born into a family of swordsmen.</p>\n<br>A second &amp; final chance."),
        )
    }

    @Test fun `synopsis is safely bounded for a compact hover card`() {
        val summary = readableSynopsis("Story ".repeat(1000))
        assertTrue(summary.length <= 1600)
        assertEquals("", readableSynopsis(null))
    }
}
