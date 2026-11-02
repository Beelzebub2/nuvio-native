package com.nuvio.app.features.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.nuvio.app.core.auth.LocalImportOutcome
import com.nuvio.app.core.auth.LocalImportProgress
import com.nuvio.app.core.auth.LocalImportStage
import com.nuvio.app.core.auth.LocalImportSummary
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LocalSettingsImportProgressDialogTest {
    private val summary = LocalImportSummary(addons = 12, plugins = 4, downloads = 23, library = 48, history = 106, collections = 3, savedLists = 8)

    private fun ComposeUiTest.capture(name: String) {
        val directory = File("build/import-progress-proof").apply { mkdirs() }
        ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun desktopShowsActualStageCountsThenAnAccessibleCompletionAction() = runDesktopComposeUiTest(width = 1100, height = 850) {
        val progress = mutableStateOf(LocalImportProgress("fixture", LocalImportStage.Downloads, summary))
        var closed = false
        setContent { NuvioTheme { Surface { LocalSettingsImportProgressDialog(progress.value) { closed = true } } } }
        onNodeWithText("Checking saved downloads and files").assertIsDisplayed()
        onNodeWithText("12 addons / 4 providers").assertIsDisplayed()
        onNodeWithText("23 items in your saved workspace").assertIsDisplayed()
        onNodeWithText("Close").assertDoesNotExist()
        capture("desktop-running")
        runOnIdle { progress.value = progress.value.copy(outcome = LocalImportOutcome.Synced) }
        onNodeWithText("Settings imported and synced.").assertIsDisplayed()
        onNodeWithText("Close").assertIsDisplayed()
        capture("desktop-complete")
        onNodeWithText("Close").performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test fun compactDialogKeepsPendingStatusAndCloseVisible() = runDesktopComposeUiTest(width = 360, height = 640) {
        val progress = LocalImportProgress("fixture", LocalImportStage.SyncLibraryAndHistory, summary, LocalImportOutcome.Pending)
        setContent { NuvioTheme { Surface { LocalSettingsImportProgressDialog(progress) {} } } }
        onNodeWithText("Saved locally. Retry account sync when connected.").assertIsDisplayed()
        onNodeWithText("Close").assertIsDisplayed()
        capture("compact-pending")
        onNodeWithText("Account sync").performScrollTo().assertIsDisplayed()
        onNodeWithText("Close").assertIsDisplayed()
    }
}
