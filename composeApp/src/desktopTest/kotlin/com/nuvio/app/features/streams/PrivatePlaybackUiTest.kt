package com.nuvio.app.features.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PrivatePlaybackUiTest {
    private val playable = StreamItem(
        name = "Example stream",
        url = "https://example.com/video.mp4",
        addonName = "Example provider",
        addonId = "addon:example",
    )

    @Test
    fun desktopMenuProvidesAnExplicitPrivateLaunch() = runDesktopComposeUiTest(width = 880, height = 640) {
        var chosen: Pair<Boolean, Boolean>? = null
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    DesktopStreamActionsMenu(
                        stream = playable,
                        anchorInRoot = Offset(340f, 170f),
                        externalPlayerSupported = true,
                        externalPlayerEnabled = false,
                        showDownloadAction = false,
                        onDismiss = {},
                        onCopyLink = {},
                        onDownload = {},
                        onOpen = { _, external, isPrivate -> chosen = external to isPrivate },
                    )
                }
            }
        }
        onNodeWithText("Play privately").assertIsDisplayed()
        val proof = File("build/reports/private-playback-ui").apply { mkdirs() }
        val popupRoot = onAllNodes(isRoot()).fetchSemanticsNodes().lastIndex
        ImageIO.write(onAllNodes(isRoot())[popupRoot].captureToImage().toAwtImage(), "png", File(proof, "desktop-stream-menu.png"))
        onNodeWithText("Play privately").performClick()
        runOnIdle { assertEquals(false to true, chosen) }
    }

    @Test
    fun externalOnlyStreamsCannotLaunchPrivately() = runDesktopComposeUiTest(width = 880, height = 640) {
        val externalOnly = playable.copy(url = null, externalUrl = "https://example.com/watch")
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    DesktopStreamActionsMenu(
                        stream = externalOnly,
                        anchorInRoot = Offset(340f, 170f),
                        externalPlayerSupported = false,
                        externalPlayerEnabled = false,
                        showDownloadAction = false,
                        onDismiss = {},
                        onCopyLink = {},
                        onDownload = {},
                        onOpen = { _, _, _ -> error("External-only stream must not launch privately") },
                    )
                }
            }
        }
        onNodeWithText("Play privately").assertDoesNotExist()
    }

    @Test
    fun mobileSheetKeepsThePrivateActionSeparateFromNormalPlayback() = runDesktopComposeUiTest(width = 420, height = 800) {
        var chosen: Pair<Boolean, Boolean>? = null
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    StreamActionsSheet(
                        stream = playable,
                        externalPlayerSupported = true,
                        externalPlayerEnabled = false,
                        showDownloadAction = false,
                        onDismiss = {},
                        onCopyLink = {},
                        onDownload = {},
                        onOpen = { _, external, isPrivate -> chosen = external to isPrivate },
                    )
                }
            }
        }
        onNodeWithText("Play privately").assertIsDisplayed()
        val proof = File("build/reports/private-playback-ui").apply { mkdirs() }
        val sheetRoot = onAllNodes(isRoot()).fetchSemanticsNodes().lastIndex
        ImageIO.write(onAllNodes(isRoot())[sheetRoot].captureToImage().toAwtImage(), "png", File(proof, "mobile-stream-sheet.png"))
        onNodeWithText("Play privately").performClick()
        runOnIdle { assertEquals(false to true, chosen) }
    }
}
