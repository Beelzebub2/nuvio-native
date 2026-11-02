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
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        val download = DownloadItem(id, if (show == null) "movie" else "series", show ?: id,
            if (show == null) "movie" else "series", id, show ?: id,
            seasonNumber = if (show == null) null else 1, episodeNumber = episode,
            episodeTitle = if (show == null) null else "Episode $episode",
            streamTitle = "Fixture", providerName = "Fixture", sourceUrl = "https://example.test/$id.mp4",
            localFileUri = if (status == DownloadStatus.Completed) file.toURI().toString() else null,
            destinationDirectory = root.absolutePath, fileName = file.name, status = status,
            createdAtEpochMs = 0, updatedAtEpochMs = 0)
        if (status == DownloadStatus.Completed) {
            file.writeBytes(byteArrayOf(1, 2, 3))
            // A restored completed file must carry a durable integrity proof.
            // Previously the UI fixture treated an unverified file as playable,
            // which is correctly rejected by the background verification worker.
            writeDesktopDownloadProof(file, DesktopDownloadProof(
                download.id, download.fileName,
                downloadSourceFingerprint(download, download.sourceUrl),
                downloadEndpointFingerprint(URI(download.sourceUrl)),
                "\"fixture\"", file.length(), downloadSha256File(file),
            ))
        }
        return download
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

    @Test fun severalEpisodesDownloadTogetherAndTheLimitCanBeChangedFromTheQueue() {
        val root = Files.createTempDirectory("downloads-parallel-ui").toFile()
        val original = DownloadDesktopFeatures.directory()
        val body = ByteArray(4 * 1024 * 1024).also {
            System.arraycopy(byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109), 0, it, 0, 12)
        }
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"slow\"")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { out ->
                        var sent = 0
                        while (sent < body.size) {
                            val count = minOf(64 * 1024, body.size - sent)
                            out.write(body, sent, count); out.flush(); sent += count
                            if (release.count > 0) Thread.sleep(100) // about 640 KB/s per episode until released
                        }
                    }
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            DownloadDesktopFeatures.setParallelDownloads(3)
            val url = "http://127.0.0.1:${server.address.port}/episode.mp4"
            (1..4).forEach { episode ->
                DownloadsRepository.enqueueFromStream("series", "parallel:$episode", "parallel-show", "series", "Parallel Show",
                    null, null, null, 1, episode, "Episode $episode", null,
                    StreamItem(name = "1080p", url = url, addonName = "Local fixture", addonId = "fixture"))
            }
            compose.setContent { NuvioTheme { Surface(Modifier.size(1100.dp, 900.dp)) { DownloadsScreen({}, {}) } } }
            compose.waitUntil(10_000) {
                DownloadsRepository.uiState.value.items.count { it.phase == "Downloading" && it.bytesPerSecond > 0 } == 3
            }
            Thread.sleep(1_500) // let the averaged speed settle before the screenshot
            compose.waitForIdle()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("3 downloading", substring = true))
            compose.onNodeWithText("1 waiting", substring = true).assertExists()
            compose.onNodeWithText("/s  ·  1 waiting", substring = true).assertExists()
            compose.onNodeWithContentDescription("3 at once").assertIsSelected()
            capture("parallel-downloads")
            // Lowering the limit lets the running transfers finish; the fourth waits for a free slot.
            compose.onNodeWithContentDescription("2 at once").performClick().assertIsSelected()
            assertEquals(2, DownloadDesktopFeatures.parallelDownloads())
            assertEquals(3, DownloadsRepository.uiState.value.items.count { it.phase == "Downloading" })
            release.countDown()
            compose.waitUntil(15_000) { DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Completed } }
        } finally {
            release.countDown(); server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setParallelDownloads(DefaultParallelDownloads)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
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
