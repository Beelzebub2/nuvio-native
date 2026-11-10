package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingRailPriority
import com.nuvio.app.features.watchprogress.isExplicitLiveMetaType

/** Content types are reported by the provider. Unknown types stay visible in the Other row. */
internal enum class ContinueWatchingRailKind {
    COMBINED,
    MOVIES,
    SERIES,
    ANIME,
    OTHER,
}

internal data class ContinueWatchingRail(
    val kind: ContinueWatchingRailKind,
    val items: List<ContinueWatchingItem>,
)

/**
 * Group an already ordered, deduplicated Continue Watching snapshot without changing playback
 * identities, the source order within any row, or the persistence/sync behavior.
 */
internal fun buildContinueWatchingRails(
    items: List<ContinueWatchingItem>,
    separateRowsByType: Boolean,
    hideLiveContent: Boolean,
    priority: ContinueWatchingRailPriority = ContinueWatchingRailPriority.MOVIES,
    animeLibraryIds: Set<String> = emptySet(),
): List<ContinueWatchingRail> {
    val visibleItems = if (hideLiveContent) items.filterNot { it.isExplicitLiveContent() } else items
    if (visibleItems.isEmpty()) return emptyList()
    if (!separateRowsByType) return listOf(ContinueWatchingRail(ContinueWatchingRailKind.COMBINED, visibleItems))

    val grouped = visibleItems.groupBy { item ->
        val type = item.parentMetaType.trim().lowercase()
        when {
            type in setOf("anime", "anime_series", "anime-series", "anime_movie", "anime-movie") ->
                ContinueWatchingRailKind.ANIME
            type in setOf("movie", "film", "series", "tv", "show", "tv_series", "tv-series") &&
                (item.mediaCategory.equals("anime", ignoreCase = true) || item.parentMetaId in animeLibraryIds) ->
                ContinueWatchingRailKind.ANIME
            type == "movie" || type == "film" -> ContinueWatchingRailKind.MOVIES
            type in setOf("series", "tv", "show", "tv_series", "tv-series") -> ContinueWatchingRailKind.SERIES
            else -> ContinueWatchingRailKind.OTHER
        }
    }
    val prioritized = when (priority) {
        ContinueWatchingRailPriority.MOVIES -> ContinueWatchingRailKind.MOVIES
        ContinueWatchingRailPriority.SERIES -> ContinueWatchingRailKind.SERIES
        ContinueWatchingRailPriority.ANIME -> ContinueWatchingRailKind.ANIME
    }
    val order = listOf(
        prioritized,
        ContinueWatchingRailKind.MOVIES,
        ContinueWatchingRailKind.SERIES,
        ContinueWatchingRailKind.ANIME,
        ContinueWatchingRailKind.OTHER,
    ).distinct()

    return order.mapNotNull { kind ->
        grouped[kind]?.takeIf { it.isNotEmpty() }?.let { ContinueWatchingRail(kind, it) }
    }
}

/** Never infer live content from a title or a regular series `tv` type. */
internal fun ContinueWatchingItem.isExplicitLiveContent(): Boolean =
    parentMetaType.isExplicitLiveMetaType()
