package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSeekIntervalsTest {
    @Test
    fun existingTenSecondSeekRemainsTheDefault() {
        val settings = PlayerSettingsUiState()

        assertEquals(10_000L, settings.seekOffsetMs(PlayerSeekStep.SHORT, forward = true))
        assertEquals(-10_000L, settings.seekOffsetMs(PlayerSeekStep.SHORT, forward = false))
        assertEquals(30_000L, settings.seekStepMs(PlayerSeekStep.MEDIUM))
        assertEquals(60_000L, settings.seekStepMs(PlayerSeekStep.LONG))
    }

    @Test
    fun eachConfiguredStepAppliesToForwardAndBackwardSeekingIndependently() {
        val settings = PlayerSettingsUiState(
            shortSeekSeconds = 3,
            mediumSeekSeconds = 25,
            longSeekSeconds = 90,
        )

        assertEquals(3_000L, settings.seekOffsetMs(PlayerSeekStep.SHORT, forward = true))
        assertEquals(-3_000L, settings.seekOffsetMs(PlayerSeekStep.SHORT, forward = false))
        assertEquals(25_000L, settings.seekOffsetMs(PlayerSeekStep.MEDIUM, forward = true))
        assertEquals(-25_000L, settings.seekOffsetMs(PlayerSeekStep.MEDIUM, forward = false))
        assertEquals(90_000L, settings.seekOffsetMs(PlayerSeekStep.LONG, forward = true))
        assertEquals(-90_000L, settings.seekOffsetMs(PlayerSeekStep.LONG, forward = false))
    }

    @Test
    fun invalidStoredValuesNeverBecomeZeroNegativeOrExcessiveSeekOffsets() {
        val settings = PlayerSettingsUiState(
            shortSeekSeconds = Int.MIN_VALUE,
            mediumSeekSeconds = 0,
            longSeekSeconds = Int.MAX_VALUE,
        )

        assertEquals(MIN_SEEK_SECONDS, normalizedSeekSeconds(0))
        assertEquals(MAX_SEEK_SECONDS, normalizedSeekSeconds(10_000))
        assertEquals(1_000L, settings.seekOffsetMs(PlayerSeekStep.SHORT, forward = true))
        assertEquals(-1_000L, settings.seekOffsetMs(PlayerSeekStep.MEDIUM, forward = false))
        assertEquals(120_000L, settings.seekOffsetMs(PlayerSeekStep.LONG, forward = true))
    }
}
