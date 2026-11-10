package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalPlaybackProgressPercentTest {
    @Test
    fun externalPlaybackEofAlwaysMarksItemWatched() {
        assertEquals(100.0, externalStopScrobblePercent(0, 120_000, playedToEnd = true))
        assertEquals(100.0, externalStopScrobblePercent(60_000, 120_000, playedToEnd = true))
    }

    @Test
    fun manualStopPreservesPartialProgressAndBoundsInvalidPositions() {
        assertEquals(50.0, externalStopScrobblePercent(60_000, 120_000, playedToEnd = false))
        assertEquals(0.0, externalStopScrobblePercent(-10, 120_000, playedToEnd = false))
        assertEquals(100.0, externalStopScrobblePercent(130_000, 120_000, playedToEnd = false))
        assertEquals(0.0, externalStopScrobblePercent(60_000, 0, playedToEnd = false))
    }
}
