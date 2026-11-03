package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMetadataVisibilityTest {
    @Test
    fun preparedButUnplayedMediaNeverShowsThePauseMetadata() {
        val initialSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            durationMs = 42_000L,
        )
        val hasStarted = playbackHasStarted(false, initialSnapshot)

        assertFalse(hasStarted)
        assertFalse(pauseMetadataEligible(true, hasStarted, initialSnapshot, hasError = false))
    }

    @Test
    fun positionMustAdvanceDuringPlaybackBeforeMetadataCanAppear() {
        val firstSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 0L)
        val advancedSnapshot = firstSnapshot.copy(positionMs = 250L)

        assertFalse(playbackHasStarted(false, firstSnapshot))
        assertTrue(playbackHasStarted(false, advancedSnapshot))
        assertTrue(playbackHasStarted(true, advancedSnapshot.copy(isPlaying = false)))
    }

    @Test
    fun pauseMetadataOnlyEligibleAfterActualPlaybackAndWhenNotBuffering() {
        val pausedSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, durationMs = 42_000L)

        assertTrue(pauseMetadataEligible(true, true, pausedSnapshot, hasError = false))
        assertFalse(pauseMetadataEligible(true, true, pausedSnapshot.copy(isLoading = true), hasError = false))
        assertFalse(pauseMetadataEligible(true, true, pausedSnapshot.copy(isPlaying = true), hasError = false))
        assertFalse(pauseMetadataEligible(true, true, pausedSnapshot.copy(durationMs = 0), hasError = false))
        assertFalse(pauseMetadataEligible(true, true, pausedSnapshot, hasError = true))
        assertFalse(pauseMetadataEligible(false, true, pausedSnapshot, hasError = false))
    }
}
