package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class MouseBackNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mouseBackNavigatesOncePerPressAndOrdinaryClicksDoNotNavigateBack() {
        var backs = 0
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1000.dp, 760.dp).testTag("app")) {
                    MainAppContent(
                        initialTab = AppScreenTab.Settings,
                        ownsAppRuntime = false,
                        showLaunchOverlay = false,
                        onGoBack = { backs++ },
                    )
                }
            }
        }
        val app = compose.onNodeWithTag("app")
        // The test API names the first three buttons; 3 and 4 are back and forward.
        app.performMouseInput { click(position = Offset(4f, 300f), button = MouseButton(3)) }
        compose.runOnIdle { assertEquals(1, backs) }
        app.performMouseInput { click(position = Offset(4f, 300f), button = MouseButton(4)) }
        app.performMouseInput { click(position = Offset(4f, 300f)) }
        compose.runOnIdle { assertEquals(1, backs) }
        app.performMouseInput {
            moveTo(Offset(4f, 300f))
            press(MouseButton(3))
            press(MouseButton.Primary)
            release(MouseButton.Primary)
            release(MouseButton(3))
        }
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
