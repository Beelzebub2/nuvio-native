package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoogleBrandMarkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sharedGoogleMarkLoadsOnAndroid() {
        // Account linking and Google login both render this shared image.
        compose.setContent {
            GoogleBrandMark(Modifier.size(24.dp).testTag("google-mark"))
        }
        compose.onNodeWithTag("google-mark").assertIsDisplayed()
    }
}
