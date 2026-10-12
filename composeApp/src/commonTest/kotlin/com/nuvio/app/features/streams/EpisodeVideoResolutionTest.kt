package com.nuvio.app.features.streams

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EpisodeVideoResolutionTest {
    private val launch = StreamLaunch(1, "series", "addon:1:2", "anime", "series", "Anime", seasonNumber = 1, episodeNumber = 2)
    @Test fun hungMetadataIsCancelledAndTheOriginalPlayableIdIsRetained() = runTest {
        var cancelled = false
        val resolved = resolveEpisodeVideoId(launch) { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        assertEquals(launch.videoId, resolved)
        assertTrue(cancelled)
    }
    @Test fun availableMetadataResolvesTheEpisodeAndErrorsRetainTheLaunchId() = runTest {
        assertEquals("canonical-video", resolveEpisodeVideoId(launch) { _, _ ->
            MetaDetails("anime", "series", "Anime", videos = listOf(MetaVideo("canonical-video", "Second", season = 1, episode = 2))) })
        assertEquals(launch.videoId, resolveEpisodeVideoId(launch) { _, _ -> error("Metadata unavailable") })
    }
    @Test fun leavingTheRouteCancelsMetadataInsteadOfOpeningStalePlayback() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch { resolveEpisodeVideoId(this@EpisodeVideoResolutionTest.launch) { _, _ -> started.complete(Unit); awaitCancellation() }; returned = true }
        started.await(); job.cancelAndJoin(); assertFalse(returned)
    }
}
