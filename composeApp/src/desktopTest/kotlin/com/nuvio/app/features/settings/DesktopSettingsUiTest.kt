package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopSettingsUiTest {
    private val clicks = mutableListOf<String>()

    private fun ComposeUiTest.showSettings() {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) {
                    SettingsScreen(
                        topChromePadding = 72.dp,
                        onSwitchProfile = { clicks += "switch-profile" },
                        onCheckForUpdatesClick = { clicks += "check-updates" },
                        onCollectionsClick = { clicks += "collections" },
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.sidebar(label: String): SemanticsNodeInteraction =
        onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    private fun ComposeUiTest.open(label: String) {
        sidebar(label).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val directory = File("build/settings-capture").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun sidebarListsEveryPageAndHighlightsTheOpenOne() = runDesktopComposeUiTest(width = 1440, height = 900) {
        showSettings()
        // An empty settings screen lands on Account.
        sidebar("Account").assertIsSelected()
        onNodeWithText("Your sign-in and account details.").assertIsDisplayed()
        capture("01-account")

        listOf(
            "Tracking", "Playback", "Continue watching", "Appearance", "Home screen", "Collections",
            "Details page", "Poster cards", "Hover previews", "Add-ons", "Debrid", "Source list",
            "TMDB", "MDBList", "Advanced", "Updates", "About",
        ).forEach { sidebar(it).performScrollTo().assertExists() }

        open("Playback")
        sidebar("Playback").assertIsSelected()
        sidebar("Account").assertIsNotSelected()
        // Breadcrumb plus the sidebar group label.
        onAllNodesWithText("Watching").assertCountEquals(2)
        capture("02-playback")

        open("Appearance")
        // Pages listed in the sidebar are not repeated as links on the Appearance page.
        onNode(hasText("Home Layout")).assertDoesNotExist()
        onNode(hasText("Hover Preview")).assertDoesNotExist()
        capture("03-appearance")

        open("Source list")
        capture("04-source-list")
        open("Advanced")
        capture("05-advanced")
    }

    @Test
    fun aboutAndUpdatesAreTheirOwnScreens() = runDesktopComposeUiTest(width = 1440, height = 900) {
        showSettings()
        open("Updates")
        sidebar("Updates").assertIsSelected()
        onNodeWithText("Check for updates").performClick()
        capture("06-updates")

        open("About")
        sidebar("About").assertIsSelected()
        onNodeWithText("Privacy Policy").assertIsDisplayed()
        capture("07-about")
        onNodeWithText("Licenses & Attribution").performClick()
        waitForIdle()
        // About stays highlighted, and the breadcrumb leads back to it.
        sidebar("About").assertIsSelected()
        onNodeWithText("‹  About").performClick()
        waitForIdle()
        onNodeWithText("Privacy Policy").assertIsDisplayed()

        open("Collections")
        onNodeWithContentDescription("Switch profile").performClick()
        assertEquals(listOf("check-updates", "collections", "switch-profile"), clicks)
    }

    @Test
    fun searchShowsResultsAndClearingReturnsToThePreviousPage() = runDesktopComposeUiTest(width = 1100, height = 760) {
        showSettings()
        open("Playback")
        val field = onNode(hasContentDescription("Search settings..."))
        field.performTextInput("subtitle")
        waitForIdle()
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Results for “subtitle”").assertIsDisplayed()
        sidebar("Playback").assertIsNotSelected()
        capture("08-search")

        field.performTextClearance()
        waitForIdle()
        sidebar("Playback").assertIsSelected()
        capture("09-narrow-playback")
    }
}
