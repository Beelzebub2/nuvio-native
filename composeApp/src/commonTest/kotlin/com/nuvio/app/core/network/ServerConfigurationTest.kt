package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigurationTest {
    @Test
    fun officialConfigurationSupportsConfiguredAndBlankBuilds() {
        val server = officialConfiguration()
        assertEquals(SupabaseConfig.URL.trim().trimEnd('/'), server.backendUrl)
        assertEquals(SupabaseConfig.ANON_KEY.trim(), server.publishableKey)
        assertFalse(server.isCustom)

        // Local builds without account-server settings can still run as guests.
        if (server.backendUrl.isBlank()) {
            assertTrue(server.publishableKey.isBlank())
            assertFalse(server.isSecure)
        }

        if (server.backendUrl.equals(SupabaseConfig.STARLIT_URL.trim().trimEnd('/'), ignoreCase = true)) {
            assertTrue(server.isSelfHosted)
            assertFalse(server.capabilities.tvLogin)
            assertFalse(server.supportsNuvioAccountFeatures)
        } else {
            // A build-time override is a separate backend, not the official Starlit server.
            assertTrue(server.capabilities.tvLogin)
            assertTrue(server.supportsNuvioAccountFeatures)
        }
    }

    @Test
    fun savedCustomStarlitServerHasSameLimitsWhileOtherCustomServersKeepTheirCapabilities() {
        val custom = ServerConfiguration(
            backendUrl = "https://my-server.example", publishableKey = "my-key",
            capabilities = ServerCapabilities(true, true), isCustom = true,
        )
        assertEquals("my-key", custom.publishableKey)
        assertTrue(custom.isCustom)
        assertTrue(custom.capabilities.tvLogin)
        assertTrue(custom.supportsNuvioAccountFeatures)

        // Starlit's special capabilities apply only when an official URL is configured.
        val officialUrl = SupabaseConfig.STARLIT_URL.trim().trimEnd('/')
        if (officialUrl.isNotBlank()) {
            val starlit = custom.copy(
                backendUrl = "$officialUrl/", publishableKey = "public-key",
                capabilities = ServerCapabilities(true, false),
            )
            assertTrue(starlit.isCustom)
            assertTrue(starlit.isSelfHosted)
            assertFalse(starlit.supportsNuvioAccountFeatures)
        }
    }
}
