package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIntervalLookupTest {
    @Test
    fun resolvesImdbEpisodeFromPlayerMetadata() {
        assertEquals(
            SkipIntervalLookup.Imdb("tt1234567", season = 2, episode = 4),
            resolveSkipIntervalLookup("tt1234567:2:4", season = 2, episode = 4),
        )
    }

    @Test
    fun resolvesMalEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Mal("62322", episode = 4),
            resolveSkipIntervalLookup("mal:62322:4", season = 1, episode = null),
        )
    }

    @Test
    fun usesActiveEpisodeForSeasonQualifiedMalId() {
        assertEquals(
            SkipIntervalLookup.Mal("63375", episode = 5),
            resolveSkipIntervalLookup("mal:63375:1:5", season = 1, episode = 5),
        )
    }

    @Test
    fun resolvesKitsuEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Kitsu("12345", episode = 8),
            resolveSkipIntervalLookup("kitsu:12345:8", season = null, episode = null),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteIds() {
        assertNull(resolveSkipIntervalLookup("tmdb:123", season = 1, episode = 2))
        assertNull(resolveSkipIntervalLookup("mal:62322", season = 1, episode = null))
    }

    @Test
    fun genericTvVideoIdsUseResolvedParentImdbIdWithoutOverridingAnimeIds() {
        val show = SkipIntervalLookup.Imdb("tt9876543", season = 2, episode = 5)
        assertEquals(show, resolveSkipIntervalLookup("tmdb:12345:2:5", 2, 5, "tt9876543"))
        assertEquals(show, resolveSkipIntervalLookup(null, 2, 5, "tt9876543"))
        assertEquals(show, resolveSkipIntervalLookup("opaque-add-on-id", 2, 5, "tt9876543"))
        assertEquals(
            SkipIntervalLookup.Mal("62322", episode = 5),
            resolveSkipIntervalLookup("mal:62322:5", 2, 5, "tt9876543"),
        )
    }

    @Test
    fun recoversAnimeIdentityFromParentWhenEpisodeIdIsOpaque() {
        assertEquals(
            SkipIntervalLookup.Mal("123456", episode = 5),
            resolveContentSkipIntervalLookup("tmdb:9876:2:5", "mal:123456", 2, 5),
        )
        assertEquals(
            SkipIntervalLookup.Kitsu("654321", episode = 8),
            resolveContentSkipIntervalLookup("episode-from-addon", "kitsu:654321", 1, 8),
        )
        assertEquals(
            SkipIntervalLookup.Imdb("tt9876543", season = 2, episode = 5),
            resolveContentSkipIntervalLookup("tt9876543:2:5", "mal:123456", 2, 5),
        )
        assertEquals(
            SkipIntervalLookup.Imdb("tt9876543", season = 2, episode = 5),
            resolveContentSkipIntervalLookup("unmapped-video", "opaque-parent", 2, 5, "tt9876543"),
        )
        assertNull(resolveContentSkipIntervalLookup("unmapped-video", "mal:123456", 2, null))
    }

    @Test
    fun fallbackRejectsMissingEpisodeAndUntrustedImdbValues() {
        assertNull(resolveSkipIntervalLookup("tmdb:123", 1, null, "tt9876543"))
        assertNull(resolveSkipIntervalLookup("tmdb:123", 1, 3, "tt9876543&season=999"))
        assertNull(resolveSkipIntervalLookup("tmdb:123", -1, 3, "tt9876543"))
        assertNull(resolveSkipIntervalLookup("tmdb:123", 1, 0, "tt9876543"))
    }
}
