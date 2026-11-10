package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceResultsFiltersTest {
    private fun source(
        name: String,
        provider: String = "Media Provider",
        seeders: Int? = null,
        sizeBytes: Long? = null,
        parsed: StreamClientResolveParsed? = null,
    ) = StreamItem(
        name = name,
        addonName = provider,
        addonId = provider,
        sourceName = "Releases",
        infoHash = "a".repeat(40),
        seeders = seeders,
        behaviorHints = StreamBehaviorHints(videoSize = sizeBytes),
        clientResolve = parsed?.let {
            StreamClientResolve(stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(parsed = it),
            ))
        },
    )

    @Test
    fun searchNormalizesCaseWhitespaceAndPunctuationAcrossProviderReleaseAndCodec() {
        val item = source(
            "Drama 2160p",
            provider = "Café Video",
            parsed = StreamClientResolveParsed(group = "Release-Team", codec = "H.265"),
        )
        val metadata = item.sourceFilterMetadata()
        assertEquals("café video release team", normalizeSourceSearch(" CAFÉ Video / Release-Team "))
        assertTrue(metadata.matches(SourceResultsFilters(search = "  CAFÉ / team  H265 ")))
        assertTrue(metadata.matches(SourceResultsFilters(search = " 2160P  café")))
        assertFalse(metadata.matches(SourceResultsFilters(search = "release x264")))
    }

    @Test
    fun metadataFiltersCombineAsIntersectionWithoutTreatingUnknownAsMatch() {
        val rich = source(
            "Film 1080p",
            seeders = 65,
            sizeBytes = 2 * SOURCE_GIB,
            parsed = StreamClientResolveParsed(
                hdr = listOf("HDR10+"),
                audio = listOf("Dolby Atmos"),
                channels = listOf("7.1"),
            ),
        )
        val filter = SourceResultsFilters(
            search = "media 1080p",
            quality = StreamQuality.FHD,
            minSeeders = 50,
            hdrOnly = true,
            audio = SourceAudioFilter.ATMOS,
            maxSizeBytes = 2 * SOURCE_GIB,
        )
        assertTrue(rich.sourceFilterMetadata().matches(filter))
        assertTrue(rich.sourceFilterMetadata().matches(filter.copy(audio = SourceAudioFilter.SURROUND)))
        assertFalse(rich.sourceFilterMetadata().matches(filter.copy(maxSizeBytes = SOURCE_GIB)))
        assertFalse(rich.sourceFilterMetadata().matches(filter.copy(minSeeders = 70)))
        assertFalse(rich.sourceFilterMetadata().matches(filter.copy(quality = StreamQuality.UHD)))

        val unknown = source("Film 1080p")
        assertFalse(unknown.sourceFilterMetadata().matches(filter.copy(search = "", hdrOnly = false, audio = null)))
        assertFalse(unknown.sourceFilterMetadata().matches(SourceResultsFilters(hdrOnly = true)))
        assertFalse(unknown.sourceFilterMetadata().matches(SourceResultsFilters(audio = SourceAudioFilter.ATMOS)))
        assertFalse(unknown.sourceFilterMetadata().matches(SourceResultsFilters(maxSizeBytes = 5 * SOURCE_GIB)))
    }

    @Test
    fun streamFilteringKeepsGroupOrderingLoadingErrorsAndOriginalSelectionObject() {
        val first = source("720p release", seeders = 10)
        val second = source("4K HDR Atmos", seeders = 85)
        val firstGroup = AddonStreamGroup("A", "a", listOf(first, second), isLoading = true)
        val secondGroup = AddonStreamGroup("B", "b", listOf(source("1080p")))
        val original = listOf(firstGroup, secondGroup)
        val filters = SourceResultsFilters(search = "hdr", minSeeders = 50)
        val filtered = filterStreamGroups(original, filters)

        assertEquals(listOf("a", "b"), filtered.map { it.addonId })
        assertTrue(filtered[0].isLoading)
        assertSame(second, filtered[0].streams.single())
        assertTrue(filtered[1].streams.isEmpty())
        assertEquals(2, firstGroup.streams.size)
        assertSame(original, filterStreamGroups(original, SourceResultsFilters()))
    }

    @Test
    fun seederAvailabilityOnlyUsesProviderValuesOrExplicitTorrentLabels() {
        val metadata = sourceFilterAvailability(listOf(
            source("1080p Seeders: 1,024", seeders = null).sourceFilterMetadata(),
            source("720p", seeders = null).sourceFilterMetadata(),
            source("4K", seeders = -10).sourceFilterMetadata(),
        ))
        assertEquals(1_024, metadata.maxSeeders)
        assertEquals(3, metadata.total)
        assertEquals(1, metadata.qualityCounts[StreamQuality.FHD])
        assertFalse(metadata.hasHdr)
        assertFalse(metadata.hasSize)
        assertFalse(metadata.hasAtmos)
    }
}
