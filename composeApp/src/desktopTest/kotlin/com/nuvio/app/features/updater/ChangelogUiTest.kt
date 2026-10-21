package com.nuvio.app.features.updater

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ChangelogUiTest {
    @get:Rule val compose = createComposeRule()

    private val fixture = """
        Starlit 2.0.0 makes the changelog easier to read.

        ## Features
        - **Clear version cards** with selectable text and `inline code`.
        - Read the [release details](https://github.com/Beelzebub2/nuvio-native/releases/tag/v2.0.0).

        Starlit 1.9.0 improves playback.

        - Historical change.

        Starlit 1.8.0 keeps the oldest release accessible.

        - The oldest change.
    """.trimIndent()

    private fun capture(name: String) {
        val directory = File("build/changelog-proof").apply { mkdirs() }
        ImageIO.write(compose.onNode(isDialog()).captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun latestIsExpandedOlderHistoryTogglesAndCloseStaysAccessible() {
        val open = mutableStateOf(true)
        var openedUrl: String? = null
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { openedUrl = uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            NuvioTheme { Surface {
                if (open.value) ReleaseNotesDialog(fixture, "Release notes", "Starlit 2.0.0", "v2.0.0", "Available update",
                    onDismiss = { open.value = false }, releaseUrl = "https://github.com/Beelzebub2/nuvio-native/releases/tag/v2.0.0")
            } }
            }
        }
        compose.onNodeWithText("Makes the changelog easier to read.").assertIsDisplayed()
        compose.onNodeWithText("Available update").assertIsDisplayed()
        compose.onNodeWithText("Historical change.").assertDoesNotExist()
        compose.onNodeWithText("Starlit 1.9.0").performClick()
        compose.onNodeWithText("Historical change.").assertIsDisplayed()
        capture("desktop-history-expanded")
        compose.onNodeWithText("Starlit 1.9.0").performClick()
        compose.onNodeWithText("Historical change.").assertDoesNotExist()
        compose.onNodeWithText("View on GitHub").assertIsDisplayed()
        compose.onNodeWithText("View on GitHub").performClick()
        compose.runOnIdle { assertEquals("https://github.com/Beelzebub2/nuvio-native/releases/tag/v2.0.0", openedUrl) }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun aboutOpensBundledHistoryWithoutAnAvailableUpdateAndSearchFindsIt() {
        val source = File("../docs/RELEASE-NOTES.md").readText()
        val bundled = runBlocking { readBundledReleaseNotes() }
        assertEquals(source, bundled)
        val first = parseReleaseNotes(bundled, "Changelog").first()
        val open = mutableStateOf(false)
        var searchEntries = emptyList<SettingsSearchEntry>()
        compose.setContent {
            NuvioTheme { Surface(Modifier.fillMaxSize()) {
                searchEntries = settingsSearchEntries(false, false, false, false, false, false, false, false, false, false)
                LazyColumn {
                    settingsRootContent(
                        isTablet = true,
                        onPlaybackClick = {}, onAppearanceClick = {}, onAdvancedClick = {}, onNotificationsClick = {},
                        onContentDiscoveryClick = {}, onIntegrationsClick = {}, onTrackingClick = {},
                        onSupportersContributorsClick = {}, onLicensesAttributionsClick = {}, onAccountClick = {},
                        onChangelogClick = { open.value = true }, showAccountSection = false, showGeneralSection = false,
                        showAdvancedSection = false, showSupportersContributorsPage = false, showAttribution = false,
                    )
                }
                if (open.value) BundledChangelogDialog { open.value = false }
            } }
        }
        compose.runOnIdle {
            assertTrue(searchEntries.any { it.target == SettingsSearchTarget.Changelog && it.searchableText.contains("offline") })
            assertFalse(searchEntries.any { it.target == SettingsSearchTarget.CheckForUpdates })
        }
        compose.onNodeWithText("Changelog").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(first.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(first.title).assertIsDisplayed()
        compose.onNodeWithText("Installed").assertExists()
        compose.onNodeWithText("View on GitHub").assertDoesNotExist()
        capture("about-offline")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertFalse(open.value) }
    }

    @Test fun emptyAndFailedBodiesOfferReadableRecovery() {
        var retries = 0
        val failed = mutableStateOf(true)
        compose.setContent {
            NuvioTheme { ReleaseNotesDialog("", "Changelog", "Preview", null, "", {}, loadFailed = failed.value,
                onRetry = { retries++; failed.value = false }) }
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("No release notes were provided.").assertExists()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
}

@OptIn(ExperimentalTestApi::class)
class ChangelogNarrowUiTest {
    @Test fun narrowLongReleaseScrollsWithoutHidingClose() = runDesktopComposeUiTest(width = 360, height = 760) {
        val notes = "Starlit 2.0.0 improves the reading experience.\n\n" +
            (1..24).joinToString("\n") { "- Change $it keeps the layout readable with a longer line of release notes." } +
            "\n\nStarlit 1.0.0 is the first release.\n\n- Earlier release."
        setContent {
            NuvioTheme { ReleaseNotesDialog(notes, "Release notes", "2.0.0", "2.0.0", "Available update", {}) }
        }
        fun capture(name: String) {
            val directory = File("build/changelog-proof").apply { mkdirs() }
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
        }
        onNodeWithContentDescription("Close").assertIsDisplayed()
        capture("narrow-latest")
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Starlit 1.0.0"))
        onNodeWithText("Starlit 1.0.0").performClick()
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Earlier release."))
        onNodeWithText("Earlier release.").assertIsDisplayed()
        onNodeWithContentDescription("Close").assertIsDisplayed()
        capture("narrow-history-scrolled")
    }
}
