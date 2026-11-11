package com.nuvio.app.features.catalog

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogSortingTest {
    @Test
    fun largeCatalogueSortsRatingsWithMissingMetadataLastAndStableTies() {
        val items = (0 until 12_000).map { position ->
            preview(
                id = "item-$position",
                rating = when (position % 4) {
                    0 -> "8.5"
                    1 -> "9.0"
                    2 -> null
                    else -> "8.5"
                },
            )
        }

        val sorted = sortCatalogItems(items, CatalogSortOption.RATING_DESC)
        assertEquals(items.size, sorted.size)
        assertEquals(items.size, sorted.map { it.id }.toSet().size)
        assertEquals(
            items.filter { it.imdbRating == "9.0" }.map { it.id },
            sorted.take(3_000).map { it.id },
        )
        assertEquals(
            items.filter { it.imdbRating == "8.5" }.map { it.id },
            sorted.drop(3_000).take(6_000).map { it.id },
        )
        assertEquals(
            items.filter { it.imdbRating == null }.map { it.id },
            sorted.takeLast(3_000).map { it.id },
        )
        assertEquals(items, sortCatalogItems(items, CatalogSortOption.PROVIDER_ORDER))
    }

    @Test
    fun titleAndReleaseOrderingUseAvailableMetadataAndKeepEqualItemsInProviderOrder() {
        val items = listOf(
            preview("the-first", name = "The Alpine", release = "2024"),
            preview("a-second", name = "A Beacon", release = "2025-03-01T00:00:00Z"),
            preview("third", name = "Alpine", release = "2025-03-01"),
            preview("unknown", name = "Zero", release = "not a date"),
            preview("older", name = "A Beacon", releaseInfo = "2023"),
        )

        assertEquals(
            listOf("the-first", "third", "a-second", "older", "unknown"),
            sortCatalogItems(items, CatalogSortOption.TITLE_ASC).map { it.id },
        )
        assertEquals(
            listOf("a-second", "third", "the-first", "older", "unknown"),
            sortCatalogItems(items, CatalogSortOption.RELEASE_DESC).map { it.id },
        )
        assertEquals(
            listOf("older", "the-first", "a-second", "third", "unknown"),
            sortCatalogItems(items, CatalogSortOption.RELEASE_ASC).map { it.id },
        )
    }

    @Test
    fun dateAddedRequiresExplicitValidDatesAndDoesNotUseReleaseDates() {
        val items = listOf(
            preview("first", release = "2025-01-01", added = "2024-12-31"),
            preview("second", release = "2023-01-01", added = "2025-02-28T18:00:00Z"),
            preview("invalid", release = "2026-01-01", added = "2025-02-30"),
        )
        val options = availableCatalogSortOptions(items)

        assertTrue(CatalogSortOption.ADDED_DESC in options)
        assertEquals(
            listOf("second", "first", "invalid"),
            sortCatalogItems(items, CatalogSortOption.ADDED_DESC).map { it.id },
        )
        assertEquals(
            listOf("first", "second", "invalid"),
            sortCatalogItems(items, CatalogSortOption.ADDED_ASC).map { it.id },
        )
        assertNull(catalogDateKey("2025-02-30"))
        assertNull(catalogDateKey("2025-00"))
        assertNull(catalogDateKey("2025-01-00"))
        assertNull(catalogDateKey("2025-01T12:00:00Z"))
        assertNull(catalogDateKey("unknown"))
        assertNotNull(catalogDateKey("2024-02-29T00:00:00Z"))
        assertNull(catalogDateKey("2023-02-29"))

        val withoutAdded = items.map { it.copy(dateAdded = null) }
        assertFalse(CatalogSortOption.ADDED_DESC in availableCatalogSortOptions(withoutAdded))
        assertEquals(
            CatalogSortOption.PROVIDER_ORDER,
            effectiveCatalogSortOption(
                CatalogSortOption.ADDED_DESC,
                availableCatalogSortOptions(withoutAdded),
            ),
        )
    }

    @Test
    fun unavailableRatingAndDateOptionsFallBackWithoutHidingProviderAndTitleSorts() {
        val items = listOf(
            preview("one", name = "A", rating = "not rated", release = "unknown"),
            preview("two", name = "B", rating = "12.0", releaseInfo = "2025 trailer"),
        )
        val available = availableCatalogSortOptions(items)

        assertEquals(
            listOf(CatalogSortOption.PROVIDER_ORDER, CatalogSortOption.TITLE_ASC, CatalogSortOption.TITLE_DESC),
            available,
        )
        assertEquals(
            CatalogSortOption.PROVIDER_ORDER,
            effectiveCatalogSortOption(CatalogSortOption.RATING_DESC, available),
        )
        assertEquals(
            listOf(CatalogSortOption.PROVIDER_ORDER),
            availableCatalogSortOptions(emptyList()),
        )
    }

    @Test
    fun sortingAccruedPagesDoesNotChangeAccumulationOrDuplicateOrder() {
        val first = listOf(preview("z", name = "Zebra"), preview("b", name = "Bravo"))
        val second = listOf(preview("b", name = "Duplicate"), preview("a", name = "Alpha"))
        val merged = mergeCatalogItems(first, second)
        val sorted = sortCatalogItems(merged, CatalogSortOption.TITLE_ASC)

        assertEquals(listOf("z", "b", "a"), merged.map { it.id })
        assertEquals(listOf("a", "b", "z"), sorted.map { it.id })
        assertEquals(listOf("z", "b", "a"), merged.map { it.id })
    }

    private fun preview(
        id: String,
        name: String = id,
        rating: String? = null,
        release: String? = null,
        releaseInfo: String? = null,
        added: String? = null,
    ): MetaPreview = MetaPreview(
        id = id,
        type = "movie",
        name = name,
        imdbRating = rating,
        rawReleaseDate = release,
        releaseInfo = releaseInfo,
        dateAdded = added,
    )
}
