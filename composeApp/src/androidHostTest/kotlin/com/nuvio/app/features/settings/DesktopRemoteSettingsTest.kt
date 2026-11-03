package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.remote.DesktopRemote
import com.nuvio.app.features.remote.DesktopRemoteDialogs
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

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
                Box {
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
                    DesktopRemoteDialogs()
                }
            }
        }

        compose.onNodeWithText("PC controller").assertIsDisplayed().performClick()
        compose.onNodeWithText("Search local network").assertIsDisplayed().performClick()
        compose.onNodeWithText(
            "Sign in to the same account and server on your phone and PC before searching."
        ).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(DesktopRemote.scanning.value)
        }
    }
}
