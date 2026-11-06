package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * Storage estimates are informational: unknown total sizes and untracked files
 * must never be treated as free disk capacity.
 */
internal fun trackedDownloadBytes(items: List<DownloadItem>): Long =
    items.fold(0L) { total, item ->
        val bytes = item.downloadedBytes.coerceAtLeast(0L)
        if (bytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + bytes
    }

/** Only explicitly checked, completed, locally verified downloads can be cleaned. */
internal fun eligibleDownloadCleanup(
    items: List<DownloadItem>,
    verifier: (DownloadItem) -> Long?,
): List<DownloadCleanupEntry> = items.asSequence()
    .filter { it.isPlayable }
    .mapNotNull { item ->
        verifier(item)?.takeIf { it > 0L }?.let { bytes ->
            DownloadCleanupEntry(
                id = item.id,
                title = item.episodeTitle?.takeIf(String::isNotBlank) ?: item.title,
                bytes = bytes,
                createdAtEpochMs = item.createdAtEpochMs,
            )
        }
    }
    .sortedWith(compareBy<DownloadCleanupEntry> { it.createdAtEpochMs }.thenBy { it.id })
    .toList()

internal data class DownloadCleanupEntry(
    val id: String,
    val title: String,
    val bytes: Long,
    val createdAtEpochMs: Long,
)

/**
 * An opt-in suggestion for the existing, explicitly confirmed cleanup dialog.
 * Age is based on download creation, not a claimed last-played/LRU timestamp.
 * Only pass entries that have already passed platform ownership verification.
 */
internal fun suggestedOldDownloadCleanupIds(
    eligible: List<DownloadCleanupEntry>,
    nowEpochMs: Long,
    ageDays: Int = 30,
): Set<String> {
    if (nowEpochMs <= 0L || ageDays !in 1..3650) return emptySet()
    val cutoff = nowEpochMs - ageDays.toLong() * 86_400_000L
    return eligible.asSequence()
        .filter { it.bytes > 0L && it.id.isNotBlank() && it.createdAtEpochMs in 1L..cutoff }
        .mapTo(linkedSetOf()) { it.id }
}

/**
 * Suggests the oldest *verified* completed copies until their combined size
 * would bring the tracked total under the informational storage target.
 * This does not make the target a hard quota or authorize any deletion.
 */
internal fun suggestedTargetDownloadCleanupIds(
    eligible: List<DownloadCleanupEntry>,
    trackedBytes: Long,
    targetBytes: Long?,
): Set<String> {
    if (targetBytes == null || targetBytes <= 0L || trackedBytes <= targetBytes) return emptySet()
    var remaining = trackedBytes - targetBytes
    val selected = linkedSetOf<String>()
    for (entry in eligible.sortedWith(compareBy<DownloadCleanupEntry> { it.createdAtEpochMs }.thenBy { it.id })) {
        if (remaining <= 0L) break
        if (entry.bytes <= 0L || entry.id.isBlank() || !selected.add(entry.id)) continue
        remaining = (remaining - entry.bytes).coerceAtLeast(0L)
    }
    return selected
}

/**
 * Playback timestamps are not download transfer timestamps. Match by the
 * series and exact season/episode identity so recycled addon video IDs cannot
 * incorrectly mark another offline copy as recently watched.
 */
internal fun lastWatchedEpochMsByDownloadedEpisodeId(
    items: Collection<DownloadItem>,
    progressEntries: Collection<WatchProgressEntry>,
    nowEpochMs: Long,
): Map<String, Long> {
    if (nowEpochMs <= 0L) return emptyMap()
    data class Episode(val type: String, val metaId: String, val season: Int, val episode: Int)
    fun key(type: String, metaId: String, season: Int?, episode: Int?): Episode? {
        if (season == null || episode == null || metaId.isBlank()) return null
        return Episode(type.trim().lowercase(), metaId.trim(), season, episode)
    }

    val lastPlayed = progressEntries.asSequence()
        .filter {
            it.lastUpdatedEpochMs in 1L..nowEpochMs &&
                (it.lastPositionMs > 0L || it.isEffectivelyCompleted)
        }
        .mapNotNull { entry ->
            key(entry.parentMetaType, entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
                ?.let { identity -> identity to entry.lastUpdatedEpochMs }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, dates) -> dates.maxOrNull() ?: 0L }

    return items.asSequence()
        .filter { it.isEpisode && it.status == DownloadStatus.Completed }
        .mapNotNull { item ->
            val identity = key(item.parentMetaType, item.parentMetaId, item.seasonNumber, item.episodeNumber)
                ?: return@mapNotNull null
            lastPlayed[identity]?.takeIf { it > 0L }?.let { item.id to it }
        }
        .toMap()
}

/**
 * LRU-based *suggestions* are limited to watched, ownership-verified offline
 * copies. A suggestion is never a deletion request: the user still checks the
 * exact files in the existing confirmation dialog.
 */
internal fun suggestedLeastRecentlyWatchedCleanupIds(
    eligible: List<DownloadCleanupEntry>,
    watchedDownloadIds: Set<String>,
    lastWatchedEpochMsByDownloadId: Map<String, Long>,
    trackedBytes: Long,
    targetBytes: Long?,
): Set<String> {
    if (targetBytes == null || targetBytes <= 0L || trackedBytes <= targetBytes) return emptySet()
    var remaining = trackedBytes - targetBytes
    val selected = linkedSetOf<String>()
    eligible.asSequence()
        .filter { it.bytes > 0L && it.id in watchedDownloadIds &&
            (lastWatchedEpochMsByDownloadId[it.id] ?: 0L) > 0L }
        .sortedWith(compareBy<DownloadCleanupEntry> {
            lastWatchedEpochMsByDownloadId[it.id] ?: Long.MAX_VALUE
        }.thenBy { it.createdAtEpochMs }.thenBy { it.id })
        .forEach { entry ->
            if (remaining > 0L && selected.add(entry.id)) {
                remaining = (remaining - entry.bytes).coerceAtLeast(0L)
            }
        }
    return selected
}

/**
 * Platform implementations must verify that a completed file is an actual
 * Starlit-owned private file/task directory and not a symlink or a user file.
 */
internal expect object DownloadStorageManagement {
    val supported: Boolean
    fun availableBytes(): Long?
    fun quotaBytes(profileId: Int): Long?
    fun setQuotaBytes(profileId: Int, limitBytes: Long?)
    fun eligibleCleanupBytes(item: DownloadItem): Long?
}
