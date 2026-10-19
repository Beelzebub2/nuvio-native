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
        DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
        compose.setContent { NuvioTheme { Surface { BatchDownloadDialog(meta,1,{}) } } }
        compose.onNodeWithText("Download 1 episode").assertIsEnabled()
        compose.onNodeWithText("Highest available").assertIsSelected()
        compose.onNodeWithText("Select all seasons").performClick()
        compose.onNodeWithText("Download 2 episodes").assertIsEnabled()
        compose.onNodeWithText("Seasons 1–2").assertExists()
        compose.onNodeWithText("Season 2").performClick()
        compose.onNodeWithText("Future").assertIsNotEnabled()
        compose.onNodeWithText("Airs", substring = true).assertExists()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("No episodes selected").assertExists()
        compose.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test fun episodesAlreadyInDownloadsAreShownAndNotQueuedAgain() {
        DownloadsRepository.clearLocalState()
        DownloadsStorage.savePayload("""{"items":[{"id":"done1","contentType":"series","parentMetaId":"fixture","parentMetaType":"series",
            "videoId":"ep1","title":"Fixture","seasonNumber":1,"episodeNumber":1,"streamTitle":"1080p","providerName":"fixture",
            "sourceUrl":"https://example.test/a.mp4","fileName":"a_done1.mp4","status":"Paused","createdAtEpochMs":0,"updatedAtEpochMs":0}]}""")
        DownloadsRepository.ensureLoaded()
        val meta = MetaDetails("fixture", "series", "Fixture", videos = listOf(
            MetaVideo("ep1", "First", "2020-01-01", season = 1, episode = 1),
            MetaVideo("ep2", "Second", "2020-01-02", season = 1, episode = 2)))
        try {
            compose.setContent { NuvioTheme { Surface { BatchDownloadDialog(meta,1,{}) } } }
            compose.onNodeWithText("Paused").assertExists()
            compose.onNodeWithText("First").assertIsNotEnabled()
            compose.onNodeWithText("Download 1 episode").assertIsEnabled()
        } finally {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
        }
    }
}
