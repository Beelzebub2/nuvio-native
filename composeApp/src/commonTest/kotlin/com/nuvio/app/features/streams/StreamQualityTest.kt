package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamQualityTest {
    private fun stream(name: String, description: String? = null, url: String? = "https://example.test/$name") =
        StreamItem(name = name, description = description, url = url, addonName = "Addon", addonId = "addon")

    @Test
    fun readsResolutionFromNameAndDescription() {
        assertEquals(StreamQuality.UHD, stream("Torrentio 4K").quality())
        assertEquals(StreamQuality.UHD, stream("Show", "Show.S01E04.2160p.WEB-DL").quality())
        assertEquals(StreamQuality.FHD, stream("Comet 1080p").quality())
        assertEquals(StreamQuality.HD, stream("Show.S01E04.720p.WEBRip").quality())
        assertEquals(StreamQuality.OTHER, stream("Direct link").quality())
        assertEquals(StreamQuality.OTHER, stream("Episode 10800").quality())
    }

    @Test
    fun groupsByResolutionBestFirstAndHonoursTheFilter() {
        val groups = listOf(
            AddonStreamGroup("A", "a", listOf(stream("720p"), stream("4k"))),
            AddonStreamGroup("B", "b", listOf(stream("1080p"), stream("cam"))),
        )
        val all = qualityGroups(groups, filter = null) { it.name }
        assertEquals(listOf("quality:UHD", "quality:FHD", "quality:HD", "quality:OTHER"), all.map { it.addonId })
        assertEquals("FHD · 1", all[1].addonName)

        val onlyHd = qualityGroups(groups, filter = StreamQuality.HD) { it.name }
        assertEquals(listOf("quality:HD"), onlyHd.map { it.addonId })
    }

    @Test
    fun bestMatchPrefersAutoPlayThenCachedThenQuality() {
        val sd = stream("cam")
        val uhd = stream("4k")
        val cachedHd = stream("720p").copy(
            debridCacheStatus = StreamDebridCacheStatus("rd", "Real-Debrid", StreamDebridCacheState.CACHED),
        )
        val groups = listOf(AddonStreamGroup("A", "a", listOf(sd, uhd, cachedHd)))

        assertEquals(sd, bestMatch(StreamsUiState(groups = groups, autoPlayStream = sd), debridEnabled = true)?.stream)
        val ready = bestMatch(StreamsUiState(groups = groups), debridEnabled = true)
        assertEquals(cachedHd, ready?.stream)
        assertEquals(BestMatchReason.ReadyToPlay, ready?.reason)

        val uncached = bestMatch(StreamsUiState(groups = listOf(AddonStreamGroup("A", "a", listOf(sd, uhd)))), debridEnabled = true)
        assertEquals(uhd, uncached?.stream)
        assertEquals(BestMatchReason.HighestQuality, uncached?.reason)

        assertNull(bestMatch(StreamsUiState(), debridEnabled = true))
    }
}
