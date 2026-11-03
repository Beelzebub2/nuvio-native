package com.nuvio.app.features.remote

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsUiState
import kotlinx.serialization.Serializable

/**
 * Only sanitized status is transported; paths, URLs, headers, tokens, torrent
 * metadata, subtitle sources and other download configuration stay on the PC.
 */
@Serializable
internal data class RemoteDownloadStatus(
    val id: String,
    val title: String,
    val state: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
)

private val safeDownloadId = Regex("[a-zA-Z0-9_-]{1,64}")

internal fun remoteDownloadSnapshot(state: DownloadsUiState, activeProfileId: Int): List<RemoteDownloadStatus> {
    if (state.profileId != activeProfileId) return emptyList()
    return state.items.asSequence()
        .filter { it.id.matches(safeDownloadId) }
        .take(30)
        .map { item ->
            RemoteDownloadStatus(
                id = item.id,
                title = item.title.take(300).map { if (it < ' ' || it == '\u007f') ' ' else it }
                    .joinToString("").trim().take(100),
                state = item.status,
                downloadedBytes = item.downloadedBytes.coerceAtLeast(0L),
                totalBytes = item.totalBytes?.takeIf { it > 0L },
            )
        }
        .toList()
}

/** Only existing tasks of the desktop's active profile may be paused/resumed. */
internal fun RemoteCommand.allowedDownloadTask(
    state: DownloadsUiState,
    activeProfileId: Int,
    activePlaybackId: String,
): DownloadItem? {
    if (profileId != activeProfileId || state.profileId != activeProfileId ||
        playbackId != activePlaybackId || value != 0.0 ||
        !id.matches(safeDownloadId)) return null
    val item = state.items.singleOrNull { it.id == id } ?: return null
    return when (type) {
        "downloadPause" -> item.takeIf { it.status == DownloadStatus.Downloading }
        "downloadResume" -> item.takeIf { it.status == DownloadStatus.Paused }
        else -> null
    }
}
