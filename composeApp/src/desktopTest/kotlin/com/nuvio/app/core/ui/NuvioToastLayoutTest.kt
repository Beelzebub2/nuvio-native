package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.settings.NavBarStyle
import org.junit.Rule
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NuvioToastLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longWatchLaterMessageLeavesNavigationVisibleAndClickableWhenResizedAndScaled() {
        val width = mutableIntStateOf(960)
        val scale = mutableStateOf(1f)
        val selected = mutableIntStateOf(0)
        val clicks = mutableListOf<Int>()
        var pixelsPerDp = 1f
        val labels = listOf("Home", "Search", "Watch Later", "Library", "Settings")
        val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.Bookmark, Icons.Default.VideoLibrary, Icons.Default.Settings)
        val message = "The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life added to Watch Later"
        try {
            NuvioToastController.dismiss()
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale.value, density.fontScale)) {
                    val scaledDensity = LocalDensity.current.density
                    SideEffect { pixelsPerDp = scaledDensity }
                    NuvioTheme {
                        Box(Modifier.size(width.intValue.dp, 480.dp).background(Color(0xFF0F272C)).testTag("window")) {
                            DesktopNavigationBar(
                                items = labels.mapIndexed { index, label ->
                                    FloatingNavigationItem(label = label, icon = icons[index], selected = selected.intValue == index,
                                        onClick = { clicks += index; selected.intValue = index })
                                },
                                navBarStyle = NavBarStyle.EXPANDED,
                                modifier = Modifier.testTag("navigation"),
                            )
                            NuvioToastHost(Modifier.testTag("toast"))
                        }
                    }
                }
            }
            compose.runOnIdle { NuvioToastController.show(message, durationMillis = 60_000) }
            for (viewportWidth in listOf(960, 480)) {
                for (uiScale in listOf(1f, 1.18f)) {
                    compose.runOnIdle { width.intValue = viewportWidth; scale.value = uiScale }
                    val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
                    val navigation = compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot
                    val toast = compose.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot
                    val text = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
                    assertTrue(toast.top > navigation.bottom, "The notification must not obscure the navigation")
                    assertTrue(toast.left >= window.left && toast.right <= window.right + 1f)
                    assertTrue(toast.bottom <= window.bottom + 1f)
                    assertEquals(window.bottom, toast.bottom, 1f)
                    assertEquals(window.right, toast.right, 1f)
                    assertTrue(toast.width <= 452f * pixelsPerDp + 1f, "The message must stay compact")
                    assertTrue(text.left >= toast.left && text.right <= toast.right + 1f)
                    val directory = File("build/reports/toast-layout").apply { mkdirs() }
                    ImageIO.write(compose.onNodeWithTag("window").captureToImage().toAwtImage(), "png",
                        File(directory, "watch-later-$viewportWidth-$uiScale.png"))
                }
            }
            compose.onNodeWithContentDescription("Watch Later").performMouseInput { click() }
            compose.onNodeWithContentDescription("Watch Later").assertIsSelected()
            compose.runOnIdle { assertEquals(listOf(2), clicks) }
        } finally {
            compose.runOnIdle { NuvioToastController.dismiss() }
        }
    }
}
