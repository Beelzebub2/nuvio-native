package com.nuvio.app.features.player

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LocalSubtitleModalUiTest {
    @Test
    fun selectedLocalSubtitleHasOpenAndRemoveActions() =
        runDesktopComposeUiTest(width = 1366, height = 768) {
            var opened = 0
            var removed = 0
            setContent {
                NuvioTheme {
                    SubtitleModal(
                        visible = true,
                        subtitleTracks = emptyList(),
                        selectedSubtitleIndex = -1,
                        addonSubtitles = emptyList(),
                        selectedAddonSubtitleId = null,
                        isLoadingAddonSubtitles = false,
                        preferredSubtitleLanguage = "en",
                        secondaryPreferredSubtitleLanguage = null,
                        subtitleStyle = SubtitleStyleState(),
                        subtitleDelayMs = 0,
                        selectedAddonSubtitle = null,
                        subtitleAutoSyncState = SubtitleAutoSyncUiState(),
                        localSubtitleName = "episode-01.srt",
                        canChooseLocalSubtitle = true,
                        onBuiltInTrackSelected = {},
                        onAddonSubtitleSelected = {},
                        onChooseLocalSubtitle = { opened++ },
                        onRemoveLocalSubtitle = { removed++ },
                        onFetchAddonSubtitles = {},
                        onStyleChanged = {},
                        onSubtitleDelayChanged = {},
                        onSubtitleDelayReset = {},
                        onAutoSyncCapture = {},
                        onAutoSyncCueSelected = {},
                        onAutoSyncReload = {},
                        onDismiss = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithText("episode-01.srt").assertExists()
            onNodeWithText("Open subtitle file").assertExists().performClick()
            onNodeWithText("Remove file").assertExists().performClick()
            assertEquals(1, opened)
            assertEquals(1, removed)

            val output = File("build/local-subtitle-capture").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "desktop-1366.png"))
        }
}
