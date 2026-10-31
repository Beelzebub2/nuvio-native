package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigurationTest {
    @Test
    fun shippedConfigurationUsesStarlitSyncWithoutUpstreamAccountFeatures() {
        assertEquals("https://accounts.example.invalid", SupabaseConfig.STARLIT_URL)
        val server = officialConfiguration()
        assertEquals(SupabaseConfig.URL.trim().trimEnd('/'), server.backendUrl)
        assertTrue(server.publishableKey.isNotBlank())
        assertFalse(server.isCustom)
        if (server.backendUrl == SupabaseConfig.STARLIT_URL) {
            assertTrue(server.isSelfHosted)
            assertFalse(server.capabilities.tvLogin)
            assertFalse(server.supportsNuvioAccountFeatures)
        }
    }

    @Test
    fun savedCustomStarlitServerHasSameLimitsWhileOtherCustomServersKeepTheirCapabilities() {
        val starlit = ServerConfiguration(
            "https://accounts.example.invalid/", "public-key", ServerCapabilities(true, false), true,
        )
        assertTrue(starlit.isCustom)
        assertTrue(starlit.isSelfHosted)
        assertFalse(starlit.supportsNuvioAccountFeatures)
        val custom = starlit.copy(
            backendUrl = "https://my-server.example", publishableKey = "my-key",
            capabilities = ServerCapabilities(true, true),
        )
        assertEquals("my-key", custom.publishableKey)
        assertTrue(custom.isCustom)
        assertTrue(custom.capabilities.tvLogin)
        assertTrue(custom.supportsNuvioAccountFeatures)
    }
}
