package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.disposeRouteResources
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.features.watchprogress.ResumePromptStorage
import com.nuvio.app.navigation.PlayerRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivatePlaybackSessionTest {
    private val profile get() = ProfileRepository.activeProfileId

    private fun session(contentId: String, privatePlayback: Boolean = false) = WatchProgressPlaybackSession(
        profileId = profile,
        contentType = "movie",
        parentMetaId = contentId,
        parentMetaType = "movie",
        videoId = contentId,
        title = "Example Movie",
        poster = "https://example.com/poster.jpg",
        background = "https://example.com/background.jpg",
        privatePlayback = privatePlayback,
    )

    private fun snapshot(position: Long, ended: Boolean = false) = PlayerPlaybackSnapshot(
        isPlaying = !ended,
        isLoading = false,
        isEnded = ended,
        positionMs = position,
        durationMs = 600_000L,
    )

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_watch_progress", Context.MODE_PRIVATE).edit().clear().commit()
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
        context.getSharedPreferences("nuvio_resume_prompt", Context.MODE_PRIVATE).edit().clear().commit()
        ResumePromptStorage.initialize(context)
    }

    @After
    fun cleanup() {
        WatchProgressRepository.clearLocalState()
        ResumePromptRepository.markPlayerExitedNormally()
    }

    @Test
    fun privateRouteDisposalDoesNotClearAnotherSessionResumeMarker() {
        fun launch(privatePlayback: Boolean) = PlayerLaunch(
            profileId = profile,
            title = "Example Movie",
            sourceUrl = "https://example.com/video.mp4",
            streamTitle = "Example Stream",
            providerName = "Example Provider",
            parentMetaId = "normal-movie",
            parentMetaType = "movie",
            privatePlayback = privatePlayback,
        )

        ResumePromptRepository.markPlayerEntered("normal-movie")
        val privateId = PlayerLaunchStore.put(launch(privatePlayback = true))
        try {
            disposeRouteResources(PlayerRoute(privateId))
            assertNull(PlayerLaunchStore.get(privateId))
            assertTrue(ResumePromptStorage.loadWasInPlayer())
            assertEquals("normal-movie", ResumePromptStorage.loadLastPlayerVideoId())

            val normalId = PlayerLaunchStore.put(launch(privatePlayback = false))
            try {
                disposeRouteResources(PlayerRoute(normalId))
                assertFalse(ResumePromptStorage.loadWasInPlayer())
                assertNull(ResumePromptStorage.loadLastPlayerVideoId())
            } finally {
                PlayerLaunchStore.remove(normalId)
            }
        } finally {
            PlayerLaunchStore.remove(privateId)
        }
    }

    @Test
    fun privateSessionDoesNotPersistStartSeekStopOrCompletion() {
        val privateSession = session("private-one", privatePlayback = true)
        assertTrue(WatchProgressRepository.uiState.value.entries.isEmpty())
        assertNull(WatchProgressStorage.loadPayload(profile))

        WatchProgressRepository.upsertPlaybackProgress(privateSession, snapshot(35_000L))
        WatchProgressRepository.upsertPlaybackProgress(privateSession, snapshot(125_000L))
        WatchProgressRepository.flushPlaybackProgress(privateSession, snapshot(200_000L))
        WatchProgressRepository.flushPlaybackProgress(privateSession, snapshot(600_000L, ended = true))

        assertNull(WatchProgressStorage.loadPayload(profile))
        assertTrue(WatchProgressRepository.uiState.value.entries.isEmpty())
        assertNull(WatchProgressRepository.progressForVideo("private-one"))
    }

    @Test
    fun normalAndPrivateSessionsDoNotAffectEachOther() {
        val normal = session("normal")
        val private = session("normal", privatePlayback = true)
        assertFalse(normal.privatePlayback)
        WatchProgressRepository.upsertPlaybackProgress(normal, snapshot(60_000L), syncRemote = false)
        val originalProgress = WatchProgressRepository.progressForVideo("normal")
        val originalPayload = WatchProgressStorage.loadPayload(profile)
        assertEquals(60_000L, originalProgress?.lastPositionMs)

        WatchProgressRepository.upsertPlaybackProgress(private, snapshot(480_000L))
        WatchProgressRepository.flushPlaybackProgress(private, snapshot(600_000L, ended = true))
        assertEquals(originalPayload, WatchProgressStorage.loadPayload(profile))
        assertEquals(originalProgress, WatchProgressRepository.progressForVideo("normal"))

        WatchProgressRepository.flushPlaybackProgress(normal, snapshot(120_000L), syncRemote = false)
        assertEquals(120_000L, WatchProgressRepository.progressForVideo("normal")?.lastPositionMs)
    }

    @Test
    fun privateExternalCallbackCannotWriteUsingItsOwnOrFallbackNormalSession() = runBlocking {
        val private = session("private-external", privatePlayback = true)
        val ordinary = session("normal-fallback")
        val result = ExternalPlaybackResult(
            positionMs = 300_000L,
            durationMs = 600_000L,
            endedByUser = false,
            playbackSession = private,
        )
        recordExternalPlaybackProgress(result, fallbackSession = ordinary)

        assertNull(WatchProgressRepository.progressForVideo("private-external"))
        assertNull(WatchProgressRepository.progressForVideo("normal-fallback"))
        assertNull(WatchProgressStorage.loadPayload(profile))

        recordExternalPlaybackProgress(
            result.copy(playbackSession = ordinary, positionMs = 80_000L, endedByUser = true, durationMs = null),
            fallbackSession = null,
        )
        assertEquals(80_000L, WatchProgressRepository.progressForVideo("normal-fallback")?.lastPositionMs)
    }

    @Test
    fun privateScrobbleStartPauseStopAndSeekHaveNoProviderSideEffects() = runBlocking {
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, title = "Example Movie"),
            progressPercent = 55.0,
        )
        for (action in TrackingScrobbleAction.entries) {
            assertTrue(
                TrackingScrobbleCoordinator.scrobble(
                    profileId = profile,
                    action = action,
                    event = event,
                    privatePlayback = true,
                ).isEmpty(),
            )
            assertTrue(
                TrackingScrobbleCoordinator.scrobbleSeek(
                    profileId = profile,
                    action = action,
                    event = event,
                    privatePlayback = true,
                ).isEmpty(),
            )
        }
    }
}
