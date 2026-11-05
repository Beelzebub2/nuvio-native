package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DesktopAccountCredentialsUiTest {
    @Test
    fun passwordAndEmailControlsOpenTheirRespectiveVerificationDialogs() {
        listOf(420, 850).forEach { width ->
            runDesktopComposeUiTest(width = width, height = 850) {
                setContent {
                    NuvioTheme {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            AccountCredentialsCard(
                                AuthState.Authenticated("account-fixture", "fixture@example.com", isAnonymous = false),
                            )
                        }
                    }
                }
                onNodeWithTag("account-credentials-card").assertExists()
                onNodeWithTag("account-change-password-button").performClick()
                onNodeWithTag("account-current-password-field").assertExists()
                onNodeWithTag("account-credentials-cancel").performClick()
                onNodeWithTag("account-change-email-button").performClick()
                onNodeWithTag("account-new-email-field").assertExists()
            }
        }
    }
}