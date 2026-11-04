package com.nuvio.app.features.downloads

import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * Match by series identity and season/episode, never by a video ID alone: addons
 * can reuse video IDs across unrelated shows. Both direct/manual downloads and
 * batches with automatic source selection belong in this list.
 */
internal fun watchedDownloadedEpisodeIds(
    items: Collection<DownloadItem>,
    watchedKeys: Set<String>,
    progressEntries: Collection<WatchProgressEntry>,
): Set<String> {
    val completedProgressKeys = progressEntries.asSequence()
        .filter { it.isEpisode && it.isEffectivelyCompleted }
        .flatMap { entry ->
            watchedItemKeys(entry.parentMetaType, entry.parentMetaId,
                entry.seasonNumber, entry.episodeNumber).asSequence()
        }
        .toSet()

    return items.asSequence()
        .filter { it.isEpisode && it.status == DownloadStatus.Completed }
        .filter { item ->
            watchedItemKeys(item.parentMetaType, item.parentMetaId,
                item.seasonNumber, item.episodeNumber).any { key ->
                key in watchedKeys || key in completedProgressKeys
            }
        }
        .mapTo(linkedSetOf()) { it.id }
}
