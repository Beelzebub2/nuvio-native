package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRefreshPlanningTest {
    private val visible = definition("visible")
    private val heroOnly = definition("hero")
    private val hidden = definition("hidden")

    private fun definition(id: String) = HomeCatalogDefinition(
        key = id,
        defaultTitle = id,
        catalogName = id,
        addonName = "Test",
        manifestUrl = "https://example.test/manifest.json",
        type = "series",
        catalogId = id,
        supportsPagination = false,
        descriptorSignature = "test",
    )

    private fun snapshot(heroEnabled: Boolean = true) = HomeCatalogSettingsSnapshot(
        heroEnabled = heroEnabled,
        showCatalogType = true,
        hideUnreleasedContent = false,
        preferences = mapOf(
            visible.key to HomeCatalogPreference("", enabled = true, heroSourceEnabled = false, order = 0),
            heroOnly.key to HomeCatalogPreference("", enabled = false, heroSourceEnabled = true, order = 1),
            hidden.key to HomeCatalogPreference("", enabled = false, heroSourceEnabled = false, order = 2),
        ),
    )

    @Test
    fun initialRefreshStillWarmsHiddenRowsForImmediateReenable() {
        val result = prioritizeDefinitions(
            listOf(hidden, heroOnly, visible), snapshot(), forcedRefresh = false,
        )
        assertEquals(listOf(visible.key, heroOnly.key, hidden.key), result.map { it.key })
    }

    @Test
    fun forcedRefreshSkipsPreviouslyLoadedInvisibleRows() {
        val result = prioritizeDefinitions(
            listOf(hidden, heroOnly, visible), snapshot(), forcedRefresh = true,
            cachedKeys = setOf(hidden.cacheKey),
        )
        assertEquals(listOf(visible.key, heroOnly.key), result.map { it.key })
    }

    @Test
    fun forcedRefreshStillAttemptsHiddenRowsWithoutAnyCachedContent() {
        val result = prioritizeDefinitions(
            listOf(hidden, heroOnly, visible), snapshot(), forcedRefresh = true,
            cachedKeys = emptySet(),
        )
        assertEquals(listOf(visible.key, heroOnly.key, hidden.key), result.map { it.key })
    }

    @Test
    fun disabledHeroAlsoSkipsCachedHeroOnlyRows() {
        val result = prioritizeDefinitions(
            listOf(hidden, heroOnly, visible), snapshot(heroEnabled = false),
            forcedRefresh = true,
            cachedKeys = setOf(heroOnly.cacheKey, hidden.cacheKey),
        )
        assertEquals(listOf(visible.key), result.map { it.key })
    }
}
