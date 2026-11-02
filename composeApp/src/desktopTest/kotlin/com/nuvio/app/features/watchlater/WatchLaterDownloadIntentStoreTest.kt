package com.nuvio.app.features.watchlater

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchLaterDownloadIntentStoreTest {
    @Test fun `intents persist across restarts and belong to one profile`() {
        val disk = mutableMapOf<Int, String>()
        fun store() = WatchLaterDownloadIntentStore({ disk[it] }, { id, payload -> disk[id] = payload })
        val movie = LibraryItem("tt10", "movie", "Movie", savedAtEpochMs = 101L)
        store().add(1, movie)
        store().add(1, movie)
        val restored = store()
        assertEquals(1, restored.items(1).size)
        assertTrue(restored.items(1).single().matches(movie))
        assertTrue(restored.items(2).isEmpty())

        restored.add(2, movie)
        restored.remove(1, movie)
        assertTrue(store().items(1).isEmpty())
        assertEquals(1, store().items(2).size)
    }

    @Test fun `generic series intent survives episode resolution but not a replaced bookmark`() {
        val disk = mutableMapOf<Int, String>()
        val store = WatchLaterDownloadIntentStore({ disk[it] }, { id, payload -> disk[id] = payload })
        val original = LibraryItem("series", "series", "Series", savedAtEpochMs = 100L)
        store.add(1, original)
        val episode = original.copy(watchLaterEpisode = WatchLaterEpisode("video", "Episode 1", 1, 1))
        val intent = store.items(1).single()
        assertTrue(intent.matches(episode))
        assertFalse(intent.matches(episode.copy(savedAtEpochMs = 200L)))
        store.remove(1, episode)
        assertTrue(store.items(1).isEmpty())
    }

    @Test fun `a new bookmark replaces the old intent for the same episode`() {
        val disk = mutableMapOf<Int, String>()
        val store = WatchLaterDownloadIntentStore({ disk[it] }, { id, payload -> disk[id] = payload })
        val episode = LibraryItem("series", "series", "Series", savedAtEpochMs = 5L,
            watchLaterEpisode = WatchLaterEpisode("first-provider", "Episode", 1, 2))
        store.add(1, episode)
        val newBookmark = episode.copy(savedAtEpochMs = 10L,
            watchLaterEpisode = episode.watchLaterEpisode!!.copy(videoId = "other-provider"))
        store.add(1, newBookmark)
        assertEquals(listOf(10L), store.items(1).map { it.savedAtEpochMs })
        assertTrue(store.items(1).single().matches(newBookmark))
    }

    @Test fun `intent survives a bookmark save error until it can be safely discarded`() {
        val pendingPayloads = mutableMapOf<Int, String>()
        val pending = WatchLaterDownloadIntentStore({ pendingPayloads[it] }, { id, payload -> pendingPayloads[id] = payload })
        val list = WatchLaterList({ 1 }, { null }, { _, _ -> error("disk full") })
        assertFailsWith<IllegalStateException> {
            list.toggle(MetaPreview("film", "movie", "Film"), beforeAdd = { pending.add(1, it) })
        }
        assertTrue(list.items.value.isEmpty())
        assertEquals(1, pending.items(1).size)
        pending.items(1).filterNot { intent -> list.items.value.any(intent::matches) }
            .forEach { pending.remove(1, it) }
        assertTrue(pending.items(1).isEmpty())
    }
}
