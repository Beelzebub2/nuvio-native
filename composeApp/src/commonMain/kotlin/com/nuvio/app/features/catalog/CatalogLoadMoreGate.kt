package com.nuvio.app.features.catalog

/**
 * Paging must follow new forward scrolling, not a recomposition that moves the anchored item
 * when sorting inserts a fetched page before it. Allow a bounded first-page viewport fill.
 */
internal class CatalogLoadMoreGate {
    private var forwardScrolls = 0
    private var consumedScrolls = 0
    private var initialFillRequests = 0
    private var previousIndex = 0
    private var previousOffset = 0
    private var gestureCounted = false

    fun observeScroll(scrolling: Boolean, index: Int, offset: Int) {
        if (!scrolling) gestureCounted = false
        if (scrolling && !gestureCounted &&
            (index > previousIndex || (index == previousIndex && offset > previousOffset))
        ) {
            forwardScrolls += 1
            gestureCounted = true
        }
        previousIndex = index
        previousOffset = offset
    }

    fun shouldLoad(
        nearEnd: Boolean,
        canLoadMore: Boolean,
        isLoading: Boolean,
        initialViewportUnderfilled: Boolean,
    ): Boolean {
        if (!nearEnd || !canLoadMore || isLoading) return false
        if (initialViewportUnderfilled && initialFillRequests < 12) {
            initialFillRequests += 1
            return true
        }
        if (forwardScrolls <= consumedScrolls) return false
        consumedScrolls = forwardScrolls
        return true
    }
}

internal data class CatalogPaginationViewport(
    val scrolling: Boolean,
    val firstIndex: Int,
    val firstOffset: Int,
    val lastVisibleIndex: Int,
    val totalItems: Int,
    val canScrollForward: Boolean,
)
