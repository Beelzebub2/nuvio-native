package com.nuvio.app.features.myanimelist

import com.nuvio.app.features.details.MetaDetails
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MalAutoMatchTest {
    private val source = MetaDetails(
        id = "tt1234567", type = "series", name = "The Secret Planet",
        releaseInfo = "2024", genres = listOf("Anime"),
    )

    @Test fun uniqueExactTitlesAndAliasesAreMatchedAutomatically() {
        assertEquals(42, matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024, mediaType = "tv"),
        )))
        assertEquals(42, matchMalSearchCandidate(source, listOf(
            MalAnime(42, "Secret Planet", englishTitle = "The Secret Planet", startYear = 2024),
        )))
        assertEquals(42, matchMalSearchCandidate(source, listOf(
            MalAnime(42, "Himitsu no Wakusei", alternativeTitles = listOf("THE SECRET PLANET!"), startYear = 2024),
        )))
    }

    @Test fun fuzzyTitlesAndAmbiguousMatchesDoNotAutoLink() {
        assertNull(matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024),
            MalAnime(43, "The Secret Planet", startYear = 2024),
        )))
        assertNull(matchMalSearchCandidate(source.copy(name = "The Secret Planet Season 2"), listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024),
        )))
    }

    @Test fun yearAndMediaTypeKeepSeasonsAndFilmsSeparate() {
        assertEquals(43, matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2023),
            MalAnime(43, "The Secret Planet", startYear = 2024),
        )))
        assertNull(matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024, mediaType = "movie"),
        )))
        assertEquals(42, matchMalSearchCandidate(source.copy(type = "movie"), listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024, mediaType = "movie"),
        )))
    }

    @Test fun completedAccountEntriesCanDisambiguateButTwoCannot() {
        assertEquals(43, matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024),
            MalAnime(43, "The Secret Planet", startYear = 2024,
                listStatus = MalListStatus(MalStatus.COMPLETED, 9, 12)),
        )))
        assertNull(matchMalSearchCandidate(source, listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024,
                listStatus = MalListStatus(MalStatus.COMPLETED, 10, 12)),
            MalAnime(43, "The Secret Planet", startYear = 2024,
                listStatus = MalListStatus(MalStatus.COMPLETED, 9, 12)),
        )))
    }

    @Test fun nonAnimeCannotAutoLinkByTitle() {
        assertNull(matchMalSearchCandidate(source.copy(genres = listOf("Drama")), listOf(
            MalAnime(42, "The Secret Planet", startYear = 2024),
        )))
    }
}
