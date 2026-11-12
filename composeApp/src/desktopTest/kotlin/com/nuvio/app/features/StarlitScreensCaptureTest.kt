package com.nuvio.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.collection.CollectionEditorRepository
import com.nuvio.app.features.collection.CollectionEditorScreen
import com.nuvio.app.features.player.SubtitleAutoSyncUiState
import com.nuvio.app.features.player.SubtitleStylePanel
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.settings.AccountCredentialsCard
import com.nuvio.app.features.settings.posterCustomizationSettingsContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders the redesigned shared screens and saves screenshots to build/starlit-capture, in the
 * default White theme and in Gold so accent-derived colours can be checked.
 */
@OptIn(ExperimentalTestApi::class)
class StarlitScreensCaptureTest {
    private fun ComposeUiTest.capture(name: String, lastRoot: Boolean = false) {
        waitForIdle()
        val node = if (lastRoot) onAllNodes(isRoot()).onLast() else onRoot()
        val file = File("build/starlit-capture/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun subtitleStylePanelGroupsTimingTextAndOutline() {
        for (theme in listOf(AppTheme.WHITE, AppTheme.GOLD)) runDesktopComposeUiTest(width = 460, height = 1500) {
            var delay = -250
            setContent {
                NuvioTheme(appTheme = theme) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF050505))
                            .padding(16.dp),
                    ) {
                        Box(
                            Modifier
                                .width(420.dp)
                                .background(MaterialTheme.nuvio.colors.surface, MaterialTheme.nuvio.shapes.playerPanel)
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SubtitleStylePanel(
                                style = SubtitleStyleState(),
                                subtitleDelayMs = delay,
                                selectedAddonSubtitle = null,
                                subtitleAutoSyncState = SubtitleAutoSyncUiState(),
                                isCompact = false,
                                onStyleChanged = {},
                                onSubtitleDelayChanged = { delay = it },
                                onSubtitleDelayReset = { delay = 0 },
                                onAutoSyncCapture = {},
                                onAutoSyncCueSelected = {},
                                onAutoSyncReload = {},
                            )
                        }
                    }
                }
            }
            onNodeWithText("TIMING").assertExists()
            onNodeWithText("OUTLINE").assertExists()
            onNodeWithContentDescription("Increase Subtitle Delay").performClick()
            runOnIdle { assertEquals(-150, delay) }
            capture("subtitle-style-${theme.name.lowercase()}")
        }
    }

    @Test
    fun collectionEditorUsesGroupsAndAHeaderSaveButton() = runDesktopComposeUiTest(width = 412, height = 900) {
        CollectionEditorRepository.initialize(null)
        CollectionEditorRepository.setTitle("Weekend picks")
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) {
                    CollectionEditorScreen(collectionId = null, onBack = {}, initializeRepository = false)
                }
            }
        }
        onNodeWithText("BASICS").assertExists()
        onNodeWithText("Save").assertExists()
        capture("collection-editor-root")
        runOnIdle { CollectionEditorRepository.addFolder("Anime nights") }
        onNodeWithText("Tile Shape").assertExists()
        capture("collection-editor-folder")
        runOnIdle { CollectionEditorRepository.saveFolderEdit() }
        onNodeWithText("Anime nights").assertExists()
        capture("collection-editor-with-folder")
    }

    @Test
    fun posterSettingsUseSegmentsAndChips() = runDesktopComposeUiTest(width = 412, height = 2200) {
        setContent {
            NuvioTheme {
                NuvioScreen {
                    posterCustomizationSettingsContent(isTablet = false, uiState = PosterCardStyleUiState())
                }
            }
        }
        waitForIdle()
        capture("poster-settings")
    }

    @Test
    fun accountSecurityRowsAndPasswordDialog() = runDesktopComposeUiTest(width = 412, height = 900) {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background).padding(16.dp)) {
                    AccountCredentialsCard(AuthState.Authenticated("account-fixture", "you@example.com", isAnonymous = false))
                }
            }
        }
        capture("account-security")
        onNodeWithTag("account-change-password-button").performClick()
        onNodeWithContentDescription("Show password").assertExists()
        capture("account-password-dialog", lastRoot = true)
    }
}
