package com.nuvio.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSmoothWheelScroll
import com.nuvio.app.features.addons.AddonBehaviorHints
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.DesktopAddonsContent
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.CollectionManagementScreen
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.LibraryLayoutMode
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.library.StoredLibraryPayload
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopPagesRedesignTest {
    private fun ComposeUiTest.page(content: @Composable () -> Unit) {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
            }
        }
        waitForIdle()
    }

    @Test
    fun wheelTurnsGlideToTheSameDistanceInsteadOfJumping() = runDesktopComposeUiTest(width = 400, height = 600) {
        val state = LazyListState()
        setContent {
            Box(Modifier.fillMaxSize().nuvioSmoothWheelScroll(state)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) {
                    items(200) { Box(Modifier.fillMaxWidth().height(50.dp)) }
                }
            }
        }
        waitForIdle()
        fun offset(): Int = state.firstVisibleItemIndex * 50 + state.firstVisibleItemScrollOffset

        mainClock.autoAdvance = false
        // Five notches: 1/20 of the 600 px viewport each, the same distance Compose uses on Windows.
        onNodeWithTag("list").performMouseInput { scroll(5f) }
        val positions = (1..40).map {
            mainClock.advanceTimeByFrame()
            offset()
        }
        assertTrue(positions.first() in 1..74, "The first frame starts moving without jumping: $positions")
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a }, "The glide only moves forward: $positions")
        assertTrue(kotlin.math.abs(positions.last() - 150) <= 1, "Five notches land where five plain notches would: $positions")
        assertTrue(positions.indexOfFirst { it >= 149 } >= 8, "The glide eases in over several frames: $positions")

        // Reversing mid-glide turns around at once instead of finishing the old direction.
        onNodeWithTag("list").performMouseInput { scroll(5f) }
        repeat(3) { mainClock.advanceTimeByFrame() }
        val beforeReverse = offset()
        onNodeWithTag("list").performMouseInput { scroll(-2f) }
        repeat(40) { mainClock.advanceTimeByFrame() }
        assertTrue(kotlin.math.abs(offset() - (beforeReverse - 60)) <= 1, "Reversing drops the rest: $beforeReverse -> ${offset()}")
    }

    @Test
    fun collectionsCardsOpenTheEditorAndConfirmDeletes() = runDesktopComposeUiTest(width = 1440, height = 900) {
        CollectionRepository.setCollections(RedesignFixtures.collections())
        val edited = mutableListOf<String?>()
        page { CollectionManagementScreen(onBack = {}, onNavigateToEditor = { edited += it }) }

        onNodeWithText("Studios").assertIsDisplayed()
        onNodeWithText("Pinned").assertIsDisplayed()
        // Six folders, four covers: the last cover summarises the other two.
        onNodeWithText("+2").assertIsDisplayed()
        onNodeWithText("5 collections  ·  15 folders  ·  1 pinned").assertIsDisplayed()

        onNodeWithText("Moods").performClick()
        onAllNodesWithText("Edit")[0].performClick()
        onNodeWithText("New Collection").performClick()
        runOnIdle { assertEquals(listOf("moods", "studios", null), edited) }

        onAllNodesWithText("Weekend watchlist (draft)")[0].assertIsDisplayed()
        onNodeWithText("No folders yet").assertIsDisplayed()
        onAllNodes(hasContentDescription("Delete"))[0].performClick()
        onNodeWithText("Delete Collection").assertIsDisplayed()
    }

    @Test
    fun draggingACardByItsHandleReordersCollections() = runDesktopComposeUiTest(width = 1440, height = 900) {
        CollectionRepository.setCollections(RedesignFixtures.collections())
        page { CollectionManagementScreen(onBack = {}, onNavigateToEditor = {}) }

        // Drag Moods (second) up past Studios, a frame at a time like a real pointer.
        // The handle sits inside the clickable card, so its label only shows in the unmerged tree.
        val handle = onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[1]
        handle.performMouseInput {
            moveTo(center)
            press()
        }
        repeat(16) {
            handle.performMouseInput { moveBy(Offset(0f, -10f)) }
            waitForIdle()
        }
        handle.performMouseInput { release() }
        waitForIdle()
        assertEquals(
            listOf("moods", "studios", "networks", "decades", "draft"),
            CollectionRepository.collections.value.map { it.id },
        )
    }

    @Test
    fun addonRowsReportStatusAndRouteEveryAction() = runDesktopComposeUiTest(width = 900, height = 1000) {
        fun manifest(name: String, resources: List<String>, catalogs: Int = 0) = AddonManifest(
            id = name, name = name, description = "$name description", version = "1.0.0",
            resources = resources.map { AddonResource(it, listOf("movie")) }, types = listOf("movie", "series"),
            catalogs = (1..catalogs).map { AddonCatalog("movie", "c$it", "Catalog $it") },
            behaviorHints = AddonBehaviorHints(configurable = true), transportUrl = "https://$name",
        )
        val addons = listOf(
            ManagedAddon("https://one/manifest.json", manifest("Open Catalogs", listOf("catalog", "meta"), catalogs = 3)),
            ManagedAddon("https://two/manifest.json", manifest("Stream Finder", listOf("stream"))),
            ManagedAddon("https://three/manifest.json", manifest("Subtitle Hub", listOf("subtitles")), enabled = false),
            ManagedAddon("https://four/manifest.json", errorMessage = "Unable to load manifest"),
        )
        val calls = mutableListOf<String>()
        page {
            var url by remember { mutableStateOf("") }
            Box(Modifier.width(760.dp)) {
                DesktopAddonsContent(
                    addons = addons,
                    addonUrl = url,
                    formMessage = null,
                    usePersonalMediaCopy = false,
                    onAddonUrlChange = { url = it },
                    onInstall = { calls += "install:$url" },
                    onMove = { from, to -> calls += "move:$from>$to" },
                    onRefresh = { calls += "refresh:${it.displayTitle}" },
                    onEnabledChange = { addon, enabled -> calls += "enabled:${addon.displayTitle}=$enabled" },
                    configureAction = { addon -> { calls += "configure:${addon.displayTitle}" } },
                    onDelete = { calls += "delete:${it.displayTitle}" },
                )
            }
        }

        onNodeWithText("Disabled").assertIsDisplayed()
        onNodeWithText("Unavailable").assertIsDisplayed()
        onNodeWithText("Unable to load manifest").assertIsDisplayed()
        onNodeWithText("Movies, Series  ·  3 catalogs  ·  Metadata", substring = true).assertIsDisplayed()

        val install = onNode(hasText("Install"))
        install.assertIsNotEnabled()
        onNodeWithContentDescription("Paste a manifest URL", substring = true).performTextInput("https://five/manifest.json")
        install.assertIsEnabled().performClick()
        onNodeWithContentDescription("Move addon up: Stream Finder").performClick()
        onNodeWithContentDescription("Move addon down: Stream Finder").performClick()
        onNodeWithContentDescription("Move addon up: Open Catalogs").assertIsNotEnabled()
        onNodeWithContentDescription("Refresh addon: Subtitle Hub").performClick()
        onNodeWithContentDescription("Configure addon: Open Catalogs").performClick()
        onNodeWithContentDescription("Delete addon: Stream Finder").performClick()
        onNodeWithContentDescription("Subtitle Hub").performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    "install:https://five/manifest.json", "move:1>0", "move:1>2", "refresh:Subtitle Hub",
                    "configure:Open Catalogs", "delete:Stream Finder", "enabled:Subtitle Hub=true",
                ),
                calls,
            )
        }
    }

    @Test
    fun librarySearchAndTypeFilterNarrowTheGrid() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val payload = Json { encodeDefaults = true }.encodeToString(
            StoredLibraryPayload.serializer(),
            StoredLibraryPayload(items = RedesignFixtures.libraryItems()),
        )
        LibraryStorage.savePayload(ProfileRepository.activeProfileId, payload)
        LibraryRepository.clearLocalState()
        LibraryDisplaySettingsRepository.setLayoutMode(LibraryLayoutMode.VERTICAL)
        page { LibraryScreen(topChromePadding = 72.dp, onDownloadsClick = {}) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Severance").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("24 titles").assertIsDisplayed()

        val search = onNodeWithContentDescription("Search your library")
        search.performTextInput("dune")
        waitForIdle()
        onNodeWithText("2 / 24 titles").assertIsDisplayed()
        onNodeWithText("Dune Part Two").assertIsDisplayed()
        onNode(hasText("Severance")).assertDoesNotExist()

        search.performTextClearance()
        onNode(hasText("Series") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForIdle()
        onNodeWithText("Severance").assertIsDisplayed()
        onNode(hasText("Past Lives")).assertDoesNotExist()
        RedesignFixtures.capture(this, "library-grid-series")

        search.performTextInput("zzz")
        waitForIdle()
        onNodeWithText("No matches").assertIsDisplayed()
        RedesignFixtures.capture(this, "library-no-matches")
        onNode(hasText("Clear search")).performClick()
        waitForIdle()
        onNodeWithText("Severance").assertIsDisplayed()
        LibraryDisplaySettingsRepository.setLayoutMode(LibraryLayoutMode.HORIZONTAL)
    }
}
