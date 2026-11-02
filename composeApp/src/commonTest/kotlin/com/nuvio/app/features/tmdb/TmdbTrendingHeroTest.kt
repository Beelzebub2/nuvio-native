package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TmdbTrendingHeroTest {
    @Test
    fun `trending fallback includes only unique watchable titles with artwork`() {
        val movie = TmdbDiscoverResult(id = 10, mediaType = "movie", title = "Film",
            posterPath = "/poster.jpg", backdropPath = "/backdrop.jpg", adult = false)
        val show = TmdbDiscoverResult(id = 22, mediaType = "tv", name = "Series",
            posterPath = "/series.jpg", adult = false)
        val trending = TmdbMetadataService.trendingHeroItems(listOf(
            TmdbDiscoverResult(id = 9, mediaType = "person", name = "Person", posterPath = "/face.jpg"),
            TmdbDiscoverResult(id = 11, mediaType = "movie", title = "Adult", posterPath = "/a.jpg", adult = true),
            TmdbDiscoverResult(id = 12, mediaType = "tv", name = "No artwork"),
            movie, movie, show,
        ))
        assertEquals(listOf("movie:tmdb:10", "series:tmdb:22"), trending.map { "${it.type}:${it.id}" })
        assertNotNull(trending.first().banner)
        assertNotNull(trending.last().poster)
    }
}
