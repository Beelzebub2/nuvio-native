package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class RuntimeFormatUiTest {
    @Test
    fun runtimeParsingRetainsExistingInputFormats() {
        assertEquals(125, parseRuntimeMinutes("2h 5m"))
        assertEquals(125, parseRuntimeMinutes("2:05"))
        assertEquals(125, parseRuntimeMinutes("125"))
        assertNull(parseRuntimeMinutes("unknown"))
        assertNull(parseRuntimeMinutes(null))
    }

    @Test
    fun detailRuntimeUsesComposeResources() = runDesktopComposeUiTest(width = 480, height = 280) {
        setContent {
            Column {
                Text(formatRuntimeForDisplay("1:05").orEmpty())
                Text(formatRuntimeForDisplay("45").orEmpty())
                Text(formatRuntimeForDisplay("unknown").orEmpty())
                Text(formatRuntimeFromMinutes(120))
            }
        }
        onNodeWithText("1h 5m").assertExists()
        onNodeWithText("45m").assertExists()
        onNodeWithText("unknown").assertExists()
        onNodeWithText("2h").assertExists()
    }
}
