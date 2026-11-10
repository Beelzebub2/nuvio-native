package com.nuvio.app.features.catalog

import com.nuvio.app.features.home.MetaPreview

internal enum class CatalogSortOption {
    PROVIDER_ORDER,
    TITLE_ASC,
    TITLE_DESC,
    RATING_DESC,
    RATING_ASC,
    RELEASE_DESC,
    RELEASE_ASC,
    ADDED_DESC,
    ADDED_ASC,
}

internal fun catalogSortKey(target: CatalogTarget): String? = when (target) {
    is CatalogTarget.Addon -> listOf(
        "addon", target.manifestUrl, target.contentType, target.catalogId, target.genre.orEmpty(),
    ).joinToString("|")
    is CatalogTarget.CollectionSource -> listOf(
        "collection", target.collectionId, target.folderId, target.sourceKey, target.contentType,
    ).joinToString("|")
    is CatalogTarget.Library, is CatalogTarget.WatchLater, is CatalogTarget.Airing -> null
}

internal fun availableCatalogSortOptions(items: List<MetaPreview>): List<CatalogSortOption> = buildList {
    add(CatalogSortOption.PROVIDER_ORDER)
    if (items.isEmpty()) return@buildList

    add(CatalogSortOption.TITLE_ASC)
    add(CatalogSortOption.TITLE_DESC)
    if (items.any { it.catalogRating() != null }) {
        add(CatalogSortOption.RATING_DESC)
        add(CatalogSortOption.RATING_ASC)
    }
    if (items.any { it.catalogReleaseDate() != null }) {
        add(CatalogSortOption.RELEASE_DESC)
        add(CatalogSortOption.RELEASE_ASC)
    }
    if (items.any { catalogDateKey(it.dateAdded) != null }) {
        add(CatalogSortOption.ADDED_DESC)
        add(CatalogSortOption.ADDED_ASC)
    }
}

internal fun effectiveCatalogSortOption(
    requested: CatalogSortOption,
    available: List<CatalogSortOption>,
): CatalogSortOption = requested.takeIf { it in available } ?: CatalogSortOption.PROVIDER_ORDER

/**
 * Sorting is a presentation-only projection of the accumulated provider pages. The input
 * remains in provider order so paging, duplicate detection, refreshes and cached results
 * continue to use the same sequence.
 */
internal fun sortCatalogItems(
    items: List<MetaPreview>,
    selected: CatalogSortOption,
): List<MetaPreview> {
    if (selected == CatalogSortOption.PROVIDER_ORDER || items.size < 2) return items

    val entries = items.mapIndexed { index, item ->
        CatalogSortEntry(
            index = index,
            item = item,
            title = item.name.ifBlank { item.id }.trim().lowercase().replace(LeadingCatalogTitleArticle, ""),
            rating = item.catalogRating(),
            releaseDate = item.catalogReleaseDate(),
            addedDate = catalogDateKey(item.dateAdded),
        )
    }
    return entries.sortedWith { first, second ->
        val comparison = when (selected) {
            CatalogSortOption.PROVIDER_ORDER -> 0
            CatalogSortOption.TITLE_ASC -> first.title.compareTo(second.title)
            CatalogSortOption.TITLE_DESC -> second.title.compareTo(first.title)
            CatalogSortOption.RATING_DESC -> compareOptional(first.rating, second.rating, descending = true)
            CatalogSortOption.RATING_ASC -> compareOptional(first.rating, second.rating, descending = false)
            CatalogSortOption.RELEASE_DESC -> compareOptional(first.releaseDate, second.releaseDate, descending = true)
            CatalogSortOption.RELEASE_ASC -> compareOptional(first.releaseDate, second.releaseDate, descending = false)
            CatalogSortOption.ADDED_DESC -> compareOptional(first.addedDate, second.addedDate, descending = true)
            CatalogSortOption.ADDED_ASC -> compareOptional(first.addedDate, second.addedDate, descending = false)
        }
        if (comparison != 0) comparison else first.index.compareTo(second.index)
    }.map { it.item }
}

private data class CatalogSortEntry(
    val index: Int,
    val item: MetaPreview,
    val title: String,
    val rating: Double?,
    val releaseDate: Int?,
    val addedDate: Int?,
)

private fun MetaPreview.catalogRating(): Double? =
    imdbRating?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }

private fun MetaPreview.catalogReleaseDate(): Int? =
    catalogDateKey(rawReleaseDate) ?: catalogDateKey(releaseInfo)

private fun <T : Comparable<T>> compareOptional(left: T?, right: T?, descending: Boolean): Int {
    if (left == null) return if (right == null) 0 else 1
    if (right == null) return -1
    return if (descending) right.compareTo(left) else left.compareTo(right)
}

private val LeadingCatalogTitleArticle = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)
private val CatalogDatePattern =
    Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2})(?:[Tt ][0-9].*)?)?)?$""")

/** ISO dates and standalone years only; unrecognized provider strings are not sortable dates. */
internal fun catalogDateKey(raw: String?): Int? {
    val match = raw?.trim()?.let(CatalogDatePattern::matchEntire) ?: return null
    val year = match.groupValues[1].toIntOrNull()?.takeIf { it in 1800..2199 } ?: return null
    val monthText = match.groupValues[2]
    val dayText = match.groupValues[3]
    val month = monthText.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val day = dayText.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    if (monthText.isNotBlank() && month !in 1..12) return null
    if (month != 0) {
        val daysInMonth = when (month) {
            2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (dayText.isNotBlank() && day !in 1..daysInMonth) return null
    }
    return year * 10000 + month * 100 + day
}
