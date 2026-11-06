package com.nuvio.app.features.collection

import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionRepositoryTest {
    @kotlin.test.Test
    fun `guest collections do not overwrite account ids and remain ordered`() {
        val account = listOf(Collection(id = "shared", title = "Account"))
        val guest = listOf(
            Collection(id = "shared", title = "Anonymous"),
            Collection(id = "new", title = "Guest only"),
        )

        val merged = mergeGuestCollections(account, guest)

        kotlin.test.assertEquals(listOf("shared", "new"), merged.map { it.id })
        kotlin.test.assertEquals("Account", merged.first().title)
    }
    @Test
    fun `deduplication keeps stable order and latest collection value`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Latest value"),
        )

        val result = collections.deduplicatedById()

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Latest value", result.first().title)
    }

    @Test
    fun `upsert replaces duplicate collection without changing its position`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Stale duplicate"),
        )

        val result = collections.upsertCollectionById(
            Collection(id = "duplicate", title = "Replacement"),
        )

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Replacement", result.first().title)
    }
}
