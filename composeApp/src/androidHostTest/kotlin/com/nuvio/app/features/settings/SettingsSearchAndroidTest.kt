package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-w390dp-h844dp")
class SettingsSearchAndroidTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun clearFocus() {
        SettingsSearchFocus.clear()
    }

    @Test
    fun interruptedBringIntoViewReleasesClaimWithoutDiscardingSearchRequest() {
        SettingsSearchFocus.request(SettingsPage.Playback, "playback-auto-play-next", "Auto-play Next Episode")
        val request = requireNotNull(SettingsSearchFocus.pending)
        assertTrue(SettingsSearchFocus.claim(request))
        assertTrue(SettingsSearchFocus.isClaimed(request))
        SettingsSearchFocus.release(request)
        assertEquals(request, SettingsSearchFocus.pending)
        assertFalse(SettingsSearchFocus.isClaimed(request))
        assertTrue(SettingsSearchFocus.claim(request))
    }

    @Test
    fun androidPhoneHighlightsPlaybackControlAfterSearchNavigation() {
        // Real search-result taps are exercised via adb against an Android APK.
        // This test isolates destination scrolling with a pending search request.
        SettingsSearchFocus.request(
            SettingsPage.Playback,
            "playback-auto-play-next",
            "Auto-play Next Episode",
        )
        compose.setContent {
            NuvioTheme {
                SettingsScreen(initialPageName = SettingsPage.Playback.name)
            }
        }
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodes(hasTestTag("settings-search-focused"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings-search-focused").assertIsDisplayed()
    }

    @Test
    fun androidPhoneLocatesAndHighlightsTheDeepTrackingProviderCard() {
        SettingsSearchFocus.request(SettingsPage.TraktAuthentication, "mdblist-authentication", "MDBList")
        compose.setContent {
            NuvioTheme {
                SettingsScreen(initialPageName = SettingsPage.TraktAuthentication.name)
            }
        }
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodes(hasTestTag("settings-search-focused"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings-search-focused").assertIsDisplayed()
    }

    @Test
    fun androidPhoneRevealsASettingLoadedAfterItsSectionWasFirstComposed() {
        val loaded = mutableStateOf(false)
        SettingsSearchFocus.request(SettingsPage.Advanced, "dynamic-control", "Dynamic control")
        compose.setContent {
            NuvioTheme {
                SettingsSearchPageScope(SettingsPage.Advanced) {
                    SearchableSettingsNuvioScreen(SettingsPage.Advanced) {
                        repeat(16) { index ->
                            item {
                                Text("Other setting $index", Modifier.height(80.dp))
                            }
                        }
                        if (loaded.value) {
                            item {
                                SettingsNavigationRow(
                                    title = "Dynamic control",
                                    description = null,
                                    isTablet = false,
                                    searchKey = "dynamic-control",
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { loaded.value = true }
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodes(hasTestTag("settings-search-focused"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings-search-focused").assertIsDisplayed()
    }
}
