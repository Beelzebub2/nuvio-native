package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.*
import com.nuvio.app.features.library.LibraryDownloadsButton
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadUiTest {
    @get:Rule val compose = createComposeRule()

    private fun storeDownloads(items: List<DownloadItem>) {
        DownloadsRepository.clearLocalState()
        DownloadsStorage.savePayload("{\"items\":" + Json.encodeToString(items) + "}")
        DownloadsRepository.ensureLoaded()
    }

    private fun fixture(root: File, id: String, status: DownloadStatus = DownloadStatus.Completed,
        show: String? = null, episode: Int? = null): DownloadItem {
        val file = File(File(root, id).apply { mkdirs() }, "$id.mp4")
        File(file.parentFile, ".starlit-owner").writeText(id)
        if (status == DownloadStatus.Completed) file.writeBytes(byteArrayOf(1,2,3))
        return DownloadItem(id, if (show == null) "movie" else "series", show ?: id,
            if (show == null) "movie" else "series", id, show ?: id,
            seasonNumber = if (show == null) null else 1, episodeNumber = episode,
            episodeTitle = if (show == null) null else "Episode $episode",
            streamTitle = "Fixture", providerName = "Fixture", sourceUrl = "https://example.test/$id.mp4",
            localFileUri = if (status == DownloadStatus.Completed) file.toURI().toString() else null,
            destinationDirectory = root.absolutePath, fileName = file.name, status = status,
            createdAtEpochMs = 0, updatedAtEpochMs = 0)
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun scrollToSelection(label: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label))
        return compose.onNodeWithContentDescription(label)
    }

    private fun capture(name: String) {
        val directory = File("build/bulk-download-proof").apply { mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun rootSelectsEveryDownloadAndConfirmsAnExactGroupSnapshot() {
        assertTrue(System.getProperty("nuvio.dataDir") != null)
        val root = Files.createTempDirectory("downloads-selection-ui").toFile()
        try {
            val items = listOf(fixture(root, "Paused", DownloadStatus.Paused),
                fixture(root, "Failed", DownloadStatus.Failed), fixture(root, "Movie"),
                fixture(root, "ep1", show = "Show", episode = 1), fixture(root, "ep2", show = "Show", episode = 2))
            storeDownloads(items)
            compose.setContent { NuvioTheme { Surface(Modifier.size(950.dp, 850.dp)) {
                DownloadsScreen({}, {})
            } } }
            scrollToText("Select all").performClick()
            scrollToText("Remove selected (5)").assertIsEnabled()
            scrollToText("Clear selection").performClick()
            scrollToText("Remove selected (0)").assertIsNotEnabled()
            scrollToSelection("Select Show").performClick().assertIsOn()
            scrollToText("Remove selected (2)").performClick()
            compose.onNodeWithText("No").performClick()
            compose.runOnIdle { assertEquals(5, DownloadsRepository.uiState.value.items.size) }
            scrollToText("Remove selected (2)").performClick()
            // A new episode arriving while confirmation is open must not join its removal snapshot.
            compose.runOnIdle { storeDownloads(items + fixture(root, "ep3", show = "Show", episode = 3)) }
            compose.onNodeWithText("Yes").performClick()
            compose.runOnIdle {
                assertEquals(setOf("Paused", "Failed", "Movie", "ep3"), DownloadsRepository.uiState.value.items.map { it.id }.toSet())
            }
            scrollToText("Select all").performClick()
            scrollToText("Remove selected (4)").assertIsEnabled()
            capture("desktop-selected")
            scrollToText("Remove selected (4)").performClick()
            compose.onNodeWithText("Yes").performClick()
            compose.runOnIdle { assertTrue(DownloadsRepository.uiState.value.items.isEmpty()) }
        } finally {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            root.deleteRecursively()
        }
    }

    @Test fun showSelectionIsScopedAndClearsOnNavigationAtNarrowWidth() {
        val root = Files.createTempDirectory("downloads-show-ui").toFile()
        val show = mutableStateOf<String?>("Show")
        try {
            storeDownloads(listOf(fixture(root, "ep1", show = "Show", episode = 1),
                fixture(root, "ep2", show = "Show", episode = 2),
                fixture(root, "other", show = "Other", episode = 1), fixture(root, "Movie")))
            compose.setContent { NuvioTheme { Surface(Modifier.size(360.dp, 850.dp)) {
                DownloadsScreen({}, {}, initialShowId = show.value, onBackFromShow = { show.value = null })
            } } }
            scrollToSelection("Select Episode 1").performClick().assertIsOn()
            scrollToText("Remove selected (1)").assertIsEnabled()
            scrollToText("Clear selection").performClick()
            scrollToText("Select all").performClick()
            scrollToText("Remove selected (2)").assertIsDisplayed()
            capture("narrow-selected")
            scrollToText("Remove selected (2)").performClick()
            compose.onNodeWithText("Yes").performClick()
            compose.runOnIdle {
                assertEquals(setOf("other", "Movie"), DownloadsRepository.uiState.value.items.map { it.id }.toSet())
                show.value = null
            }
            scrollToText("Remove selected (0)").assertIsNotEnabled()
            // Keep the existing one-item delete path usable alongside selection.
            scrollToSelection("Select Movie").assertIsOff()
            compose.onAllNodesWithContentDescription("Delete")[0].performClick()
            compose.onNodeWithText("Delete?").assertExists()
            compose.onNodeWithText("No").performClick()
        } finally {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            root.deleteRecursively()
        }
    }
    @Test fun libraryEntryAndFolderAndQueueControlsAreDiscoverable() {
        DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
        compose.setContent { NuvioTheme { Surface { Column(Modifier.width(950.dp)) {
            LibraryDownloadsButton({})
            DownloadManagementControls()
        } } } }
        compose.onNodeWithText("Downloads").assertIsDisplayed()
        compose.onNodeWithText("Change folder").assertIsDisplayed()
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
