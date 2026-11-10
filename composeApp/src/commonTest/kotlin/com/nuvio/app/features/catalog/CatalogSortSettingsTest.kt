package com.nuvio.app.features.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSortSettingsTest {
    @Test
    fun selectionRoundTripsBySourceAndInvalidSelectionsFallBackSafely() {
        val addon = CatalogTarget.Addon(
            manifestUrl = "https://catalog.example/manifest.json",
            contentType = "series",
            catalogId = "popular",
        )
        val anotherAddon = addon.copy(catalogId = "new")
        val firstKey = requireNotNull(catalogSortKey(addon))
        val secondKey = requireNotNull(catalogSortKey(anotherAddon))

        val selections = updateCatalogSortSelections(emptyMap(), firstKey, CatalogSortOption.RELEASE_DESC)
        val state = decodeCatalogSortSettings(encodeCatalogSortSettings(CatalogSortSettingsUiState(selections)))
        assertEquals(CatalogSortOption.RELEASE_DESC, state.selections[firstKey])
        assertFalse(secondKey in state.selections)
        assertEquals(CatalogSortSettingsUiState(), decodeCatalogSortSettings("not json"))

        val invalid = decodeCatalogSortSettings(
            """{"selections":{"valid":"RATING_DESC","unknown":"not-an-option","default":"PROVIDER_ORDER"}}""",
        )
        assertEquals(mapOf("valid" to CatalogSortOption.RATING_DESC), invalid.selections)
        assertTrue(updateCatalogSortSelections(selections, firstKey, CatalogSortOption.PROVIDER_ORDER).isEmpty())
    }

    @Test
    fun selectionsAreBoundedAndTheMostRecentlySetCataloguesSurvive() {
        val selections = (0 until 150).fold(emptyMap<String, CatalogSortOption>()) { previous, index ->
            updateCatalogSortSelections(previous, "catalog-$index", CatalogSortOption.TITLE_DESC)
        }
        assertEquals(128, selections.size)
        assertFalse("catalog-0" in selections)
        assertTrue("catalog-149" in selections)

        val restored = decodeCatalogSortSettings(encodeCatalogSortSettings(CatalogSortSettingsUiState(selections)))
        assertEquals(selections, restored.selections)
    }

    @Test
    fun onlyExternalCataloguesHaveLocalSortKeys() {
        assertEquals(null, catalogSortKey(CatalogTarget.WatchLater))
        assertEquals(null, catalogSortKey(CatalogTarget.Airing("today")))
        assertEquals(
            null,
            catalogSortKey(CatalogTarget.Library(contentType = "series", sectionType = "watching")),
        )
    }
}
