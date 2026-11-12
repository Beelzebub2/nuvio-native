package com.nuvio.app.features.watched

import com.nuvio.app.features.watching.sync.WatchedSyncAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedNuvioOutboxTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun failedOfflineDeleteSurvivesRestartAndIsRetriedBeforePendingMarks() = runBlocking {
        val removedItem = item("removed", 10L)
        val untouchedItem = item("untouched", 20L)
        val removedKey = key(removedItem)
        val untouchedKey = key(untouchedItem)
        val original = storeWithDelete(removedItem)
        original.update { nuvio, _, dirty, _ ->
            nuvio[untouchedKey] = untouchedItem
            dirty += untouchedKey
        }

        // Disk state was written before the network attempt. Restoring it must
        // retain the deletion even though there is no corresponding local item.
        val saved = json.encodeToString(
            original.readWithPendingDeletes { nuvio, _, dirty, _, deletes ->
                StoredWatchedPayload(
                    items = nuvio.values.toList(),
                    dirtyWatchedKeys = dirty.toSet(),
                    pendingDeletedItems = deletes.values.toList(),
                )
            },
        )
        val reloaded = json.decodeFromString<StoredWatchedPayload>(saved)
        val store = WatchedItemsStore().apply {
            updateWithPendingDeletes { nuvio, _, dirty, _, deletes ->
                nuvio.putAll(reloaded.items.associateBy(::key))
                dirty.addAll(reloaded.dirtyWatchedKeys)
                deletes.putAll(reloaded.pendingDeletedItems.associateBy(::key))
            }
        }
        val remote = RecordingAdapter(listOf(removedItem))
        remote.failDelete = true

        assertFailsWith<IllegalStateException> { flush(store, remote) }
        assertEquals(listOf(removedKey), store.pendingDeletes().map(::key))
        assertTrue(remote.pushes.isEmpty(), "A failed removal must block later upserts")
        val merged = mergeWatchedSnapshot(
            serverItems = remote.pull(1, 900),
            localItems = reloaded.items,
            dirtyKeys = reloaded.dirtyWatchedKeys,
            pendingDeleteKeys = pendingWatchedDeleteKeys(store.pendingDeletes()),
        )
        assertFalse(removedKey in merged.items, "Offline removal cannot be restored from the server")
        assertTrue(untouchedKey in merged.items)

        remote.failDelete = false
        val persisted = mutableListOf<StoredWatchedPayload>()
        assertTrue(flush(store, remote, onPersist = {
            persisted += store.readWithPendingDeletes { nuvio, _, dirty, _, deletes ->
                StoredWatchedPayload(
                    items = nuvio.values.toList(),
                    dirtyWatchedKeys = dirty.toSet(),
                    pendingDeletedItems = deletes.values.toList(),
                )
            }
        }))
        assertEquals(emptyList(), store.pendingDeletes())
        assertFalse(removedKey in remote.items)
        assertTrue(untouchedKey in remote.items)
        assertEquals(listOf("delete", "delete", "push"), remote.operations)
        assertTrue(persisted.isNotEmpty())
        assertTrue(persisted.last().pendingDeletedItems.isEmpty())
        assertTrue(persisted.last().dirtyWatchedKeys.isEmpty())
    }

    @Test
    fun readdDuringInFlightDeleteWinsAndIsUploadedAfterRemoval() = runBlocking {
        val removed = item("episode", 100L, season = 2, episode = 3)
        val addedAgain = removed.copy(markedAtEpochMs = 200L)
        val key = key(removed)
        val store = storeWithDelete(removed)
        val remote = RecordingAdapter(listOf(removed))
        remote.onDelete = {
            store.updateWithPendingDeletes { nuvio, _, dirty, _, deletes ->
                deletes.remove(key)
                nuvio[key] = addedAgain
                dirty += key
            }
        }

        assertTrue(flush(store, remote))
        assertEquals(listOf("delete", "push"), remote.operations)
        assertEquals(addedAgain, remote.items[key])
        assertEquals(emptyList(), store.pendingDeletes())
        assertEquals(emptySet(), store.read { _, _, dirty, _ -> dirty.toSet() })
    }

    @Test
    fun accountChangeDuringDeleteDoesNotAcknowledgeStaleProfile() = runBlocking {
        val removed = item("different-profile", 100L)
        val store = storeWithDelete(removed)
        val remote = RecordingAdapter(listOf(removed))
        var authenticatedProfile = true
        remote.onDelete = { authenticatedProfile = false }

        assertFalse(flush(store, remote, isCurrent = { authenticatedProfile }))
        assertEquals(listOf(key(removed)), store.pendingDeletes().map(::key))
        authenticatedProfile = true
        remote.onDelete = null
        assertTrue(flush(store, remote, isCurrent = { authenticatedProfile }))
        assertEquals(emptyList(), store.pendingDeletes())
    }

    @Test
    fun successfulDeleteIsAcknowledgedEvenWhenFollowingMarkUploadFails() = runBlocking {
        val deletedItem = item("removed", 100L)
        val pendingMark = item("pending-mark", 200L)
        val store = storeWithDelete(deletedItem)
        store.update { nuvio, _, dirty, _ ->
            nuvio[key(pendingMark)] = pendingMark
            dirty += key(pendingMark)
        }
        val remote = RecordingAdapter(listOf(deletedItem))
        remote.failPush = true
        var persistCount = 0

        assertFailsWith<IllegalStateException> {
            flush(store, remote, onPersist = { persistCount += 1 })
        }
        assertEquals(emptyList(), store.pendingDeletes())
        assertEquals(setOf(key(pendingMark)), store.read { _, _, dirty, _ -> dirty.toSet() })
        assertEquals(1, persistCount, "The successful deletion should be durable before the push")

        remote.failPush = false
        assertTrue(flush(store, remote))
        assertEquals(listOf("delete", "push", "push"), remote.operations)
        assertEquals(pendingMark, remote.items[key(pendingMark)])
    }

    private fun storeWithDelete(item: WatchedItem): WatchedItemsStore = WatchedItemsStore().apply {
        updateWithPendingDeletes { nuvio, _, dirty, _, deletes ->
            nuvio[key(item)] = item
            deletes[key(item)] = requireNotNull(nuvio.remove(key(item))).toNuvioDeleteIntent()
            dirty.remove(key(item))
        }
    }

    private fun WatchedItemsStore.pendingDeletes(): List<WatchedItem> =
        readWithPendingDeletes { _, _, _, _, deletes -> deletes.values.toList() }

    private suspend fun flush(
        store: WatchedItemsStore,
        remote: RecordingAdapter,
        isCurrent: () -> Boolean = { true },
        onPersist: () -> Unit = {},
    ): Boolean = flushWatchedNuvioOutbox(
        profileId = 1,
        store = store,
        adapter = remote,
        isCurrent = isCurrent,
        persist = onPersist,
        acknowledgePush = { pushed ->
            store.update { nuvio, _, dirty, _ ->
                val remaining = acknowledgeSuccessfulWatchedPush(nuvio, dirty, pushed)
                dirty.clear()
                dirty += remaining
            }
            onPersist()
        },
    )

    private class RecordingAdapter(initial: Collection<WatchedItem>) : WatchedSyncAdapter {
        val items = initial.associateBy(::key).toMutableMap()
        val pushes = mutableListOf<WatchedItem>()
        val operations = mutableListOf<String>()
        var failDelete = false
        var failPush = false
        var onDelete: (() -> Unit)? = null

        override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> = items.values.toList()

        override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
            operations += "push"
            if (failPush) throw IllegalStateException("offline")
            pushes += items
            items.forEach { item -> this.items[key(item)] = item }
        }

        override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
            operations += "delete"
            if (failDelete) throw IllegalStateException("offline")
            items.forEach { item -> this.items.remove(key(item)) }
            onDelete?.invoke()
        }
    }

    private companion object {
        fun item(id: String, markedAt: Long, season: Int? = null, episode: Int? = null) = WatchedItem(
            id = id,
            type = if (season == null) "movie" else "series",
            name = id,
            season = season,
            episode = episode,
            markedAtEpochMs = markedAt,
        )

        fun key(item: WatchedItem): String =
            watchedItemKey(item.type, item.id, item.season, item.episode)
    }
}
