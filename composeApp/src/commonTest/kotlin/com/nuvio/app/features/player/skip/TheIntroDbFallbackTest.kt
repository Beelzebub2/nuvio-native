package com.nuvio.app.features.player.skip

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheIntroDbFallbackTest {
    @Test
    fun mapsPublicApiIntervalsForMoviesAndSeriesWithoutGuessingOpenEndedCredits() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<TheIntroDbMediaResponse>(
            """{"tvdb_id":190,"type":"movie","intro":[{"start_ms":null,"end_ms":84000}],"recap":[{"start_ms":80000,"end_ms":100000}],"credits":[{"start_ms":8300000,"end_ms":null},{"start_ms":9000000,"end_ms":9200000}]}""",
        )
        val movie = response.skipIntervals(isMovie = true)
        assertEquals(
            listOf(
                SkipInterval(0.0, 84.0, "intro", "theintrodb"),
                SkipInterval(80.0, 100.0, "recap", "theintrodb"),
                SkipInterval(9000.0, 9200.0, "movie-credits", "theintrodb"),
            ),
            movie,
        )
        assertEquals("outro", response.skipIntervals(isMovie = false).last().type)
        assertTrue(movie.first().shouldAutoSkip(setOf(AutoSkipSegmentType.INTRO)))
        assertFalse(movie.last().shouldAutoSkip(setOf(AutoSkipSegmentType.INTRO)))
    }

    @Test
    fun rejectsMissingOrInvalidBoundariesInsteadOfSkippingUnknownMovieTail() {
        val result = TheIntroDbMediaResponse(
            intro = listOf(
                TheIntroDbSegment(startMs = -1, endMs = 90000),
                TheIntroDbSegment(startMs = 90000, endMs = 90000),
                TheIntroDbSegment(startMs = 30000, endMs = null),
            ),
            credits = listOf(TheIntroDbSegment(startMs = 6500000, endMs = null)),
        )
        assertTrue(result.skipIntervals(isMovie = true).isEmpty())
    }

    @Test
    fun movieMergePreservesPostCreditsSceneAndProviderPriority() {
        val movie = IntroDbSegmentsResponse(
            intro = IntroDbSegment(startSec = 10.0, endSec = 95.0),
            outro = IntroDbSegment(startSec = 1500.0, endSec = 1800.0),
            postCredits = IntroDbSegment(startSec = 1650.0, endSec = 1710.0),
        )
        val primary = movie.movieSkipIntervals()
        val fallback = listOf(
            SkipInterval(8.0, 90.0, "intro", "theintrodb"),
            SkipInterval(1400.0, 1780.0, "movie-credits", "theintrodb"),
        )
        val merged = SkipIntroRepository.mergeMovieSkipIntervals(primary, fallback)
        assertEquals("introdb", merged.first().provider)
        assertEquals(SkipInterval(1500.0, 1650.0, "movie-credits", "introdb"), merged[1])
        assertEquals("post-credits", merged.last().type)
        assertEquals(InternalSkipAction(1650_000L, true), merged[1].internalSkipAction(merged, 1800_000L))
        assertNull(merged.last().internalSkipAction(merged, 1800_000L))
    }

    @Test
    fun movieAndEpisodeUrlsUseCorrectPublicApiQuery() {
        assertEquals(
            "https://api.theintrodb.org/v4/media?imdb_id=tt9876543",
            theIntroDbMediaUrl("https://api.theintrodb.org/v4/", "tt9876543", null, null),
        )
        assertEquals(
            "https://api.theintrodb.org/v4/media?imdb_id=tt9876543&season=2&episode=5",
            theIntroDbMediaUrl("https://api.theintrodb.org/v4", "tt9876543", 2, 5),
        )
    }

    @Test
    fun resolvesMetadataAndTmdbIdsForNonAnimeSeriesOrMovies() = runBlocking {
        val tmdbLookups = mutableListOf<Int>()
        val resolver: suspend (Int) -> String? = {
            tmdbLookups += it
            if (it == 12345) "tt7654321" else null
        }
        val noAnime: suspend (String, String) -> String? = { _, _ -> null }
        assertEquals("tt1234567", resolveContentSkipImdbId(
            "tmdb:12345", "tmdb:12345:2:1", "tt1234567", resolver, noAnime,
        ))
        assertTrue(tmdbLookups.isEmpty(), "Metadata IMDb ID must avoid an unnecessary TMDB request")
        assertEquals("tt7654321", resolveContentSkipImdbId(
            "tmdb:12345", "episode-opaque", null, resolver, noAnime,
        ))
        assertEquals("tt7654321", resolveContentSkipImdbId(
            "series:12345", null, null, resolver, noAnime,
        ))
        assertNull(resolveContentSkipImdbId(
            "tmdb:12345&episode=42", null, null, resolver, noAnime,
        ))
    }
}
