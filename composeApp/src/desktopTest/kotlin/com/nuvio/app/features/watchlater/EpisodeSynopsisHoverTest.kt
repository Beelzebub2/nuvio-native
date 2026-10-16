package com.nuvio.app.features.watchlater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DesktopNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.MetaPreview
import org.junit.Rule
import org.junit.Test

class EpisodeSynopsisHoverTest {
    @get:Rule val compose = createComposeRule()

    private val first = episode("first", "The first anime's synopsis.")
    private val second = episode("second", "The second anime's synopsis.")

    @Test
    fun synopsisStaysWhileReadingButDisappearsWhenMovingToHome() {
        setContent()
        compose.onNodeWithContentDescription("Home").performMouseInput { moveTo(center) }
        compose.onNodeWithText("Synopsis").assertDoesNotExist()

        hover(first)
        // Reading a synopsis must not be limited to the default short tooltip timeout.
        compose.mainClock.advanceTimeBy(6_000L)
        compose.onNodeWithText(first.description!!).assertIsDisplayed()

        compose.onNodeWithContentDescription("Home").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.onNodeWithText("Synopsis").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
    }

    @Test
    fun leavingTheWindowDismissesTheSynopsisAndReenteringShowsItAgain() {
        setContent()
        hover(first)
        compose.onNodeWithText(first.description!!).assertIsDisplayed()

        compose.onNodeWithTag(first.id).performMouseInput { exit() }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.onNodeWithText("Synopsis").assertDoesNotExist()

        hover(first)
        compose.onNodeWithText(first.description).assertIsDisplayed()
    }

    @Test
    fun movingBetweenEpisodesOnlyShowsTheCurrentSynopsis() {
        setContent()
        hover(first)
        compose.onNodeWithText(first.description!!).assertIsDisplayed()

        hover(second)
        compose.onNodeWithText(first.description).assertDoesNotExist()
        compose.onNodeWithText(second.description!!).assertIsDisplayed()

        compose.onNodeWithContentDescription("Home").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.onNodeWithText("Synopsis").assertDoesNotExist()
    }

    private fun hover(item: MetaPreview) {
        compose.onNodeWithTag(item.id).performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(1_500L)
    }

    private fun setContent() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(960.dp, 640.dp)) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        listOf(first, second).forEach { item ->
                            EpisodeSynopsis(item) {
                                Box(
                                    Modifier.size(280.dp, 180.dp).testTag(item.id).clickable {},
                                    contentAlignment = Alignment.Center,
                                ) { Text(item.name) }
                            }
                        }
                    }
                    DesktopNavigationBar(
                        items = listOf(
                            FloatingNavigationItem("Home", false, {}, icon = Icons.Default.Home),
                            FloatingNavigationItem("Watch Later", true, {}, icon = Icons.Default.Bookmark),
                        ),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    private fun episode(id: String, synopsis: String) = MetaPreview(
        id, "series", "Anime $id", description = synopsis,
        watchLaterEpisode = WatchLaterEpisode("$id:1:2", "Episode 2", 1, 2),
    )
}
