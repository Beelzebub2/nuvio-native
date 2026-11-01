package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBatchDownloadDialogTest {
    private val episodes = listOf(
        MetaVideo("show:1:1", "Episode 1", released = "2026-10-01", season = 1, episode = 1),
        MetaVideo("show:1:2", "Episode 2", released = "2026-10-15", season = 1, episode = 2),
        MetaVideo("show:1:3", "Episode 3", released = "2027-02-01", season = 1, episode = 3),
        MetaVideo("show:2:1", "Episode 1", available = false, season = 2, episode = 1),
    )
    private val meta = MetaDetails("show", "series", "Series", videos = episodes)

    @Test fun onlyAlreadyReleasedEpisodesCanBeQueued() {
        val available = androidBatchAvailableEpisodes(meta, episodes, emptyList(), "2026-11-01")
        assertEquals(listOf("show:1:1", "show:1:2"), available.map { it.id })
    }

    @Test fun queuedAndCompletedEpisodesAreExcludedButFailedCanBeRetried() {
        val present = listOf(
            download("show:1:1", 1, DownloadStatus.Completed),
            download("show:1:2", 2, DownloadStatus.Failed),
        )
        val available = androidBatchAvailableEpisodes(meta, episodes, present, "2026-11-01")
        assertEquals(listOf("show:1:2"), available.map { it.id })
    }

    @Test fun deduplicatesRepeatedEntries() {
        val available = androidBatchAvailableEpisodes(meta, episodes + episodes[0], emptyList(), "2026-11-01")
        assertEquals(2, available.size)
    }

    private fun download(id: String, episode: Int, status: DownloadStatus) = DownloadItem(
        id = id, contentType = "series", parentMetaId = "show", parentMetaType = "series",
        videoId = id, title = "Series", streamTitle = "Source", providerName = "Addon",
        sourceUrl = "https://example.com/episode.mkv", fileName = "episode-$episode.mkv",
        seasonNumber = 1, episodeNumber = episode, status = status,
        createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )
}
