package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchLaterListTest {
    @Test fun `episodes of one anime are saved and removed independently across restarts`() {
        val disk = mutableMapOf<Int, String>()
        val list = WatchLaterList({ 1 }, { disk[it] }, { p, value -> disk[p] = value })
        val first = MetaPreview("anime", "series", "Anime", watchLaterEpisode = WatchLaterEpisode("episode-1", "First", 1, 1))
        val second = first.copy(watchLaterEpisode = WatchLaterEpisode("episode-2", "Second", 1, 2))
        list.toggle(first); list.toggle(second)
        val restored = WatchLaterList({ 1 }, { disk[it] }, { p, value -> disk[p] = value })
        restored.ensureLoaded()
        assertEquals(setOf("episode-1", "episode-2"), restored.items.value.map { it.watchLaterEpisode?.videoId }.toSet())
        restored.toggle(first)
        assertEquals("episode-2", restored.items.value.single().watchLaterEpisode?.videoId)
    }
    @Test fun `native anime identities and artwork survive a restart`() {
        val disk = mutableMapOf<Int, String>()
        val preview = MetaPreview("nativeairing:21", "series", "One Piece", poster = "https://example.test/poster.jpg")
        val original = WatchLaterList({ 1 }, { disk[it] }, { profile, payload -> disk[profile] = payload }, { 123L })
        original.toggle(preview)
        val restarted = WatchLaterList({ 1 }, { disk[it] }, { profile, payload -> disk[profile] = payload })
        restarted.ensureLoaded()
        assertEquals(preview.id, restarted.items.value.single().id)
        assertEquals(preview.poster, restarted.items.value.single().poster)
        assertEquals(123L, restarted.items.value.single().savedAtEpochMs)
    }

    @Test fun `profiles are separate and a second middle click removes only the matching type`() {
        var profile = 1
        val disk = mutableMapOf<Int, String>()
        val list = WatchLaterList({ profile }, { disk[it] }, { p, payload -> disk[p] = payload })
        val movie = MetaPreview("same-id", "movie", "Movie")
        val series = MetaPreview("same-id", "series", "Series")
        list.toggle(movie)
        list.toggle(series)
        list.toggle(movie)
        assertEquals(listOf("series"), list.items.value.map { it.type })
        profile = 2
        list.ensureLoaded()
        assertTrue(list.items.value.isEmpty())
        list.toggle(movie)
        profile = 1
        list.ensureLoaded()
        assertEquals(listOf("series"), list.items.value.map { it.type })
    }

    @Test fun `a failed disk write does not mark a poster as saved`() {
        val list = WatchLaterList({ 1 }, { null }, { _, _ -> throw java.io.IOException("Full disk") })
        assertFailsWith<java.io.IOException> { list.toggle(MetaPreview("id", "movie", "Movie")) }
        assertTrue(list.items.value.isEmpty())
    }
}
