package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingSourcesTest {
    @Test
    fun `stored legacy source names retain their meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.fromStorage("NUVIO_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, librarySourceModeFromStorage("TRAKT"))
        assertEquals(LibrarySourceMode.LOCAL, librarySourceModeFromStorage("LOCAL"))
    }

    @Test
    fun `connecting Trakt preserves local sources when preferences have never been set`() {
        val librarySource = librarySourceModeFromStorage(null)
        val progressSource = WatchProgressSource.fromStorage(null)
        assertEquals(LibrarySourceMode.LOCAL, effectiveLibrarySourceMode(librarySource) { false })
        assertEquals(LibrarySourceMode.LOCAL, effectiveLibrarySourceMode(librarySource) { true })
        assertEquals(WatchProgressSource.NUVIO_SYNC, effectiveWatchProgressSource(progressSource) { false })
        assertEquals(WatchProgressSource.NUVIO_SYNC, effectiveWatchProgressSource(progressSource) { true })
    }

    @Test
    fun `explicit Trakt preferences become active only after authorization`() {
        val librarySource = librarySourceModeFromStorage("TRAKT")
        val progressSource = WatchProgressSource.fromStorage("TRAKT")
        assertEquals(LibrarySourceMode.LOCAL, effectiveLibrarySourceMode(librarySource) { false })
        assertEquals(LibrarySourceMode.TRAKT, effectiveLibrarySourceMode(librarySource) { it == TrackingProviderId.TRAKT })
        assertEquals(WatchProgressSource.NUVIO_SYNC, effectiveWatchProgressSource(progressSource) { false })
        assertEquals(WatchProgressSource.TRAKT, effectiveWatchProgressSource(progressSource) { it == TrackingProviderId.TRAKT })
    }

    @Test
    fun `remote watch source falls back when its provider is disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false },
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false },
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `local sources have no remote provider`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }
}
