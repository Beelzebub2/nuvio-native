package com.nuvio.app.features.auth

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
class AuthScreenStartupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openingLoginPageKeepsEmailAndPasswordAvailableWithoutPasskeyInteraction() {
        compose.setContent { NuvioTheme { AuthScreen() } }

        // Credentials/password managers must be optional at screen startup.
        // Both email and password fields remain usable without choosing one.
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
    }
}
