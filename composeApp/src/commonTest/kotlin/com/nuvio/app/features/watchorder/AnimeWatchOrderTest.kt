package com.nuvio.app.features.watchorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeWatchOrderTest {
    private val example = """
      {
        "franchiseId": "junban:7404", "franchiseName": "KonoSuba",
        "mode": "recommended", "confidence": "verified",
        "steps": [
          {"malId":30831,"title":"Season 1","kind":"tv","date":"2016-01-14","required":true},
          {"malId":32937,"title":"Season 2","kind":"tv","date":"2017-01-12","required":true},
          {"malId":38040,"title":"Legend of Crimson","kind":"movie","date":"2019-08-30","required":true},
          {"malId":51958,"title":"Explosion prequel","kind":"tv","date":"2023-04-06","required":false},
          {"malId":49458,"title":"Season 3","kind":"tv","date":"2024-04-10","required":true}
        ]
      }
    """.trimIndent()

    @Test fun movieIsNextAfterKonosubaSeasonTwo() {
        val order = AnimeWatchOrderRepository.parse(example)!!
        assertTrue(order.verified)
        assertEquals(38040, order.nextAfter(32937, includeOptional = false)?.malId)
        assertEquals(49458, order.nextAfter(38040, includeOptional = false)?.malId)
        assertEquals(51958, order.nextAfter(38040, includeOptional = true)?.malId)
        assertNull(order.nextAfter(49458, includeOptional = false))
        assertNull(order.nextAfter(49458, includeOptional = true))
    }

    @Test fun inferredOrderIsNotMarkedVerified() {
        assertFalse(AnimeWatchOrderRepository.parse(example.replace("verified", "inferred"))!!.verified)
        assertEquals("partial", AnimeWatchOrderRepository.parse(example.replace("verified", "partial"))!!.confidence)
    }

    @Test fun nextRecommendationSkipsCompletedOptionalAndFutureReleases() {
        val order = AnimeWatchOrderRepository.parse(example)!!
        assertEquals(49458, order.nextUnwatchedAfter(
            currentMalId = 38040, includeOptional = true, completedMalIds = setOf(51958),
            today = "2026-11-09",
        )?.malId)
        assertNull(order.nextUnwatchedAfter(
            currentMalId = 32937, includeOptional = false,
            completedMalIds = setOf(38040, 49458), today = "2026-11-09",
        ))
        assertNull(order.nextUnwatchedAfter(
            currentMalId = 32937, includeOptional = false,
            completedMalIds = emptySet(), today = "2016-02-02",
        ))
        val unknownRelease = order.copy(
            steps = order.steps.map { if (it.malId == 38040) it.copy(releaseDate = null) else it },
        )
        assertEquals(49458, unknownRelease.nextUnwatchedAfter(
            currentMalId = 32937, includeOptional = false,
            completedMalIds = emptySet(), today = "2026-11-09",
        )?.malId)
    }

    @Test fun previewCentersOnTheCurrentEntryAndBoundsTheList() {
        val order = AnimeWatchOrderRepository.parse(example)!!
        val required = order.steps.filter { it.required }
        assertEquals(listOf(30831, 32937, 38040),
            watchOrderPreviewWindow(required, 32937, 3).map { it.malId })
        assertEquals(listOf(32937, 38040, 49458),
            watchOrderPreviewWindow(required, 49458, 3).map { it.malId })
        assertEquals(emptyList(), watchOrderPreviewWindow(required, 49458, 0))
        assertEquals(required, watchOrderPreviewWindow(required, null, 10))
    }

    @Test fun franchiseCardNeverOverridesWithinSeriesAutoplayOrShowsOnErrors() {
        assertFalse(shouldOfferFranchiseContinuation(
            hasEnded = true, hasPlaybackError = false, hasNextEpisode = true,
            isSeries = true, currentEpisodeInMetadata = true,
        ))
        assertFalse(shouldOfferFranchiseContinuation(
            hasEnded = true, hasPlaybackError = true, hasNextEpisode = false,
            isSeries = true, currentEpisodeInMetadata = true,
        ))
        assertFalse(shouldOfferFranchiseContinuation(
            hasEnded = false, hasPlaybackError = false, hasNextEpisode = false,
            isSeries = true, currentEpisodeInMetadata = true,
        ))
        assertFalse(shouldOfferFranchiseContinuation(
            hasEnded = true, hasPlaybackError = false, hasNextEpisode = false,
            isSeries = true, currentEpisodeInMetadata = false,
        ))
        assertTrue(shouldOfferFranchiseContinuation(
            hasEnded = true, hasPlaybackError = false, hasNextEpisode = false,
            isSeries = false, currentEpisodeInMetadata = false,
        ))
    }

    @Test fun malformedAndDuplicateResponsesFailClosed() {
        assertNull(AnimeWatchOrderRepository.parse("{}"))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"malId\":38040", "\"malId\":32937")))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"kind\":\"movie\"", "\"kind\":\"torrent\"")))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"required\":true", "\"required\":\"true\"")))
    }
}
