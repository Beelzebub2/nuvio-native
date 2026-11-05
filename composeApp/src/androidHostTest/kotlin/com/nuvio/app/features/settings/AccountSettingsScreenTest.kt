package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
class AccountSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    // The screen observes the process-wide AuthRepository; restore it to avoid
    // contaminating unrelated Compose tests with a fabricated signed-in user.
    private val authState: MutableStateFlow<AuthState> by lazy {
        @Suppress("UNCHECKED_CAST")
        (AuthRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(null) as MutableStateFlow<AuthState>)
    }
    private lateinit var originalState: AuthState

    @BeforeTest fun saveAuthState() {
        PlatformLocalAccountDataCleaner.initialize(RuntimeEnvironment.getApplication())
        originalState = authState.value
    }

    @AfterTest fun restoreAuthState() {
        authState.value = originalState
    }

    @Test
    fun credentialCardShowsBothAccountSecurityActions() {
        val account = AuthState.Authenticated(
            userId = "test-account",
            email = "test@example.com",
            isAnonymous = false,
        )
        compose.setContent { NuvioTheme { AccountCredentialsCard(account) } }
        compose.onNodeWithTag("account-credentials-card").assertExists()
        compose.onNodeWithTag("account-change-password-button").assertExists()
        compose.onNodeWithTag("account-change-email-button").assertExists()
    }

    @Test
    fun accountSettingsOpensForUnauthenticatedUser() {
        authState.value = AuthState.Unauthenticated
        compose.setContent { NuvioTheme { AccountSettingsScreen(onBack = {}) } }
        compose.onNodeWithTag("account-overview-card").assertExists()
    }

    @Test
    fun accountSettingsOpensForSignedInUser() {
        authState.value = AuthState.Authenticated(
            userId = "test-account",
            email = "test@example.com",
            isAnonymous = false,
        )
        compose.setContent { NuvioTheme { AccountSettingsScreen(onBack = {}) } }
        compose.onNodeWithTag("account-overview-card").assertExists()
        compose.onNodeWithTag("account-sign-out-button").assertExists()
    }
}
