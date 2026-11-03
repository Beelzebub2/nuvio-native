package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.remote.DesktopRemote
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
class DesktopRemoteSettingsTest {
    @get:Rule val compose = createComposeRule()

    @AfterTest
    fun resetRemote() {
        DesktopRemote.visible.value = false
        DesktopRemote.transport.stop()
    }

    @Test
    fun pcControllerAndManualSearchRemainAvailableWithoutDiscoveredPcs() {
        DesktopRemote.transport.stop()
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.visible.value = false

        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    settingsRootContent(
                        isTablet = false,
                        onPlaybackClick = {},
                        onAppearanceClick = {},
                        onAdvancedClick = {},
                        onNotificationsClick = {},
                        onContentDiscoveryClick = {},
                        onIntegrationsClick = {},
                        onTrackingClick = {},
                        onSupportersContributorsClick = {},
                        onLicensesAttributionsClick = {},
                        onAccountClick = {},
                        showGeneralSection = false,
                        showAboutSection = false,
                        showAdvancedSection = false,
                        showAttribution = false,
                    )
                }
            }
        }

        compose.onNodeWithText("PC controller").assertIsDisplayed().performClick()
        // Keep the Robolectric UI test focused on Settings navigation and the
        // open/scan state. Espresso idleness checks can time out on the Material
        // AlertDialog animation even though the real Android button remains
        // usable. DesktopRemoteProtocolTest separately verifies that the LAN
        // discover() action returns the sign-in prerequisite without an account.
        compose.runOnIdle {
            assertTrue(DesktopRemote.visible.value)
            assertTrue(DesktopRemote.desktops.value.isEmpty())
            assertFalse(DesktopRemote.scanning.value)
            assertTrue(DesktopRemote.status.value.contains("Sign in"))
        }
    }
}
