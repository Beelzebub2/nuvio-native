package com.nuvio.app.features.downloads

import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchedDownloadCleanupTest {
    private fun download(
        id: String,
        show: String = "show-a",
        type: String = "series",
        episode: Int? = 1,
        status: DownloadStatus = DownloadStatus.Completed,
        sourceSelection: DownloadSourceSelection? = null,
    ) = DownloadItem(
        id = id,
        contentType = type,
        parentMetaId = show,
        parentMetaType = type,
        videoId = "reused-video-id",
        title = "Episode fixture",
        seasonNumber = episode?.let { 1 },
        episodeNumber = episode,
        streamTitle = "1080p",
        providerName = "Fixture",
        sourceUrl = "https://example.invalid/episode.mp4",
        sourceSelection = sourceSelection,
        localFileUri = "file:///test/$id.mp4",
        fileName = "$id.mp4",
        status = status,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
    )

    private fun progress(
        show: String = "show-a",
        type: String = "series",
        episode: Int = 2,
        completedPercent: Float = 95f,
    ) = WatchProgressEntry(
        contentType = type,
        parentMetaId = show,
        parentMetaType = type,
        videoId = "reused-video-id",
        title = "Episode fixture",
        seasonNumber = 1,
        episodeNumber = episode,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = 100L,
        progressPercent = completedPercent,
    )

    @Test
    fun includesManualAndBatchEpisodesButNotUnwatchedOrActiveDownloads() {
        val items = listOf(
            download("manual", episode = 1),
            download("manual-batch", type = "anime", episode = 2,
                sourceSelection = DownloadSourceSelection()),
            download("unwatched", episode = 3),
            download("active", episode = 4, status = DownloadStatus.Downloading),
            download("paused", episode = 5, status = DownloadStatus.Paused),
            download("movie", type = "movie", episode = null),
            download("other-show", show = "show-b", episode = 1),
        )
        val marked = setOf(
            watchedItemKey("series", "show-a", 1, 1),
            watchedItemKey("series", "show-a", 1, 4),
            watchedItemKey("series", "show-a", 1, 5),
            watchedItemKey("movie", "show-a"),
        )
        val result = watchedDownloadedEpisodeIds(items, marked, listOf(
            progress(episode = 2),
            progress(episode = 3, completedPercent = 70f),
        ))
        assertEquals(setOf("manual", "manual-batch"), result)
    }

    @Test
    fun neverMatchesACompletedProgressEntryFromAnotherSeriesOrEpisode() {
        val items = listOf(download("a1", episode = 1), download("a2", episode = 2))
        assertTrue(watchedDownloadedEpisodeIds(items, emptySet(), listOf(
            progress(show = "show-b", episode = 1),
            progress(episode = 3),
        )).isEmpty())
    }

    @Test
    fun respectsAnUpdatedWatchSnapshotBeforeDeleting() {
        val items = listOf(download("a1"))
        val marked = setOf(watchedItemKey("series", "show-a", 1, 1))
        assertEquals(setOf("a1"), watchedDownloadedEpisodeIds(items, marked, emptyList()))
        assertTrue(watchedDownloadedEpisodeIds(items, emptySet(), emptyList()).isEmpty())
    }
}
