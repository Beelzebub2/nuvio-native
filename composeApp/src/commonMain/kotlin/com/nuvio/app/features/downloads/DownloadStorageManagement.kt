package com.nuvio.app.features.downloads

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
