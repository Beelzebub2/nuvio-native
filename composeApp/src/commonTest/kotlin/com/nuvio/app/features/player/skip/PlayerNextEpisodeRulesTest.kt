package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {
    private fun show(position: Long, intervals: List<SkipInterval> = emptyList(),
        mode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE, duration: Long = 1_200_000L) =
        PlayerNextEpisodeRules.shouldShowNextEpisodeCard(position, duration, intervals, mode, 99f, 2f)

    @Test fun ordinaryOutroWithTrailingPaddingShowsBeforeTheFileEnds() {
        val intervals = listOf(SkipInterval(1_110.0, 1_194.0, "ed", "fixture"))
        assertFalse(show(1_109_999, intervals))
        assertTrue(show(1_110_000, intervals))
        assertTrue(show(1_195_000, intervals))
    }

    @Test fun aLongerUnclassifiedTailUsesTheConfiguredPercentageInsteadOfEof() {
        val intervals = listOf(SkipInterval(1_080.0, 1_140.0, "outro", "fixture"))
        assertFalse(show(1_187_999, intervals))
        assertTrue(show(1_188_000, intervals))
    }

    @Test fun minutesModeStillShowsThePromptDuringAnOrdinaryOutro() {
        val intervals = listOf(SkipInterval(1_080.0, 1_140.0, "mixed-ed", "fixture"))
        assertFalse(show(1_079_999, intervals, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        assertTrue(show(1_080_000, intervals, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
    }

    @Test fun explicitPostCreditsScenesAreProtectedInBothThresholdModes() {
        val intervals = listOf(SkipInterval(1_080.0, 1_140.0, "ed", "fixture"),
            SkipInterval(1_150.0, 1_185.0, "post-credits", "fixture"))
        assertFalse(show(1_180_000, intervals, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        assertTrue(show(1_185_000, intervals, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        assertFalse(show(1_187_999, intervals))
        assertTrue(show(1_188_000, intervals))
        val longerScene = intervals.dropLast(1) + intervals.last().copy(endTime = 1_195.0)
        assertFalse(show(1_194_999, longerScene))
        assertTrue(show(1_195_000, longerScene))
    }

    @Test fun fallbackAndPlaceholderGuardsRemainInEffect() {
        assertFalse(show(1_187_999))
        assertTrue(show(1_188_000))
        assertFalse(show(0, duration = 0))
        assertFalse(show(10_000, duration = 10_000))
    }
}
