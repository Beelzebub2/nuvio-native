package com.nuvio.app.features.remote

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.BestMatchReason
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.SourceResultsFilters
import com.nuvio.app.features.streams.SourceAudioFilter
import com.nuvio.app.features.streams.SOURCE_GIB
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class RemoteSourceCatalogTest {
    private fun source(
        url: String? = null,
        externalUrl: String? = null,
        infoHash: String? = null,
        name: String = "1080p",
        addonName: String = "Provider",
    ) = StreamItem(
        url = url, externalUrl = externalUrl, infoHash = infoHash,
        name = name, addonName = addonName, addonId = "test",
    )

    @Test
    fun neverOfferArbitraryLocalPathExternalLinksOrDisabledTorrents() {
        assertTrue(source(url = "https://media.example/video.m3u8").isEligibleForRemotePlayback(false, false))
        assertFalse(source(url = "file:///private/movie.mp4").isEligibleForRemotePlayback(true, false))
        assertFalse(source(url = "javascript:alert(1)").isEligibleForRemotePlayback(true, false))
        assertFalse(source(externalUrl = "https://example.org/account").isEligibleForRemotePlayback(true, true))
        val torrent = source(infoHash = "a".repeat(40))
        assertFalse(torrent.isEligibleForRemotePlayback(false, false))
        assertTrue(torrent.isEligibleForRemotePlayback(true, false))
    }

    @Test
    fun sourceLabelsStripUntrustedLinksControlsAndCredentials() {
        val stream = source(
            url = "https://media.example/video",
            addonName = "Provider https://private.example/key",
            name = "1080p\nBearer:secret magnet:?xt=urn:btih:abc",
        ).copy(behaviorHints = StreamBehaviorHints(videoSize = 5L * 1024 * 1024 * 1024))
        val result = stream.remoteSourceLabel()
        assertTrue(result.contains("1080p"))
        assertTrue(result.contains("5.0 GiB"))
        assertFalse(result.contains("private.example"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("magnet:"))
        assertFalse(result.contains("\n"))
        assertTrue(result.length <= 150)
    }

    @Test
    fun sourceMetadataIsBasedOnResolutionAndActualSeedsOnly() {
        val torrent = source(infoHash = "a".repeat(40), name = "4K | Seeders: 1,024")
            .copy(seeders = 72, peers = 9, debridCacheStatus = StreamDebridCacheStatus(
                "resolver", "Debrid", StreamDebridCacheState.CACHED,
            ))
        val choice = torrent.remoteSourceChoice("opaque-1", BestMatchReason.ReadyToPlay)
        assertEquals("4K", choice.quality)
        assertEquals(5, choice.qualityStars)
        assertEquals(72, choice.seeders)
        assertEquals(9, choice.peers)
        assertTrue(choice.cached)
        assertTrue(choice.recommended)
        assertEquals("ReadyToPlay", choice.recommendationReason)
        assertEquals(1024, torrent.copy(seeders = null).remoteSeederCount())

        val sourceWithoutSeeders = source(url = "https://media.example/movie.mp4", name = "1080p")
        assertEquals(4, sourceWithoutSeeders.remoteSourceChoice("opaque-2").qualityStars)
        assertNull(sourceWithoutSeeders.remoteSeederCount())
        assertEquals(3, source(url = "https://media.example/720", name = "720p")
            .remoteSourceChoice("opaque-3").qualityStars)
        assertNull(source(url = "https://media.example/no-quality", name = "Direct")
            .remoteSourceChoice("opaque-4").qualityStars)
        assertNull(source(infoHash = "b".repeat(40), name = "2160p")
            .copy(seeders = -7).remoteSeederCount())
    }

    @Test
    fun addonSeederMetadataIsPreservedWithoutInventingAvailability() {
        val payload = """{"streams":[
            {"name":"1080p","infoHash":"${"a".repeat(40)}","seeders":52,"peers":8},
            {"name":"720p","infoHash":"${"b".repeat(40)}"},
            {"name":"4K","infoHash":"${"c".repeat(40)}","seeders":-10}
        ]}"""
        val parsed = StreamParser.parse(payload, "Provider", "addon:provider")
        assertEquals(52, parsed[0].remoteSeederCount())
        assertEquals(8, parsed[0].remoteSourceChoice("opaque").peers)
        assertNull(parsed[1].remoteSeederCount())
        assertNull(parsed[2].remoteSeederCount())
    }

    @Test
    fun desktopBestMatchIsKeptWithinBoundedRemoteResults() {
        val streams = (0 until 40).map { index ->
            source(url = "https://media.example/$index", name = if (index == 39) "4K" else "720p")
        }
        val state = StreamsUiState(groups = listOf(AddonStreamGroup("Provider", "provider", streams)))
        val selection = recommendedRemoteSources(state, streams, debridEnabled = false)
        assertEquals(36, selection.streams.size)
        assertEquals(streams[39], selection.streams.first())
        assertEquals(BestMatchReason.HighestQuality, selection.best?.reason)

        val autoplay = state.copy(autoPlayStream = streams[5])
        val preferred = recommendedRemoteSources(autoplay, streams, debridEnabled = false)
        assertEquals(streams[5], preferred.best?.stream)
        assertEquals(BestMatchReason.AutoPlayRules, preferred.best?.reason)
        val excluded = recommendedRemoteSources(autoplay, streams.drop(6), debridEnabled = false)
        assertFalse(excluded.streams.contains(streams[5]))
        assertEquals(streams[39], excluded.best?.stream)
    }

    @Test
    fun remoteFilteringRetainsRecommendedHandleAndSelectionIdentity() {
        val best = source(infoHash = "a".repeat(40), name = "4K HDR10 Atmos")
            .copy(seeders = 85, behaviorHints = StreamBehaviorHints(videoSize = 2 * SOURCE_GIB))
            .remoteSourceChoice("opaque-top", BestMatchReason.HighestQuality)
        val other = source(infoHash = "b".repeat(40), name = "720p")
            .remoteSourceChoice("opaque-low")
        val filters = SourceResultsFilters(search = "provider 4k", minSeeders = 50, hdrOnly = true,
            audio = SourceAudioFilter.ATMOS, maxSizeBytes = 2 * SOURCE_GIB)
        val result = filterRemoteSourceChoices(listOf(best, other), filters)
        assertEquals(listOf("opaque-top"), result.map { it.id })
        assertSame(best, result.single())
        assertTrue(result.single().recommended)
        assertEquals(2, filterRemoteSourceChoices(listOf(best, other), SourceResultsFilters()).size)
        assertTrue(filterRemoteSourceChoices(listOf(best, other), filters.copy(search = "does-not-exist")).isEmpty())
    }

    @Test
    fun optionalRemoteMetadataSupportsReleaseCodecAudioSizeAndLegacyPayloads() {
        val stream = source(name = "1080p source", infoHash = "a".repeat(40))
            .copy(
                behaviorHints = StreamBehaviorHints(videoSize = 3 * SOURCE_GIB),
                clientResolve = StreamClientResolve(stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(parsed = StreamClientResolveParsed(
                        group = "Release-Group https://private.example/secret",
                        codec = "HEVC",
                        hdr = listOf("Dolby Vision"),
                        audio = listOf("Atmos"),
                    )),
                )),
            )
        val choice = stream.remoteSourceChoice("safe-id")
        assertEquals("HEVC", choice.codec)
        assertTrue(choice.hdr)
        assertEquals(3 * SOURCE_GIB, choice.sizeBytes)
        assertFalse(choice.release.orEmpty().contains("private.example"))
        assertTrue(choice.sourceFilterMetadata().matches(SourceResultsFilters(search = "release hevc")))
        assertTrue(choice.sourceFilterMetadata().matches(SourceResultsFilters(audio = SourceAudioFilter.ATMOS)))
        assertTrue(remoteSourceFilterAvailability(listOf(choice)).hasSize)

        val legacy = Json.decodeFromString<RemoteChoice>(
            """{"id":"old-pc","label":"Provider 720p","quality":"720p"}""",
        )
        assertNull(legacy.codec)
        assertNull(legacy.sizeBytes)
        assertFalse(legacy.hdr)
        assertEquals("old-pc",
            filterRemoteSourceChoices(listOf(legacy), SourceResultsFilters(search = "provider 720p")).single().id)
    }
}
