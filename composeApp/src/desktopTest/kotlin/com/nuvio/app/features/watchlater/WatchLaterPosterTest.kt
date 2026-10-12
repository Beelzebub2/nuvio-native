package com.nuvio.app.features.watchlater

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchLaterPosterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `middle click toggles a real poster bookmark without opening details`() {
        val preview = MetaPreview("nativeairing:99999999", "series", "Watch Later test",
            watchLaterEpisode = WatchLaterEpisode("nativeairing:99999999:1:1", "Episode 1", 1, 1))
        WatchLaterRepository.ensureLoaded()
        if (WatchLaterRepository.items.value.any { it.id == preview.id && it.type == preview.type }) {
            WatchLaterRepository.items.value.filter { it.id == preview.id }.forEach(WatchLaterRepository::remove)
        }
        var navigations = 0
        try {
            compose.setContent {
                NuvioTheme { HomePosterCard(preview, modifier = Modifier.testTag("saved-poster"), onClick = { navigations++ }) }
            }
            compose.onNodeWithTag("saved-poster", useUnmergedTree = true).performMouseInput { click(button = MouseButton.Tertiary) }
            compose.waitUntil(5_000L) { WatchLaterRepository.items.value.any { it.id == preview.id } }
            compose.onNodeWithContentDescription("Saved to Watch Later").assertExists()
            compose.runOnIdle { assertEquals(0, navigations) }
            compose.onNodeWithTag("saved-poster", useUnmergedTree = true).performMouseInput { click(button = MouseButton.Tertiary) }
            compose.waitUntil(5_000L) { WatchLaterRepository.items.value.none { it.id == preview.id } }
            compose.onNodeWithContentDescription("Saved to Watch Later").assertDoesNotExist()
        } finally {
            if (WatchLaterRepository.items.value.any { it.id == preview.id && it.type == preview.type }) {
                WatchLaterRepository.items.value.filter { it.id == preview.id }.forEach(WatchLaterRepository::remove)
            }
        }
    }
}
