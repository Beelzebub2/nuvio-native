package com.nuvio.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.collection.CollectionManagementScreen
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.LibraryLayoutMode
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.library.StoredLibraryPayload
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.watchlater.LibraryView
import com.nuvio.app.features.watchlater.LibraryViewSwitcher
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * Renders Collections, Settings → Add-ons and Library with sample data and saves screenshots to
 * build/redesign-capture/latest. It only uses screens that existed before the desktop redesign,
 * so the same test captures both the old and the new pages for comparison.
 */
@OptIn(ExperimentalTestApi::class)
class RedesignCaptureTest {
    private fun ComposeUiTest.page(content: @androidx.compose.runtime.Composable () -> Unit) {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
            }
        }
        waitForIdle()
    }

    /** Waits for artwork from the fixture server so screenshots are not caught mid-load. */
    private fun ComposeUiTest.settle() {
        waitForIdle()
        Thread.sleep(1_500)
        waitForIdle()
    }

    @Test
    fun collections() {
        CollectionRepository.setCollections(RedesignFixtures.collections())
        runDesktopComposeUiTest(width = 1440, height = 900) {
            page { CollectionManagementScreen(onBack = {}, onNavigateToEditor = {}) }
            settle()
            RedesignFixtures.capture(this, "collections-wide")
        }
        runDesktopComposeUiTest(width = 900, height = 760) {
            page { CollectionManagementScreen(onBack = {}, onNavigateToEditor = {}) }
            settle()
            RedesignFixtures.capture(this, "collections-narrow")
        }
        CollectionRepository.setCollections(emptyList())
        runDesktopComposeUiTest(width = 1440, height = 900) {
            page { CollectionManagementScreen(onBack = {}, onNavigateToEditor = {}) }
            settle()
            RedesignFixtures.capture(this, "collections-empty")
        }
    }

    @Test
    fun addons() {
        val urls = listOf(
            RedesignFixtures.manifest(
                id = "opencatalogs", name = "Open Catalogs", version = "2.4.1", hue = 0.58f,
                description = "Popular, trending and top-rated lists for movies and series, refreshed every day.",
                resources = listOf("catalog", "meta"), types = listOf("movie", "series"), catalogs = 12, configurable = true,
            ),
            RedesignFixtures.manifest(
                id = "streamfinder", name = "Stream Finder", version = "1.9.0", hue = 0.08f,
                description = "Finds streams from your debrid service and ranks them by quality.",
                resources = listOf("stream"), types = listOf("movie", "series", "anime"), configurable = true, p2p = true,
            ),
            RedesignFixtures.manifest(
                id = "subtitlehub", name = "Subtitle Hub", version = "0.7.3", hue = 0.33f,
                description = "Subtitles in 40 languages, matched to the file you are playing.",
                resources = listOf("subtitles"), types = listOf("movie", "series"),
            ),
            "${RedesignFixtures.base}/addon/missing/manifest.json",
        )
        val profileId = ProfileRepository.activeProfileId
        AddonRepository.clearLocalState()
        AddonStorage.saveInstalledAddonUrls(profileId, urls)
        AddonStorage.saveAddonEnabledStates(profileId, mapOf(urls[2] to false))
        fun ComposeUiTest.openAddons() {
            page { SettingsScreen(topChromePadding = 72.dp) }
            onNode(hasText("Add-ons") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Open Catalogs").fetchSemanticsNodes().isNotEmpty() }
            settle()
        }
        runDesktopComposeUiTest(width = 1440, height = 1100) {
            openAddons()
            RedesignFixtures.capture(this, "addons-wide")
        }
        runDesktopComposeUiTest(width = 1000, height = 900) {
            openAddons()
            RedesignFixtures.capture(this, "addons-narrow")
        }
    }

    @Test
    fun library() {
        val profileId = ProfileRepository.activeProfileId
        val payload = Json { encodeDefaults = true }.encodeToString(
            StoredLibraryPayload.serializer(),
            StoredLibraryPayload(items = RedesignFixtures.libraryItems()),
        )
        LibraryStorage.savePayload(profileId, payload)
        LibraryRepository.clearLocalState()
        LibraryDisplaySettingsRepository.setLayoutMode(LibraryLayoutMode.HORIZONTAL)
        fun ComposeUiTest.openLibrary() {
            page {
                LibraryScreen(
                    topChromePadding = 72.dp,
                    onDownloadsClick = {},
                    // What the desktop shell puts under the title.
                    headerAccessory = { LibraryViewSwitcher(selected = LibraryView.Library, onSelect = {}) },
                )
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Severance").fetchSemanticsNodes().isNotEmpty() }
            settle()
        }
        runDesktopComposeUiTest(width = 1440, height = 900) {
            openLibrary()
            RedesignFixtures.capture(this, "library-rows")
            onNodeWithContentDescription("Show vertical grid").performClick()
            settle()
            RedesignFixtures.capture(this, "library-grid")
        }
        runDesktopComposeUiTest(width = 1000, height = 800) {
            openLibrary()
            RedesignFixtures.capture(this, "library-grid-narrow")
        }
        LibraryDisplaySettingsRepository.setLayoutMode(LibraryLayoutMode.HORIZONTAL)
    }
}
