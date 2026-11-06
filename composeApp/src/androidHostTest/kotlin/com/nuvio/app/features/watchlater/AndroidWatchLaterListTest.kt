package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidWatchLaterListTest {
    @Test fun itemsSurviveReloadAndStayIsolatedPerProfile() {
        val stored = mutableMapOf<Int, String>()
        var activeProfile = 1
        val preview = MetaPreview("tt300", "movie", "Movie")
        val list = WatchLaterList(
            profileId = { activeProfile },
            load = { stored[it] },
            save = { p, data -> stored[p] = data },
            now = { 123L },
        )
        list.toggle(preview)
        assertEquals(1, list.items.value.size)
        list.clearLocalState()
        list.ensureLoaded()
        assertEquals("Movie", list.items.value.single().name)
        activeProfile = 2
        list.ensureLoaded()
        assertTrue(list.items.value.isEmpty())
        activeProfile = 1
        list.ensureLoaded()
        assertEquals("Movie", list.items.value.single().name)
        list.toggle(preview)
        assertTrue(list.items.value.isEmpty())
    }

    @Test fun failedPersistenceDoesNotShowSavedItems() {
        val list = WatchLaterList({ 1 }, { null }, { _, _ -> error("Storage unavailable") })
        list.ensureLoaded()
        kotlin.test.assertFailsWith<IllegalStateException> {
            list.toggle(MetaPreview("tt123", "movie", "Movie"))
        }
        assertTrue(list.items.value.isEmpty())
    }

    @Test fun personalRatingsAndStatusesRemainPerProfile() {
        val data = mutableMapOf<Int, String>()
        var activeProfile = 1
        val list = PersonalMediaList(
            profileId = { activeProfile },
            load = { data[it] },
            save = { p, payload -> data[p] = payload },
            now = { 123L },
        )
        val preview = MetaPreview("tt123", "movie", "Movie")
        list.setStatus(preview, PersonalMediaStatus.COMPLETED)
        list.setRating(preview, 9)
        assertEquals(9, list.items.value.single().rating)
        activeProfile = 2
        list.ensureLoaded()
        assertTrue(list.items.value.isEmpty())
        activeProfile = 1
        list.ensureLoaded()
        assertEquals(PersonalMediaStatus.COMPLETED, list.items.value.single().status)
    }
}
