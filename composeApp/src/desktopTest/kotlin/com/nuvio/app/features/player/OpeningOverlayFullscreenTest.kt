package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test

class OpeningOverlayFullscreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fullscreenRemainsAvailableWhilePlaybackEngineStarts() {
        val fullscreen = AtomicBoolean(false)
        val unregisterFullscreen = registerDesktopAppFullscreenToggle(
            handler = { fullscreen.set(!fullscreen.get()) },
            isFullscreen = { fullscreen.get() },
        )
        var loadingMessage by mutableStateOf("Starting playback engine")

        try {
            compose.setContent {
                NuvioTheme {
                    OpeningOverlay(
                        artwork = null,
                        logo = null,
                        title = "Loading playback",
                        onBack = {},
                        horizontalSafePadding = 16.dp,
                        message = loadingMessage,
                    )
                }
            }

            compose.onNodeWithContentDescription("Fullscreen").assertIsDisplayed().performClick()
            compose.waitUntil(3_000) { fullscreen.get() }
            compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()

            compose.runOnIdle { loadingMessage = "Preparing stream" }
            compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed().performClick()
            compose.waitUntil(3_000) { !fullscreen.get() }
            compose.onNodeWithContentDescription("Fullscreen").assertIsDisplayed()
        } finally {
            unregisterFullscreen()
        }
    }
}
