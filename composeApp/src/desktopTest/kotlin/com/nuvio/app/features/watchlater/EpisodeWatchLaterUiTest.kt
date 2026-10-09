package com.nuvio.app.features.watchlater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.components.DetailSeriesContent
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class EpisodeWatchLaterUiTest(private val style: MetaEpisodeCardStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun styles() = MetaEpisodeCardStyle.entries.map { arrayOf(it) }
    }
    @get:Rule val compose = createComposeRule()
    @Test fun middleClickSavesTheSpecificEpisodeWithoutPlayingIt() {
        val id = "episode-ui-$style"
        val first = MetaVideo("$id:1:1", "First episode", released = "2020-01-01", season = 1, episode = 1)
        val second = first.copy(id = "$id:1:2", title = "Second episode", episode = 2)
        val meta = MetaDetails(id, "series", "Test Anime", videos = listOf(first, second))
        var plays = 0
        WatchLaterRepository.ensureLoaded()
        WatchLaterRepository.items.value.filter { it.id == id }.forEach(WatchLaterRepository::remove)
        try {
            compose.setContent { NuvioTheme { Box(Modifier.size(1100.dp, 760.dp)) {
                DetailSeriesContent(meta, episodeCardStyle = style, onEpisodeClick = { plays++ })
            } } }
            compose.onNodeWithText(first.title).performMouseInput { click(button = MouseButton.Tertiary) }
            compose.waitUntil(5_000L) { WatchLaterRepository.items.value.any { it.id == id && it.watchLaterEpisode?.episodeNumber == 1 } }
            compose.onNodeWithText(second.title).performMouseInput { click(button = MouseButton.Tertiary) }
            compose.waitUntil(5_000L) { WatchLaterRepository.items.value.count { it.id == id } == 2 }
            assertEquals(0, plays)
            val directory = File("build/qa-1.0.6").apply { mkdirs() }
            ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "episodes-$style.png"))
        } finally { WatchLaterRepository.items.value.filter { it.id == id }.forEach(WatchLaterRepository::remove) }
    }
}
