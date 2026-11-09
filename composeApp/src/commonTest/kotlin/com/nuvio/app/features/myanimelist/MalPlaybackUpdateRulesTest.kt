package com.nuvio.app.features.myanimelist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MalPlaybackUpdateRulesTest {
    @Test
    fun briefPlaybackDoesNotMarkAnEpisodeAsRewatching() {
        // A five-minute session in a typical 24-minute episode is only 20.8%.
        assertFalse(shouldUpdateMalAfterPlayback(300_000L, 1_440_000L, true, false, 55))
        assertFalse(shouldUpdateMalAfterPlayback(720_000L, 1_440_000L, true, false, 55))
        assertFalse(shouldUpdateMalAfterPlayback(791_999L, 1_440_000L, true, false, 55))
        assertTrue(shouldUpdateMalAfterPlayback(792_000L, 1_440_000L, true, false, 55))
    }

    @Test
    fun settingChangesThePointAtWhichAutomaticUpdatesAreAllowed() {
        assertTrue(shouldUpdateMalAfterPlayback(792_000L, 1_440_000L, true, false, 55))
        assertFalse(shouldUpdateMalAfterPlayback(792_000L, 1_440_000L, true, false, 75))
        assertTrue(shouldUpdateMalAfterPlayback(1_080_000L, 1_440_000L, true, false, 75))
        assertFalse(shouldUpdateMalAfterPlayback(1_367_999L, 1_440_000L, true, false, 95))
        assertTrue(shouldUpdateMalAfterPlayback(1_368_000L, 1_440_000L, true, false, 95))
    }

    @Test
    fun bufferingPausedAndInvalidMediaNeverTriggerAutomaticUpdates() {
        assertFalse(shouldUpdateMalAfterPlayback(800_000L, 1_440_000L, false, false, 55))
        assertFalse(shouldUpdateMalAfterPlayback(800_000L, 1_440_000L, true, true, 55))
        assertFalse(shouldUpdateMalAfterPlayback(800_000L, 0L, true, false, 55))
        assertFalse(shouldUpdateMalAfterPlayback(-1L, 1_440_000L, true, false, 55))
    }

    @Test
    fun thresholdPreferencesAreClampedAndSnappedToFivePercentSteps() {
        assertEquals(55, normalizeMalPlaybackUpdateThresholdPercent(-1))
        assertEquals(55, normalizeMalPlaybackUpdateThresholdPercent(54))
        assertEquals(55, normalizeMalPlaybackUpdateThresholdPercent(57))
        assertEquals(60, normalizeMalPlaybackUpdateThresholdPercent(58))
        assertEquals(95, normalizeMalPlaybackUpdateThresholdPercent(100))
    }
}
