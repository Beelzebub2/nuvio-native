package com.nuvio.app.features.airing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import com.nuvio.app.features.watchlater.EpisodeDetailsNavigation
import com.nuvio.app.features.home.MetaPreview
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import org.junit.Rule
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiringBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val events = (1..40).map { AiringEvent(it, 7, now.minusSeconds(3600).epochSecond, "Anime $it", isAdult = false) }
    private fun repository() = AiringRepository(AiringSettings(zone = "UTC"),
        AiringSnapshot(events, now.toEpochMilli(), hideNsfw = true), fetch = { _, _ -> events }, now = { now })
    private fun posterTitle(name: String) = compose.onAllNodesWithText(name, useUnmergedTree = true).let {
        // The fixture's missing artwork also displays its title; target the label below it.
        it[it.fetchSemanticsNodes().lastIndex]
    }

    @Test fun `airing episode labels show synopsis and open title details with right click`() {
        val event = AiringEvent(61, 7, now.minusSeconds(86400).epochSecond, "Label Anime", description = "The parent anime synopsis.", isAdult = false)
        val repository = AiringRepository(AiringSettings(zone = "UTC"), AiringSnapshot(listOf(event), now.toEpochMilli(), hideNsfw = true),
            fetch = { _, _ -> listOf(event) }, now = { now })
        var opened: MetaPreview? = null
        var menus = 0
        compose.setContent { NuvioTheme {
            LaunchedEffect(event.mediaId) { EpisodeDetailsNavigation.requests.collect { opened = it } }
            Box(Modifier.size(1024.dp, 760.dp)) {
                AiringBrowser(Modifier, false, "Now", onPosterClick = {}, onPosterLongClick = { menus++ }, repository = repository)
            }
        } }
        compose.onNodeWithText("Episode 7 ·", substring = true).performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(event.description!!).assertIsDisplayed()
        compose.onNodeWithText("Episode 7 ·", substring = true).performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle {
            assertEquals(event.preview().id, opened?.id)
            assertEquals(null, opened?.watchLaterEpisode)
            assertEquals(0, menus)
        }
    }

    @Test fun `recent and today share one feed and future days are in the schedule below`() {
        val recent = AiringEvent(51, 3, now.minusSeconds(86400).epochSecond, "Recent Anime", isAdult = false)
        val future = AiringEvent(52, 4, now.plusSeconds(86400).epochSecond, "Future Anime", isAdult = false)
        val repository = AiringRepository(AiringSettings(zone = "UTC"), AiringSnapshot(listOf(recent, future), now.toEpochMilli(), hideNsfw = true),
            fetch = { _, _ -> listOf(recent, future) }, now = { now })
        compose.setContent { NuvioTheme {
            Column(Modifier.size(1024.dp, 760.dp).verticalScroll(rememberScrollState())) {
                AiringBrowser(Modifier, false, "All", onPosterClick = {}, onPosterLongClick = {}, repository = repository)
            }
        } }
        posterTitle("Recent Anime").assertIsDisplayed()
        compose.onNodeWithText("Future Anime").assertDoesNotExist()
        compose.onNodeWithText("Next 24 hours").assertDoesNotExist()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithTag("schedule-2026-10-05").performScrollTo().performClick()
        compose.onNodeWithTag("anime-schedule").performScrollTo()
        posterTitle("Future Anime").assertIsDisplayed()
    }

    @Test fun `view all opens the currently selected window`() {
        var launch: HomeCatalogSection? = null
        val repository = repository()
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1024.dp, 700.dp)) {
                    AiringBrowser(Modifier, expanded = false, initialWindow = "Today", onPosterClick = {},
                        onPosterLongClick = {}, onCatalogClick = { launch = it }, repository = repository)
                }
            }
        }
        compose.onNodeWithText("Browse").performClick()
        compose.runOnIdle { assertEquals(CatalogTarget.Airing("Now"), launch?.target) }
    }

    @Test fun `full catalog scrolls to anime beyond the first page`() {
        val repository = repository()
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1024.dp, 700.dp)) {
                    AiringBrowser(Modifier, expanded = true, initialWindow = "Today", onPosterClick = {},
                        onPosterLongClick = {}, onBack = {}, repository = repository)
                }
            }
        }
        compose.waitForIdle()
        posterTitle("Anime 1").assertIsDisplayed()
        val directory = File("build/reports/native-airing").apply { mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "full-catalog.png"))
        compose.onNodeWithTag("airing-grid").performScrollToIndex(39)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Anime 40").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `settings checkbox hides adults by default and enables them after save`() {
        val normal = AiringEvent(81, 1, now.minusSeconds(1800).epochSecond, "Normal Example", isAdult = false)
        val adult = AiringEvent(82, 1, now.minusSeconds(3600).epochSecond, "Adult Example", isAdult = true)
        val feed = listOf(normal, adult)
        val repository = AiringRepository(AiringSettings(zone = "UTC"),
            AiringSnapshot(feed, now.toEpochMilli(), hideNsfw = true), fetch = { _, _ -> feed }, now = { now })
        compose.setContent { NuvioTheme {
            Box(Modifier.size(1024.dp, 760.dp)) {
                AiringBrowser(Modifier, expanded = false, initialWindow = "Now", onPosterClick = {},
                    onPosterLongClick = {}, repository = repository)
            }
        } }
        assertTrue(compose.onAllNodesWithText("Normal Example").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Adult Example").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("airing-hide-nsfw").assertIsOn().performClick()
        compose.onNodeWithTag("airing-hide-nsfw").assertIsOff()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(false, repository.state.value.settings.hideNsfw)
        assertTrue(compose.onAllNodesWithText("Adult Example").fetchSemanticsNodes().isNotEmpty())
    }
}
