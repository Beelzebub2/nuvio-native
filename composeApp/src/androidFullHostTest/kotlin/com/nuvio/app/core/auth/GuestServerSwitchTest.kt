package com.nuvio.app.core.auth

import android.app.Application
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.network.SupabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuestServerSwitchTest {
    @Test
    fun guestCanPrepareToConnectItsFirstAccountServerWithoutCreatingAnEmptyBackendClient() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        AuthStorage.initialize(context)
        ServerConfigurationStorage.initialize(context)
        val original = ServerConfigurationRepository.active.value
        try {
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                backendUrl = "",
                publishableKey = "",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = false),
                isCustom = true,
            )))
            SupabaseProvider.reset()
            AuthRepository.signInAnonymously()

            assertTrue(AuthRepository.prepareForServerSwitch().isSuccess)
            assertNull(AuthStorage.loadAnonymousUserId())
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        } finally {
            AuthStorage.clearAnonymousUserId()
            if (original.isCustom) ServerConfigurationRepository.saveCustom(original)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
        }
    }
}
