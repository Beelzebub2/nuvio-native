package com.nuvio.app.features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UpdateBannerUiTest {
    private val update = AppUpdate(
        tag = "v1.0.26",
        title = "Starlit 1.0.26",
        notes = "## Starlit 1.0.26\n\n- Faster startup.",
        releaseUrl = "https://github.com/Beelzebub2/starlit/releases/tag/v1.0.26",
        assetName = "Starlit-1.0.26.msi",
        assetUrl = "https://example.invalid/Starlit-1.0.26.msi",
        assetSizeBytes = 131_400_000L,
    )

    private val states = listOf(
        AppUpdaterUiState(update = update, isUpdateAvailable = true, showDialog = true),
        AppUpdaterUiState(update = update, isUpdateAvailable = true, showDialog = true, isDownloading = true, downloadProgress = 0.42f),
        AppUpdaterUiState(update = update, isUpdateAvailable = true, showDialog = true, downloadedUpdatePath = "C:/tmp/update.msi"),
        AppUpdaterUiState(update = update, isUpdateAvailable = true, showDialog = true, errorMessage = "Download failed with HTTP 503"),
    )

    private fun renderStates(width: Int, name: String, clicks: MutableList<String>, theme: AppTheme = AppTheme.WHITE) =
        runDesktopComposeUiTest(width = width, height = 640) {
            setContent {
                NuvioTheme(appTheme = theme) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        states.forEach { state ->
                            AppUpdateBanner(
                                state = state,
                                update = update,
                                primaryAction = primaryUpdateAction(
                                    state,
                                    onDownload = { clicks += "download" },
                                    onInstall = { clicks += "install" },
                                ),
                                onShowReleaseNotes = { clicks += "notes" },
                                onDismiss = { clicks += "dismiss" },
                            )
                        }
                    }
                }
            }
            onNodeWithText("Downloading 42%").assertIsDisplayed()
            onNodeWithText("Download failed with HTTP 503").assertIsDisplayed()
            val directory = File("build/changelog-proof").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
            onNodeWithText("Update").assertIsDisplayed().performClick()
            onNodeWithText("Install").performClick()
            onNodeWithText("Retry").performClick()
            onAllNodesWithText("What’s new")[0].performClick()
        }

    @Test fun wideBannerShowsEveryStateWithOneAction() {
        val clicks = mutableListOf<String>()
        renderStates(width = 1100, name = "banner-wide", clicks = clicks)
        assertEquals(listOf("download", "install", "download", "notes"), clicks)
    }

    @Test fun bannerUsesTheThemeAccent() {
        val clicks = mutableListOf<String>()
        renderStates(width = 1100, name = "banner-wide-gold", clicks = clicks, theme = AppTheme.GOLD)
        assertEquals(listOf("download", "install", "download", "notes"), clicks)
    }

    @Test fun narrowBannerMovesActionsBelowText() {
        val clicks = mutableListOf<String>()
        renderStates(width = 380, name = "banner-narrow", clicks = clicks)
        assertEquals(listOf("download", "install", "download", "notes"), clicks)
    }

    @Test fun releaseNotesOfferTheUpdateAction() = releaseNotesWithUpdate(AppTheme.WHITE, "release-notes-update")

    @Test fun releaseNotesUseTheThemeAccent() = releaseNotesWithUpdate(AppTheme.GOLD, "release-notes-update-gold")

    private fun releaseNotesWithUpdate(theme: AppTheme, name: String) = runDesktopComposeUiTest(width = 1024, height = 768) {
        var updated = 0
        val notes = File("../docs/RELEASE-NOTES.md").readText()
        val latest = parseReleaseNotes(notes, update.title).first().version
        setContent {
            NuvioTheme(appTheme = theme) {
                ReleaseNotesDialog(
                    notes = notes,
                    title = "Release notes",
                    fallbackTitle = update.title,
                    highlightedVersion = latest,
                    highlightedLabel = "Available update",
                    onDismiss = {},
                    releaseUrl = update.releaseUrl,
                    primaryAction = UpdateAction("Update", Icons.Rounded.Refresh, true) { updated++ },
                )
            }
        }
        onNodeWithText("Available update").assertIsDisplayed()
        val directory = File("build/changelog-proof").apply { mkdirs() }
        ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
        onNodeWithText("Update").performClick()
        runOnIdle { assertEquals(1, updated) }
    }

    @Test fun unavailableUpdateDisablesTheAction() = runDesktopComposeUiTest(width = 1100, height = 200) {
        setContent {
            NuvioTheme {
                val state = AppUpdaterUiState(update = update, isUpdateAvailable = false, showDialog = true)
                AppUpdateBanner(state, update, primaryUpdateAction(state, {}, {}), {}, {})
            }
        }
        onNodeWithText("Update").assertIsNotEnabled()
    }
}
