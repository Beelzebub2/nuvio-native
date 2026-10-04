package com.nuvio.app.features.airing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
    private val events = (1..40).map { AiringEvent(it, 7, now.minusSeconds(3600).epochSecond, "Anime $it") }
    private fun repository() = AiringRepository(AiringSettings(zone = "UTC"),
        AiringSnapshot(events, now.toEpochMilli()), fetch = { _, _ -> events }, now = { now })

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
        compose.onNodeWithText("Recent").performClick()
        compose.onNodeWithText("View all").performClick()
        compose.runOnIdle { assertEquals(CatalogTarget.Airing("Recent"), launch?.target) }
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
        val directory = File("build/reports/native-airing").apply { mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "full-catalog.png"))
        compose.onNodeWithTag("airing-grid").performScrollToIndex(39)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Anime 40").fetchSemanticsNodes().isNotEmpty())
    }
}
