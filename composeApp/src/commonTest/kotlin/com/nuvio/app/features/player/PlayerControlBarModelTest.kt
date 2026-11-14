package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControlBarModelTest {
    private val everyItem = PlayerBarItem.entries.toSet()

    @Test
    fun chaptersSplitAroundSkipSegmentsLikeTheDesktopBar() {
        val chapters = buildPlayerTimelineChapters(
            durationMs = 1_440_000L,
            segments = listOf(
                PlayerControlSkipSegment("outro", 1_320_000L, 1_420_000L),
                PlayerControlSkipSegment("recap", 0L, 60_000L),
                PlayerControlSkipSegment("intro", 90_000L, 180_000L),
            ),
        )

        assertEquals(
            listOf(
                PlayerTimelineChapter(PlayerTimelineChapterType.Recap, 0L, 60_000L),
                PlayerTimelineChapter(PlayerTimelineChapterType.Content, 60_000L, 90_000L),
                PlayerTimelineChapter(PlayerTimelineChapterType.Intro, 90_000L, 180_000L),
                PlayerTimelineChapter(PlayerTimelineChapterType.Content, 180_000L, 1_320_000L),
                PlayerTimelineChapter(PlayerTimelineChapterType.Credits, 1_320_000L, 1_420_000L),
                PlayerTimelineChapter(PlayerTimelineChapterType.Content, 1_420_000L, 1_440_000L),
            ),
            chapters,
        )
        assertEquals(PlayerTimelineChapterType.Intro, chapters.chapterAt(120_000L)?.type)
    }

    @Test
    fun chaptersDropShortOverlappingAndUnknownSegments() {
        val chapters = buildPlayerTimelineChapters(
            durationMs = 600_000L,
            segments = listOf(
                PlayerControlSkipSegment("intro", 30_000L, 90_000L),
                PlayerControlSkipSegment("recap", 60_000L, 120_000L), // overlaps the intro
                PlayerControlSkipSegment("intro", 200_000L, 200_500L), // under a second
                PlayerControlSkipSegment("mixed", 300_000L, 330_000L),
                PlayerControlSkipSegment("outro", 590_000L, 700_000L), // clipped to the duration
            ),
        )

        assertEquals(
            listOf(
                PlayerTimelineChapterType.Content,
                PlayerTimelineChapterType.Intro,
                PlayerTimelineChapterType.Content,
                PlayerTimelineChapterType.Credits,
            ),
            chapters.map { it.type },
        )
        assertEquals(600_000L, chapters.last().endMs)
    }

    @Test
    fun withoutSegmentsOrDurationTheBarIsOneChapter() {
        assertEquals(
            listOf(PlayerTimelineChapter(PlayerTimelineChapterType.Content, 0L, 300_000L)),
            buildPlayerTimelineChapters(300_000L, emptyList()),
        )
        assertEquals(1, buildPlayerTimelineChapters(0L, listOf(PlayerControlSkipSegment("intro", 0L, 5_000L))).size)
    }

    @Test
    fun skipIntervalsMapToChapterTypes() {
        val segments = listOf(
            SkipInterval(startTime = 1_300.0, endTime = 1_380.0, type = "ed", provider = "aniskip"),
            SkipInterval(startTime = 85.5, endTime = 175.5, type = "op", provider = "aniskip"),
            SkipInterval(startTime = 10.0, endTime = 5.0, type = "recap", provider = "introdb"),
            SkipInterval(startTime = 0.0, endTime = 60.0, type = "preview", provider = "introdb"),
        ).toPlayerControlSkipSegments()

        assertEquals(
            listOf(
                PlayerControlSkipSegment("intro", 85_500L, 175_500L),
                PlayerControlSkipSegment("outro", 1_300_000L, 1_380_000L),
            ),
            segments,
        )
    }

    @Test
    fun qualityChipMatchesTheDesktopLabels() {
        assertEquals("4K", playerVideoQualityLabel(3840, 2160))
        assertEquals("1080p", playerVideoQualityLabel(1920, 1080))
        assertEquals("1080p", playerVideoQualityLabel(1920, 800))
        assertEquals("1440p", playerVideoQualityLabel(2560, 1440))
        assertEquals("720p", playerVideoQualityLabel(1280, 720))
        assertEquals("480p", playerVideoQualityLabel(640, 480))
        assertNull(playerVideoQualityLabel(1920, 0))
    }

    @Test
    fun landscapePhonesKeepEveryControlInTheBar() {
        // 844 dp wide phone minus a 47 dp cutout inset each side and the 20 dp gutters.
        val plan = planPlayerBar(710f, everyItem)

        assertFalse(plan.compact)
        assertEquals(everyItem, plan.inline)
        assertTrue(playerBarWidthDp(plan.inline, plan.compact) <= 710f)
    }

    @Test
    fun portraitPhonesFoldLibraryAndViewControlsIntoMore() {
        val plan = planPlayerBar(350f, everyItem)

        assertTrue(plan.compact)
        assertEquals(setOf(PlayerBarItem.Subtitles, PlayerBarItem.Audio), plan.inline)
        assertTrue(playerBarWidthDp(plan.inline, plan.compact) <= 350f)
    }

    @Test
    fun narrowBarsStillFitTheirPlan() {
        for (width in 260..1_200 step 7) {
            val plan = planPlayerBar(width.toFloat(), everyItem)
            if (plan.inline.isNotEmpty()) {
                assertTrue(playerBarWidthDp(plan.inline, plan.compact) <= width, "bar overflows at $width dp")
            }
        }
    }

    @Test
    fun subtitlesClaimSpaceBeforeAnythingElse() {
        val plan = planPlayerBar(playerBarWidthDp(setOf(PlayerBarItem.Subtitles), compact = false), everyItem)

        assertEquals(setOf(PlayerBarItem.Subtitles), plan.inline)
    }

    @Test
    fun unavailableButtonsAreNeverPlanned() {
        val plan = planPlayerBar(1_000f, setOf(PlayerBarItem.Subtitles, PlayerBarItem.Audio, PlayerBarItem.Speed, PlayerBarItem.Aspect))

        assertEquals(setOf(PlayerBarItem.Subtitles, PlayerBarItem.Audio, PlayerBarItem.Speed, PlayerBarItem.Aspect), plan.inline)
    }
}
