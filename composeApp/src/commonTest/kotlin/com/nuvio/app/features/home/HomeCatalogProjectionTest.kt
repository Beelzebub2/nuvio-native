package com.nuvio.app.features.home

import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class HomeCatalogProjectionTest {
    private val first = definition("first")
    private val second = definition("second")

    @Test
    fun `a fetched row appears immediately and stays projected when later rows finish`() {
        val cache = HomeCatalogProjectionCache()
        val settings = snapshot(
            first.key to preference(order = 1),
            second.key to preference(order = 0),
        )
        val firstRow = section(first, "tt0000001")
        val secondRow = section(second, "tt0000002")

        val firstPaint = cache.project(
            definitions = listOf(first, second),
            sectionsByKey = mapOf(first.cacheKey to firstRow),
            snapshot = settings,
            posterPattern = "https://image.test/{id}",
            todayIsoDate = null,
            requestKey = "stable",
        )
        assertEquals(listOf(first.key), firstPaint.sections.map { it.key })
        assertEquals(listOf("tt0000001"), firstPaint.catalogHeroItems.map { it.id })

        val completed = cache.project(
            definitions = listOf(first, second),
            sectionsByKey = mapOf(first.cacheKey to firstRow, second.cacheKey to secondRow),
            snapshot = settings,
            posterPattern = "https://image.test/{id}",
            todayIsoDate = null,
            requestKey = "stable",
        )
        assertEquals(listOf(second.key, first.key), completed.sections.map { it.key })
        assertSame(firstPaint.sections.single(), completed.sections[1])
        assertEquals(setOf("tt0000001", "tt0000002"), completed.catalogHeroItems.map { it.id }.toSet())

        val unchanged = cache.project(
            definitions = listOf(first, second),
            sectionsByKey = mapOf(first.cacheKey to firstRow, second.cacheKey to secondRow),
            snapshot = settings,
            posterPattern = "https://image.test/{id}",
            todayIsoDate = null,
            requestKey = "stable",
        )
        assertSame(completed.sections[0], unchanged.sections[0])
        assertSame(completed.sections[1], unchanged.sections[1])
        assertSame(completed.catalogHeroItems, unchanged.catalogHeroItems)
    }

    @Test
    fun `settings reorder rename and hero selection update without replacing source rows`() {
        val cache = HomeCatalogProjectionCache()
        val rows = mapOf(
            first.cacheKey to section(first, "tt0000001"),
            second.cacheKey to section(second, "tt0000002"),
        )
        val definitions = listOf(first, second)
        val initial = cache.project(
            definitions, rows,
            snapshot(first.key to preference(order = 0), second.key to preference(order = 1)),
            posterPattern = "", todayIsoDate = null, requestKey = "stable",
        )
        val typeTitlesHidden = cache.project(
            definitions, rows,
            snapshot(first.key to preference(order = 0), second.key to preference(order = 1))
                .copy(showCatalogType = false),
            posterPattern = "", todayIsoDate = null, requestKey = "stable",
        )
        assertEquals(listOf("Catalog first", "Catalog second"), typeTitlesHidden.sections.map { it.title })
        assertSame(initial.sections[0].items, typeTitlesHidden.sections[0].items)
        assertSame(initial.catalogHeroItems, typeTitlesHidden.catalogHeroItems)

        val changed = cache.project(
            definitions, rows,
            snapshot(
                first.key to preference(order = 2, customTitle = "Updated", enabled = false, heroSourceEnabled = true),
                second.key to preference(order = 0),
            ),
            posterPattern = "", todayIsoDate = null, requestKey = "stable",
        )
        assertEquals(listOf(second.key), changed.sections.map { it.key })
        assertSame(initial.sections[1], changed.sections[0])
        assertSame(initial.catalogHeroItems, changed.catalogHeroItems)

        val reenabled = cache.project(
            definitions, rows,
            snapshot(
                first.key to preference(order = 2, customTitle = "Updated", heroSourceEnabled = false),
                second.key to preference(order = 0),
            ),
            posterPattern = "", todayIsoDate = null, requestKey = "stable",
        )
        assertEquals(listOf(second.key, first.key), reenabled.sections.map { it.key })
        assertEquals("Updated", reenabled.sections[1].title)
        assertEquals(listOf("tt0000002"), reenabled.catalogHeroItems.map { it.id })

        val heroDisabled = cache.project(
            definitions, rows,
            snapshot(first.key to preference(), second.key to preference(), heroEnabled = false),
            posterPattern = "", todayIsoDate = null, requestKey = "stable",
        )
        assertEquals(emptyList(), heroDisabled.catalogHeroItems)
    }

    @Test
    fun `poster pattern and changed source content invalidate cached rows and hero`() {
        val cache = HomeCatalogProjectionCache()
        val original = section(first, "tt0000001")
        val rows = mapOf(first.cacheKey to original)
        val settings = snapshot(first.key to preference())
        fun project(source: Map<String, HomeCatalogSection>, posterPattern: String) = cache.project(
            listOf(first), source, settings, posterPattern, todayIsoDate = null, requestKey = "stable",
        )

        val initial = project(rows, "https://first.test/{id}")
        assertEquals("https://first.test/tt0000001", initial.sections.single().items.single().poster)
        val replacementPattern = project(rows, "https://second.test/{id}")
        assertNotSame(initial.sections.single(), replacementPattern.sections.single())
        assertEquals("https://second.test/tt0000001", replacementPattern.sections.single().items.single().poster)
        assertEquals("https://second.test/tt0000001", replacementPattern.catalogHeroItems.single().poster)

        val restored = project(rows, "")
        assertEquals("https://source.test/tt0000001", restored.sections.single().items.single().poster)

        val replacedSource = project(mapOf(first.cacheKey to section(first, "tt0000003")), "")
        assertEquals(listOf("tt0000003"), replacedSource.sections.single().items.map { it.id })
        assertEquals(listOf("tt0000003"), replacedSource.catalogHeroItems.map { it.id })
        assertNotSame(restored.catalogHeroItems, replacedSource.catalogHeroItems)
    }

    @Test
    fun `release filter invalidates at exact release instant without day change`() {
        val cache = HomeCatalogProjectionCache()
        val releaseAt = requireNotNull(parseEpisodeReleaseEpochMs("2026-05-06T12:00:00Z"))
        val future = section(first, "tt0000001").copy(items = listOf(
            MetaPreview(
                id = "tt0000001", type = "movie", name = "Timed release",
                rawReleaseDate = "2026-05-06T12:00:00Z",
            ),
        ))
        val settings = snapshot(first.key to preference(), hideUnreleased = true)
        fun project(at: Long, today: String = "2026-05-06") = cache.project(
            definitions = listOf(first),
            sectionsByKey = mapOf(first.cacheKey to future),
            snapshot = settings,
            posterPattern = "",
            todayIsoDate = today,
            requestKey = "stable",
            nowEpochMs = at,
        )

        val before = project(releaseAt - 1L)
        assertEquals(emptyList(), before.sections)
        assertEquals(emptyList(), before.catalogHeroItems)
        val after = project(releaseAt)
        assertEquals(listOf("tt0000001"), after.sections.single().items.map { it.id })
        assertEquals(listOf("tt0000001"), after.catalogHeroItems.map { it.id })
        assertSame(after.sections.single(), project(releaseAt + 100L).sections.single())
        assertNotSame(after.sections.single(), project(releaseAt + 100L, today = "2026-05-07").sections.single())
    }

    @Test
    fun `clearing projection cache drops prior profile and hero references`() {
        val cache = HomeCatalogProjectionCache()
        val rows = mapOf(first.cacheKey to section(first, "tt0000001"))
        val settings = snapshot(first.key to preference())
        fun project() = cache.project(
            listOf(first), rows, settings, "", todayIsoDate = null, requestKey = "stable",
        )
        val prior = project()
        cache.clear()
        val subsequent = project()
        assertNotSame(prior.sections.single(), subsequent.sections.single())
        assertNotSame(prior.catalogHeroItems, subsequent.catalogHeroItems)
    }

    private fun definition(id: String) = HomeCatalogDefinition(
        key = id,
        defaultTitle = "Default $id",
        catalogName = "Catalog $id",
        addonName = "Test",
        manifestUrl = "https://source.test/manifest.json",
        type = "movie",
        catalogId = id,
        supportsPagination = false,
        descriptorSignature = "v1",
    )

    private fun section(definition: HomeCatalogDefinition, itemId: String) = HomeCatalogSection(
        key = definition.key,
        title = definition.defaultTitle,
        subtitle = "Test",
        addonName = "Test",
        target = CatalogTarget.Addon(
            manifestUrl = definition.manifestUrl,
            contentType = "movie",
            catalogId = definition.catalogId,
        ),
        items = listOf(MetaPreview(
            id = itemId,
            type = "movie",
            name = itemId,
            poster = "https://source.test/$itemId",
        )),
    )

    private fun preference(
        order: Int = 0,
        customTitle: String = "",
        enabled: Boolean = true,
        heroSourceEnabled: Boolean = true,
    ) = HomeCatalogPreference(customTitle, enabled, heroSourceEnabled, order)

    private fun snapshot(
        vararg preferences: Pair<String, HomeCatalogPreference>,
        heroEnabled: Boolean = true,
        hideUnreleased: Boolean = false,
    ) = HomeCatalogSettingsSnapshot(
        heroEnabled = heroEnabled,
        showCatalogType = true,
        hideUnreleasedContent = hideUnreleased,
        preferences = preferences.toMap(),
    )
}
