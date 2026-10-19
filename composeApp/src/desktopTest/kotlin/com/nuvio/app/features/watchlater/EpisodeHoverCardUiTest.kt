package com.nuvio.app.features.watchlater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class EpisodeHoverCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandedEpisodeHoverCardRoutesRightClickToParentDetails() {
        PosterCardStyleRepository.ensureLoaded()
        val previous = PosterCardStyleRepository.uiState.value
        PosterCardStyleRepository.setHoverPreviewEnabled(true)
        PosterCardStyleRepository.setHoverPreviewTrailerEnabled(false)
        val preview = MetaPreview("hover-episode", "series", "Hover episode anime",
            watchLaterEpisode = WatchLaterEpisode("hover-episode:1:2", "Episode 2", 1, 2))
        var opened: MetaPreview? = null
        var oldMenu = 0
        var plays = 0
        try {
            compose.setContent { NuvioTheme {
                LaunchedEffect(preview.id) { EpisodeDetailsNavigation.requests.collect { opened = it } }
                Box(Modifier.size(1000.dp, 760.dp)) {
                    HomePosterCard(preview, Modifier.testTag("anchor"), onClick = { plays++ }, onLongClick = { oldMenu++ })
                }
            } }
            compose.onNodeWithTag("anchor", useUnmergedTree = true).performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(previous.hoverPreviewOpenDelayMillis.toLong() + 600)
            val titles = compose.onAllNodesWithText(preview.name)
            compose.waitUntil(5_000) { titles.fetchSemanticsNodes().size >= 2 }
            titles[titles.fetchSemanticsNodes().lastIndex].performMouseInput { click(button = MouseButton.Secondary) }
            compose.runOnIdle {
                assertEquals(preview.id, opened?.id)
                assertEquals(null, opened?.watchLaterEpisode)
                assertEquals(0, oldMenu)
                assertEquals(0, plays)
            }
        } finally {
            PosterCardStyleRepository.setHoverPreviewEnabled(previous.hoverPreviewEnabled)
            PosterCardStyleRepository.setHoverPreviewTrailerEnabled(previous.hoverPreviewTrailerEnabled)
        }
    }
}
