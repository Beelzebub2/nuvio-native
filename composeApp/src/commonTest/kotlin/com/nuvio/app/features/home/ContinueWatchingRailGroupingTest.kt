package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingRailPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingRailGroupingTest {
    private fun item(id: String, type: String, category: String? = null) = ContinueWatchingItem(
        parentMetaId = id,
        parentMetaType = type,
        videoId = "$id:1",
        title = id,
        subtitle = "",
        imageUrl = null,
        resumePositionMs = 30_000L,
        durationMs = 60_000L,
        progressFraction = 0.5f,
        mediaCategory = category,
    )

    @Test
    fun `existing installations keep one combined row by default`() {
        val preferences = ContinueWatchingPreferencesUiState()
        assertFalse(preferences.separateRowsByType)
        assertFalse(preferences.hideLiveContent)
        val items = listOf(item("series", "series"), item("film", "movie"))
        val rails = buildContinueWatchingRails(
            items,
            preferences.separateRowsByType,
            preferences.hideLiveContent,
            preferences.railPriority,
        )
        assertEquals(listOf(ContinueWatchingRailKind.COMBINED), rails.map { it.kind })
        assertEquals(items, rails.single().items)
    }

    @Test
    fun `grouped rails keep all items and stable relative order`() {
        val items = listOf(
            item("show1", "series"), item("film1", "movie"), item("anime1", "anime"),
            item("show2", "tv"), item("unknown", "book"), item("film2", "MOVIE"),
        )
        val rails = buildContinueWatchingRails(items, separateRowsByType = true, hideLiveContent = false)
        assertEquals(
            listOf(
                ContinueWatchingRailKind.MOVIES, ContinueWatchingRailKind.SERIES,
                ContinueWatchingRailKind.ANIME, ContinueWatchingRailKind.OTHER,
            ),
            rails.map { it.kind },
        )
        assertEquals(listOf("film1", "film2"), rails[0].items.map { it.title })
        assertEquals(listOf("show1", "show2"), rails[1].items.map { it.title })
        assertEquals(items.size, rails.sumOf { it.items.size })
    }

    @Test
    fun `priority is first nonempty group and empty groups are absent`() {
        val rails = buildContinueWatchingRails(
            listOf(item("one", "series"), item("two", "anime")),
            separateRowsByType = true,
            hideLiveContent = false,
            priority = ContinueWatchingRailPriority.ANIME,
        )
        assertEquals(
            listOf(ContinueWatchingRailKind.ANIME, ContinueWatchingRailKind.SERIES),
            rails.map { it.kind },
        )
    }

    @Test
    fun `hide live matches only explicit types and does not mistake tv series`() {
        val items = listOf(
            item("live-channel", "live-tv"), item("series", "tv"),
            item("podcast", "channel"), item("film", "movie"),
        )
        val rails = buildContinueWatchingRails(items, separateRowsByType = false, hideLiveContent = true)
        assertEquals(listOf("series", "film"), rails.single().items.map { it.title })
        assertTrue(item("broadcast", "iptv").isExplicitLiveContent())
        assertFalse(item("regular", "tv").isExplicitLiveContent())
        assertTrue(buildContinueWatchingRails(listOf(item("only", "channel")), true, true).isEmpty())
    }

    @Test
    fun `anime library provenance separates series and movies without altering playback types`() {
        val items = listOf(
            item("anime-provider", "series", "anime"),
            item("anime-library", "movie"),
            item("regular-show", "tv"),
            item("regular-film", "movie"),
        )
        val rails = buildContinueWatchingRails(
            items, separateRowsByType = true, hideLiveContent = false,
            animeLibraryIds = setOf("anime-library"),
        )
        assertEquals(listOf("anime-provider", "anime-library"),
            rails.single { it.kind == ContinueWatchingRailKind.ANIME }.items.map { it.parentMetaId })
        assertEquals(listOf("regular-show"),
            rails.single { it.kind == ContinueWatchingRailKind.SERIES }.items.map { it.parentMetaId })
        assertEquals(listOf("regular-film"),
            rails.single { it.kind == ContinueWatchingRailKind.MOVIES }.items.map { it.parentMetaId })
    }
}
