package com.nuvio.app.features.watchlater

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlin.test.*

class WatchLaterPlaybackTest {
    @Test fun savedEpisodesResumeTheirOwnProgressAndDoNotReuseAnotherEpisodesPosition() {
        val item = LibraryItem("anime", "series", "Anime", savedAtEpochMs = 0,
            watchLaterEpisode = WatchLaterEpisode("addon-video-id", "Second", 1, 2))
        val entry = WatchProgressEntry("series", "anime", "series", "anime:1:2", "Anime",
            seasonNumber = 1, episodeNumber = 2, lastPositionMs = 60_000, durationMs = 120_000, lastUpdatedEpochMs = 1)
        val progress = WatchProgressUiState(entries = listOf(entry))
        val playback = item.watchLaterPlayback(progress)
        assertEquals("addon-video-id", playback.videoId)
        assertEquals(60_000L, playback.resumePositionMs)
        assertEquals(0.5f, playback.progressFraction)
        assertEquals(0L, item.copy(watchLaterEpisode = WatchLaterEpisode("other", "Third", 1, 3))
            .watchLaterPlayback(progress).resumePositionMs)
        assertEquals(0L, item.watchLaterPlayback(WatchProgressUiState(entries = listOf(entry.copy(isCompleted = true)))).resumePositionMs)
    }
}
