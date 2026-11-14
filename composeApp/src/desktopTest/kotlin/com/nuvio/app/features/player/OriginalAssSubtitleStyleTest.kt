package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalAssSubtitleStyleTest {
    @Test
    fun desktopPreservesOriginalAssStylingByDefault() {
        assertTrue(PlayerSettingsUiState().useLibass)
    }

    @Test
    fun explicitlySelectedCustomStylingRemainsAvailable() {
        assertFalse(PlayerSettingsUiState(useLibass = false).useLibass)
    }
}
