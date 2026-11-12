package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {
    private fun show(position: Long, intervals: List<SkipInterval> = emptyList(),
        mode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        duration: Long = 1_200_000L,
        percent: Float = 99f,
    ) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(position, duration, intervals, mode, percent, 2f)

    private fun autoPlay(position: Long, intervals: List<SkipInterval> = emptyList(),
        mode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.SMART,
        duration: Long = 1_200_000L,
    ) = PlayerNextEpisodeRules.shouldAutomaticallyPlayNextEpisode(
        position, duration, intervals, mode, 99f, 2f,
    )

    @Test fun smartUsesDurationAwareFallbackWithoutMetadata() {
        assertFalse(show(1_139_999, mode = NextEpisodeThresholdMode.SMART))
        assertTrue(show(1_140_000, mode = NextEpisodeThresholdMode.SMART))

        // A 24-minute episode shows 72 seconds before the end; a long one is capped at 90.
        assertFalse(show(1_367_999, mode = NextEpisodeThresholdMode.SMART, duration = 1_440_000))
        assertTrue(show(1_368_000, mode = NextEpisodeThresholdMode.SMART, duration = 1_440_000))
        assertFalse(show(2_909_999, mode = NextEpisodeThresholdMode.SMART, duration = 3_000_000))
        assertTrue(show(2_910_000, mode = NextEpisodeThresholdMode.SMART, duration = 3_000_000))

        // Short episodes have at least a 45-second lead, not a 90-second intrusion.
        assertFalse(show(434_999, mode = NextEpisodeThresholdMode.SMART, duration = 480_000))
        assertTrue(show(435_000, mode = NextEpisodeThresholdMode.SMART, duration = 480_000))
    }

    @Test fun smartShowsAtCredibleCreditsAndIgnoresInvalidOrEarlyMarkers() {
        val valid = listOf(SkipInterval(1_110.0, 1_194.0, "ed", "fixture"))
        assertFalse(show(1_109_999, valid, NextEpisodeThresholdMode.SMART))
        assertTrue(show(1_110_000, valid, NextEpisodeThresholdMode.SMART))

        val invalid = listOf(
            SkipInterval(Double.NaN, 1_190.0, "ed", "fixture"),
            SkipInterval(1_080.0, 1_250.0, "outro", "fixture"),
            SkipInterval(700.0, 790.0, "ed", "fixture"),
        )
        assertFalse(show(1_139_999, invalid, NextEpisodeThresholdMode.SMART))
        assertTrue(show(1_140_000, invalid, NextEpisodeThresholdMode.SMART))
    }

    @Test fun smartProtectsPostCreditsScenesAndDelaysOnlyAutomaticPlayback() {
        val markers = listOf(
            SkipInterval(1_110.0, 1_145.0, "ed", "fixture"),
            SkipInterval(1_160.0, 1_195.0, "post-credits", "fixture"),
        )
        assertFalse(show(1_194_999, markers, NextEpisodeThresholdMode.SMART))
        assertTrue(show(1_195_000, markers, NextEpisodeThresholdMode.SMART))
        assertFalse(autoPlay(1_194_999, markers))
        assertTrue(autoPlay(1_195_000, markers))

        val noScene = markers.take(1)
        assertTrue(show(1_110_000, noScene, NextEpisodeThresholdMode.SMART))
        assertFalse(autoPlay(1_110_000, noScene))
        assertFalse(autoPlay(1_189_999, noScene))
        assertTrue(autoPlay(1_190_000, noScene))
    }

    @Test fun manualPercentageCanOfferTheCardEarlierWhileRetainingItsAutomaticBehavior() {
        assertFalse(show(1_019_999, percent = 85f))
        assertTrue(show(1_020_000, percent = 85f))
        assertFalse(PlayerNextEpisodeRules.shouldAutomaticallyPlayNextEpisode(
            1_020_000, 1_200_000, emptyList(), NextEpisodeThresholdMode.PERCENTAGE, 85f, 2f,
        ))
        assertTrue(PlayerNextEpisodeRules.shouldAutomaticallyPlayNextEpisode(
            1_140_000, 1_200_000, emptyList(), NextEpisodeThresholdMode.PERCENTAGE, 85f, 2f,
        ))
        assertTrue(autoPlay(1_188_000, mode = NextEpisodeThresholdMode.PERCENTAGE))
    }

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
