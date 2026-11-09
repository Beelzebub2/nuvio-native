package com.nuvio.app.features.watchorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.search.SearchPrefill
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.TabsRoute
import com.nuvio.app.navigation.WatchOrderRoute
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class WatchOrderLayoutUiTest {
    @Test
    fun searchHandoffEscapesTheNestedDetailRouteWithoutDuplicatingRoot() {
        val route = WatchOrderRoute(
            malId = 1001, sourceMetaId = "mal:1001", sourceMetaType = "series",
            sourceTitle = "First", currentTitleCompleted = true, screenTitle = "Watch order",
        )
        val navigator = NuvioNavigator(
            NavBackStack<NavKey>(TabsRoute, DetailRoute("series", "mal:1001"), route),
        )
        navigator.navigate(TabsRoute) {
            popUpTo<TabsRoute>()
            launchSingleTop = true
        }
        assertEquals(listOf(TabsRoute), navigator.routes)
        SearchPrefill.request("Second")
        assertEquals("Second", SearchPrefill.pending.value)
        SearchPrefill.consume("Second")
        assertNull(SearchPrefill.pending.value)
    }
    private val sampleSteps = listOf(
        AnimeWatchOrderStep(
            1101, "A Very Long Serialized Animation Title: The First Season",
            "tv", true, "2020-01-01",
        ),
        AnimeWatchOrderStep(
            1102, "Part Two — A Dramatic Feature Film With a Long Subtitle",
            "movie", true, "2021-08-02",
        ),
        AnimeWatchOrderStep(
            1103, "Part Three", "tv", true, "2022-02-01",
        ),
    )

    @Test
    fun rowsAreTappableAndRemainReadableAtCompactAndWideSizes() {
        listOf(390 to 780, 1100 to 800).forEach { (width, height) ->
            val searched = mutableListOf<String>()
            runDesktopComposeUiTest(width = width, height = height) {
                setContent {
                    NuvioTheme {
                        NuvioScreen {
                            item {
                                Column(
                                    modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        "Watch order",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "Three installments · recommended",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    sampleSteps.forEachIndexed { index, step ->
                                        WatchOrderStepRow(
                                            step = step,
                                            position = index + 1,
                                            isCurrent = index == 0,
                                            isCompleted = index == 0,
                                            onFindTitle = searched::add,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Watch order").assertExists()
                onNodeWithText(sampleSteps[1].title).performClick()
                runOnIdle { assertEquals(listOf(sampleSteps[1].title), searched) }
                val output = File("build/watch-order-capture").apply { mkdirs() }
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(), "png",
                    File(output, if (width < 500) "compact.png" else "wide.png"),
                )
            }
        }
    }
}
