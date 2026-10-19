package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.*
import com.nuvio.app.features.library.LibraryDownloadsButton
import org.junit.Rule
import org.junit.Test

class DownloadUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun libraryEntryAndFolderAndQueueControlsAreDiscoverable() {
        DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
        compose.setContent { NuvioTheme { Surface { Column(Modifier.width(950.dp)) {
            LibraryDownloadsButton({})
            DownloadManagementControls()
        } } } }
        compose.onNodeWithText("Downloads").assertIsDisplayed()
        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onNodeWithText("Open folder").assertIsDisplayed()
        compose.onNodeWithText("Pause all").assertIsDisplayed()
        compose.onNodeWithText("Resume all").assertIsDisplayed()
    }
    @Test fun seasonChooserSelectsSeveralSeasonsAndExcludesUnairedEpisodes() {
        val meta = MetaDetails("fixture", "series", "Fixture", videos = listOf(
            MetaVideo("ep1", "First", "2020-01-01", season = 1, episode = 1),
            MetaVideo("ep2", "Second", "2020-01-01", season = 2, episode = 1),
            MetaVideo("future", "Future", "2099-01-01", season = 2, episode = 2)))
        compose.setContent { NuvioTheme { Surface { BatchDownloadDialog(meta,1,{}) } } }
        compose.onNodeWithText("Queue 1 download").assertIsEnabled()
        compose.onNodeWithText("Select all available").performScrollTo().performClick()
        compose.onNodeWithText("Queue 2 downloads").assertIsEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollTo().performScrollToIndex(4)
        compose.onNodeWithText("E2 - Future - Unavailable / unaired").assertExists()
        compose.onNodeWithText("Clear").performScrollTo().performClick()
        compose.onNodeWithText("Queue 0 downloads").assertIsNotEnabled()
    }
}
