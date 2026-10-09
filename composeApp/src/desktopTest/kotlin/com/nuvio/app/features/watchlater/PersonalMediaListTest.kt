package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.*

class PersonalMediaListTest {
    @Test fun statusAndRatingSurviveRestartAndRemainSeparateForEachProfile() {
        var profile = 1
        val disk = mutableMapOf<Int, String>()
        fun list() = PersonalMediaList({ profile }, { disk[it] }, { p, value -> disk[p] = value })
        val item = MetaPreview("anime", "series", "Anime")
        val original = list()
        original.setStatus(item, PersonalMediaStatus.WATCHING)
        original.setRating(item, 8)
        val restored = list(); restored.ensureLoaded()
        assertEquals(PersonalMediaStatus.WATCHING, restored.items.value.single().status)
        assertEquals(8, restored.items.value.single().rating)
        profile = 2; restored.ensureLoaded(); assertTrue(restored.items.value.isEmpty())
        restored.setStatus(item, PersonalMediaStatus.DROPPED)
        profile = 1; restored.ensureLoaded(); assertEquals(8, restored.items.value.single().rating)
        restored.setStatus(item, null); assertEquals(8, restored.items.value.single().rating)
        restored.setRating(item, null); assertTrue(restored.items.value.isEmpty())
    }
    @Test fun invalidRatingsAndFailedWritesDoNotPublishChanges() {
        val list = PersonalMediaList({ 1 }, { null }, { _, _ -> throw java.io.IOException("Full disk") })
        val item = MetaPreview("anime", "series", "Anime")
        assertFailsWith<IllegalArgumentException> { list.setRating(item, 11) }
        assertFailsWith<java.io.IOException> { list.setStatus(item, PersonalMediaStatus.COMPLETED) }
        assertTrue(list.items.value.isEmpty())
    }
}
