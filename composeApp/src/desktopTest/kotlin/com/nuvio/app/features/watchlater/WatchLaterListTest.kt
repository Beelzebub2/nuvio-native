package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchLaterListTest {
    @Test fun `provider id changes still toggle the same saved episode`() {
        val disk = mutableMapOf<Int, String>()
        val list = WatchLaterList({ 1 }, { disk[it] }, { p, value -> disk[p] = value })
        val offline = MetaPreview("nativeairing:21", "series", "Anime",
            watchLaterEpisode = WatchLaterEpisode("nativeairing:21:7", "Episode 7", 1, 7))
        list.toggle(offline)
        val canonical = offline.copy(watchLaterEpisode = offline.watchLaterEpisode!!.copy(videoId = "kitsu:12:1:7"))
        val restarted = WatchLaterList({ 1 }, { disk[it] }, { p, value -> disk[p] = value })
        restarted.toggle(canonical)
        assertTrue(restarted.items.value.isEmpty(), "The same episode must be removed rather than duplicated")
        restarted.toggle(canonical)
        restarted.toggle(canonical.copy(watchLaterEpisode = canonical.watchLaterEpisode!!.copy(videoId = "kitsu:12:1:8", episodeNumber = 8)))
        assertEquals(2, restarted.items.value.size)
    }
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

    @Test fun `completion uses video identity when either episode number is unknown and writes only once`() {
        val disk = mutableMapOf<Int, String>()
        var writes = 0
        val list = WatchLaterList({ 1 }, { disk[it] }, { p, value -> writes++; disk[p] = value })
        val unknown = MetaPreview("series", "series", "Series", watchLaterEpisode = WatchLaterEpisode("unknown", "Unknown"))
        val known = unknown.copy(watchLaterEpisode = WatchLaterEpisode("known", "Known", 1, 3))
        list.toggle(unknown); list.toggle(known)
        val session = WatchProgressPlaybackSession(1, "series", "series", "series", "unknown", "Series", seasonNumber = 1, episodeNumber = 2)
        list.removeCompletedEpisode(session)
        assertEquals("known", list.items.value.single().watchLaterEpisode?.videoId)
        list.removeCompletedEpisode(session.copy(videoId = "unrelated", seasonNumber = null))
        assertEquals("known", list.items.value.single().watchLaterEpisode?.videoId)
        list.removeCompletedEpisode(session.copy(videoId = "known", seasonNumber = null, episodeNumber = 3))
        list.removeCompletedEpisode(session.copy(videoId = "known", seasonNumber = null, episodeNumber = 3))
        assertTrue(list.items.value.isEmpty())
        assertEquals(4, writes, "Only two additions and two actual removals should write")
    }

    @Test fun `completion invalidates a stale loaded profile without publishing into the newly active profile`() {
        var profile = 1
        var writes = 0
        val disk = mutableMapOf<Int, String>()
        val list = WatchLaterList({ profile }, { disk[it] }, { p, value -> writes++; disk[p] = value })
        list.toggle(MetaPreview("series", "series", "Series", watchLaterEpisode = WatchLaterEpisode("video", "Episode", 1, 1)))
        val session = WatchProgressPlaybackSession(1, "series", "series", "series", "video", "Series", seasonNumber = 1, episodeNumber = 1)
        profile = 2
        list.removeCompletedEpisode(session)
        list.removeCompletedEpisode(session)
        assertEquals(2, writes, "A stale loaded profile must not repeat the removal write")
        list.ensureLoaded()
        assertTrue(list.items.value.isEmpty())
        profile = 1
        list.ensureLoaded()
        assertTrue(list.items.value.isEmpty(), "Switching back must load the removal from disk")
    }

    @Test fun `blank video identities and conflicting partial episode numbers do not remove bookmarks`() {
        val list = WatchLaterList({ 1 }, { null }, { _, _ -> })
        val episodes = listOf(WatchLaterEpisode("", "Blank"),
            WatchLaterEpisode("season", "Other season", 2, null), WatchLaterEpisode("episode", "Other episode", null, 3))
        episodes.forEach { list.toggle(MetaPreview("series", "series", "Series", watchLaterEpisode = it)) }
        episodes.forEach { episode ->
            list.removeCompletedEpisode(WatchProgressPlaybackSession(1, "series", "series", "series", episode.videoId,
                "Series", seasonNumber = 1, episodeNumber = 2))
        }
        assertEquals(3, list.items.value.size)
    }
}
