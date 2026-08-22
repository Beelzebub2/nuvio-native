package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSurfaceLifetimeTest {
    @Test
    fun desktopKeepsNativeHostDuringResolvedSourceGap() {
        assertTrue(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = true,
                desktop = true,
            ),
        )
    }

    @Test
    fun unresolvedDesktopSourceWithoutControllerDoesNotCreateSurface() {
        assertFalse(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = false,
                desktop = true,
            ),
        )
    }

    @Test
    fun nonDesktopSourceGapDoesNotRetainSurface() {
        assertFalse(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = true,
                desktop = false,
            ),
        )
    }

    @Test
    fun currentSourceAlwaysRendersSurface() {
        assertTrue(
            shouldRenderPlayerSurface(
                hasCurrentSource = true,
                hasLifecycleController = false,
                desktop = false,
            ),
        )
    }
}
