package com.nuvio.app.features.catalog

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogLoadMoreGateTest {
    @Test
    fun `sorted page insertion does not fetch repeatedly without user scrolling`() {
        val gate = CatalogLoadMoreGate()
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
        gate.observeScroll(scrolling = false, index = 0, offset = 0)
        gate.observeScroll(scrolling = true, index = 0, offset = 10)
        assertTrue(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
        // A new page that sorts before the anchored key can leave the viewport near the tail.
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
        gate.observeScroll(scrolling = true, index = 0, offset = 20)
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
        gate.observeScroll(scrolling = false, index = 0, offset = 20)
        gate.observeScroll(scrolling = true, index = 0, offset = 30)
        assertTrue(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
    }

    @Test
    fun `initial underfilled viewport prefetch is bounded and never runs while loading`() {
        val gate = CatalogLoadMoreGate()
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = true,
            initialViewportUnderfilled = true))
        repeat(12) {
            assertTrue(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
                initialViewportUnderfilled = true))
        }
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = true))
        assertFalse(gate.shouldLoad(nearEnd = false, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
    }

    @Test
    fun `loading and exhausted catalogue do not consume forward scroll authorization`() {
        val gate = CatalogLoadMoreGate()
        gate.observeScroll(scrolling = true, index = 1, offset = 0)
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = true,
            initialViewportUnderfilled = false))
        assertFalse(gate.shouldLoad(nearEnd = true, canLoadMore = false, isLoading = false,
            initialViewportUnderfilled = false))
        assertTrue(gate.shouldLoad(nearEnd = true, canLoadMore = true, isLoading = false,
            initialViewportUnderfilled = false))
    }
}
