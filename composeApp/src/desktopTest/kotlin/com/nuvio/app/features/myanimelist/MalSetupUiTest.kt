package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.Test

class MalSetupUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun setupExplainsThePublicClientAndExactRedirect() {
        compose.setContent { NuvioTheme { Surface { Box(Modifier.size(950.dp, 760.dp)) { MyAnimeListSettingsCard() } } } }
        compose.waitUntil(5_000L) { !MyAnimeListRepository.state.value.busy }
        compose.onNodeWithText("Set up / Connect").performClick()
        compose.onNodeWithText("Public client ID").assertIsDisplayed()
        compose.onNodeWithText(MAL_REDIRECT).assertIsDisplayed()
        compose.onNodeWithText("Open MyAnimeList API setup").assertIsDisplayed()
    }
}
