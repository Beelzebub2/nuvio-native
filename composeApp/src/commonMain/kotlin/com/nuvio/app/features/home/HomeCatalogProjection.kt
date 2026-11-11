package com.nuvio.app.features.home

import com.nuvio.app.core.poster.reapplyCustomPosterUrls
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.absoluteValue
import kotlin.random.Random

internal data class HomeCatalogProjection(
    val sections: List<HomeCatalogSection>,
    val catalogHeroItems: List<MetaPreview>,
)

/** Reuses transformed rows and hero order while independently fetched catalogues arrive. */
internal class HomeCatalogProjectionCache {
    private val lock = SynchronizedObject()
    private val projectedRows = mutableMapOf<String, ProjectedRow>()
    private var lastHeroRequestKey: String? = null
    private var lastHeroSources: List<HomeCatalogSection> = emptyList()
    private var lastHeroItems: List<MetaPreview> = emptyList()
    private var hasHeroProjection = false

    fun clear() = synchronized(lock) {
        projectedRows.clear()
        lastHeroRequestKey = null
        lastHeroSources = emptyList()
        lastHeroItems = emptyList()
        hasHeroProjection = false
    }

    fun project(
        definitions: List<HomeCatalogDefinition>,
        sectionsByKey: Map<String, HomeCatalogSection>,
        snapshot: HomeCatalogSettingsSnapshot,
        posterPattern: String,
        todayIsoDate: String?,
        requestKey: String?,
        nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
    ): HomeCatalogProjection = synchronized(lock) {
        projectedRows.keys.retainAll(sectionsByKey.keys)

        fun transformed(definition: HomeCatalogDefinition): ProjectedRow? {
            val key = definition.cacheKey
            val source = sectionsByKey[key] ?: return null
            val previous = projectedRows[key]
            if (previous != null && previous.source === source &&
                previous.posterPattern == posterPattern && previous.todayIsoDate == todayIsoDate &&
                (todayIsoDate == null || nowEpochMs >= previous.computedAtEpochMs &&
                    nowEpochMs < previous.validUntilEpochMs)
            ) return previous

            val withPosters = source.copy(items = source.items.reapplyCustomPosterUrls(posterPattern))
            val projected = if (todayIsoDate != null) {
                withPosters.filterReleasedItems(todayIsoDate, nowEpochMs)
            } else {
                withPosters
            }
            // An exact release instant can arrive before the calendar day changes.
            val nextRelease = if (todayIsoDate == null) Long.MAX_VALUE else source.items.asSequence()
                .mapNotNull { item ->
                    parseEpisodeReleaseEpochMs(item.rawReleaseDate)
                        ?: parseEpisodeReleaseEpochMs(item.releaseInfo)
                }
                .filter { it > nowEpochMs }
                .minOrNull() ?: Long.MAX_VALUE
            return ProjectedRow(
                source = source,
                posterPattern = posterPattern,
                todayIsoDate = todayIsoDate,
                computedAtEpochMs = nowEpochMs,
                validUntilEpochMs = nextRelease,
                section = projected,
            ).also { projectedRows[key] = it }
        }

        val visibleSections = definitions
            .sortedBy { definition -> snapshot.preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .mapNotNull { definition ->
                val preference = snapshot.preferences[definition.key]
                if (preference?.enabled == false) return@mapNotNull null
                val row = transformed(definition) ?: return@mapNotNull null
                if (row.section.items.isEmpty()) return@mapNotNull null
                val title = preference?.customTitle.orEmpty()
                    .ifBlank { definition.titleFor(snapshot.showCatalogType) }
                row.withTitle(title)
            }

        val heroItems = if (!snapshot.heroEnabled) {
            emptyList()
        } else {
            val sources = definitions
                .filter { definition -> snapshot.preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> transformed(definition)?.section }
            if (!hasHeroProjection || lastHeroRequestKey != requestKey ||
                lastHeroSources.size != sources.size ||
                lastHeroSources.indices.any { index -> lastHeroSources[index] !== sources[index] }
            ) {
                val random = Random((requestKey?.hashCode() ?: 0).absoluteValue + 1)
                lastHeroItems = sources.flatMap { section -> section.items }
                    .distinctBy { item -> "${item.type}:${item.id}" }
                    .shuffled(random)
                    .take(HOME_HERO_ITEM_LIMIT)
                lastHeroRequestKey = requestKey
                lastHeroSources = sources
                hasHeroProjection = true
            }
            lastHeroItems
        }
        HomeCatalogProjection(sections = visibleSections, catalogHeroItems = heroItems)
    }

    private class ProjectedRow(
        val source: HomeCatalogSection,
        val posterPattern: String,
        val todayIsoDate: String?,
        val computedAtEpochMs: Long,
        val validUntilEpochMs: Long,
        val section: HomeCatalogSection,
    ) {
        private var lastTitle: String? = null
        private var titledSection: HomeCatalogSection? = null

        fun withTitle(title: String): HomeCatalogSection {
            if (lastTitle == title) return titledSection ?: section
            return (if (section.title == title) section else section.copy(title = title)).also {
                lastTitle = title
                titledSection = it
            }
        }
    }
}
