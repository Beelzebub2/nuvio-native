package com.nuvio.app.features.watched

import com.nuvio.app.features.watching.sync.WatchedSyncAdapter

internal fun WatchedItem.toNuvioDeleteIntent(): WatchedItem = WatchedItem(
    id = id,
    type = type,
    name = "",
    season = season,
    episode = episode,
    markedAtEpochMs = 0L,
)

/** Keys used to hide pending removals even when an upstream snapshot uses a type alias. */
internal fun pendingWatchedDeleteKeys(items: Collection<WatchedItem>): Set<String> =
    items.flatMapTo(linkedSetOf()) { item ->
        watchedItemKeys(item.type, item.id, item.season, item.episode)
    }

/**
 * Sends durable removals before marks. An acknowledgement only removes the exact
 * pending mutation that was sent, so deleting and then re-marking the same item
 * while a request is in flight cannot discard the newer local change.
 * The caller serializes this with native refreshes and other outbox flushes.
 */
internal suspend fun flushWatchedNuvioOutbox(
    profileId: Int,
    store: WatchedItemsStore,
    adapter: WatchedSyncAdapter,
    isCurrent: () -> Boolean,
    persist: () -> Unit,
    acknowledgePush: (Collection<WatchedItem>) -> Unit,
): Boolean {
    val pendingDeletes = store.readWithPendingDeletes { _, _, _, _, deletes ->
        deletes.values.toList()
    }
    pendingDeletes.chunked(200).forEach { batch ->
        if (!isCurrent()) return false
        adapter.delete(profileId = profileId, items = batch)
        if (!isCurrent()) return false
        val acknowledged = store.updateWithPendingDeletes { _, _, _, _, deletes ->
            var changed = false
            batch.forEach { deletedItem ->
                val key = watchedItemKey(
                    deletedItem.type, deletedItem.id, deletedItem.season, deletedItem.episode,
                )
                if (deletes[key] == deletedItem) {
                    deletes.remove(key)
                    changed = true
                }
            }
            changed
        }
        if (acknowledged) persist()
    }

    val pendingMarks = store.read { nuvioItems, _, dirtyNuvioKeys, _ ->
        dirtyNuvioKeys.mapNotNull(nuvioItems::get)
    }
    pendingMarks.chunked(200).forEach { batch ->
        if (!isCurrent()) return false
        adapter.push(profileId = profileId, items = batch)
        if (!isCurrent()) return false
        acknowledgePush(batch)
    }
    return isCurrent()
}
