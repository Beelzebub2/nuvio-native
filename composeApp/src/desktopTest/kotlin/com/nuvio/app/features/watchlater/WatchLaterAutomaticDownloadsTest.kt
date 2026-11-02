package com.nuvio.app.features.watchlater

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadDesktopFeatures
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.embeddedDownloadStreams
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchLaterAutomaticDownloadsTest {
    private val today = "2026-10-31"

    @Test fun `movie uses metadata default video id while retaining bookmark identity`() {
        val movie = LibraryItem("imdb:film", "movie", "Film", poster = "poster", savedAtEpochMs = 10L)
        val meta = MetaDetails("provider:film", "movie", "Other name", defaultVideoId = "provider:film:video",
            videos = listOf(MetaVideo("provider:film:video", "Film", released = "2020-01-01")))
        val (details, video) = assertNotNull(watchLaterDownloadTarget(movie, meta, today))
        assertEquals("imdb:film", details.id)
        assertEquals("Film", details.name)
        assertEquals("poster", details.poster)
        assertEquals("provider:film:video", video.id)
        assertEquals(listOf(video), details.videos)
    }

    @Test fun `series bookmark selects only the matching released episode`() {
        val saved = LibraryItem("show", "series", "Show", savedAtEpochMs = 2L,
            watchLaterEpisode = WatchLaterEpisode("old-provider-id", "Episode two", 1, 2))
        val meta = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("episode-1", "First", released = "2020-01-01", season = 1, episode = 1),
            MetaVideo("episode-2", "Second", released = "2020-01-02", season = 1, episode = 2),
        ))
        val (details, video) = assertNotNull(watchLaterDownloadTarget(saved, meta, today))
        assertEquals("episode-2", video.id)
        assertEquals(1, video.season)
        assertEquals(2, video.episode)
        assertEquals(listOf(video), details.videos)
        assertNull(watchLaterDownloadTarget(saved, meta.copy(videos = listOf(
            MetaVideo("episode-2", "Unaired", released = "2027-02-01", season = 1, episode = 2),
        )), today))
        assertNull(watchLaterDownloadTarget(saved, meta.copy(videos = listOf(
            MetaVideo("episode-1", "First", released = "2020-01-01", season = 1, episode = 1),
        )), today))
        assertNull(watchLaterDownloadTarget(saved, null, today))
    }

    @Test fun `movie bookmark uses canonical metadata id when stream id is not declared`() {
        val movie = LibraryItem("tmdb:123", "movie", "Film", savedAtEpochMs = 11L)
        val meta = MetaDetails("tt4567890", "movie", "Film")
        val (details, video) = assertNotNull(watchLaterDownloadTarget(movie, meta, today))
        assertEquals("tmdb:123", details.id)
        assertEquals("tt4567890", video.id)
    }

    @Test fun `unresolved series and native airing placeholders are never queued`() {
        val series = LibraryItem("show", "series", "Show", savedAtEpochMs = 2L)
        assertNull(watchLaterDownloadTarget(series, null, today))
        assertNull(watchLaterDownloadTarget(series.copy(watchLaterEpisode = WatchLaterEpisode(
            "nativeairing:21:2", "Pending", 1, 2)), null, today))
        val resolved = assertNotNull(watchLaterDownloadTarget(series.copy(watchLaterEpisode = WatchLaterEpisode(
            "kitsu:21:1:2", "Second", 1, 2)), MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("kitsu:21:1:2", "Second", season = 1, episode = 2),
        )), today))
        assertEquals("kitsu:21:1:2", resolved.second.id)
    }

    @Test fun `embedded streams are recovered from matching cached metadata`() {
        val videoStream = StreamItem(name = "episode two", url = "https://example.test/episode-2.mp4",
            addonName = "Fixture", addonId = "fixture")
        val otherStream = StreamItem(name = "episode one", url = "https://example.test/episode-1.mp4",
            addonName = "Fixture", addonId = "fixture")
        val meta = MetaDetails("series-a", "series", "Series", videos = listOf(
            MetaVideo("episode-1", "One", season = 1, episode = 1, streams = listOf(otherStream)),
            MetaVideo("episode-2", "Two", season = 1, episode = 2, streams = listOf(videoStream)),
        ))
        val task = DownloadItem(id = "1", contentType = "series", parentMetaId = "series-a",
            parentMetaType = "series", videoId = "old-provider-episode-2", title = "Series",
            seasonNumber = 1, episodeNumber = 2, streamTitle = "Automatic",
            providerName = "", sourceUrl = "", fileName = "episode.mp4",
            status = DownloadStatus.Paused, createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
        assertEquals(listOf(videoStream), embeddedDownloadStreams(meta, task))
        assertTrue(embeddedDownloadStreams(null, task).isEmpty())
        assertTrue(embeddedDownloadStreams(meta, task.copy(episodeNumber = 3)).isEmpty())
        // A title-level ID or ambiguous prefix is never evidence for a specific episode.
        assertTrue(embeddedDownloadStreams(meta, task.copy(videoId = meta.id,
            seasonNumber = null, episodeNumber = null)).isEmpty())
        assertTrue(embeddedDownloadStreams(meta, task.copy(videoId = "episode",
            seasonNumber = null, episodeNumber = null)).isEmpty())
        // A unique movie video is safe when its metadata only has the title ID.
        val movie = meta.copy(type = "movie", videos = listOf(meta.videos.first()))
        assertEquals(listOf(otherStream), embeddedDownloadStreams(movie,
            task.copy(contentType = "movie", videoId = movie.id, seasonNumber = null, episodeNumber = null)))
    }

    @Test fun `automatic download preference persists separately per profile`() {
        val profileOne = 897_131
        val profileTwo = 897_132
        val beforeOne = DownloadDesktopFeatures.autoDownloadWatchLater(profileOne)
        val beforeTwo = DownloadDesktopFeatures.autoDownloadWatchLater(profileTwo)
        try {
            DownloadDesktopFeatures.setAutoDownloadWatchLater(false, profileOne)
            DownloadDesktopFeatures.setAutoDownloadWatchLater(false, profileTwo)
            DownloadDesktopFeatures.setAutoDownloadWatchLater(true, profileOne)
            assertTrue(DownloadDesktopFeatures.autoDownloadWatchLater(profileOne))
            assertFalse(DownloadDesktopFeatures.autoDownloadWatchLater(profileTwo))
        } finally {
            DownloadDesktopFeatures.setAutoDownloadWatchLater(beforeOne, profileOne)
            DownloadDesktopFeatures.setAutoDownloadWatchLater(beforeTwo, profileTwo)
        }
    }
}
