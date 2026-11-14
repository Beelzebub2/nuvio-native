package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartTvRemoteTest {
    @Test fun onlyPrivateIpv4TargetsAreAccepted() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.20").forEach {
            assertTrue(isPrivateSmartTvAddress(it), it)
        }
        listOf("8.8.8.8", "127.0.0.1", "169.254.2.1", "172.32.0.1", "192.169.1.1",
            "192.168.1.2/path", "tv.local", "192.168.1.1:8002", "192.168.1.01", "256.1.1.1",
            "192.168.1.\n2").forEach { assertFalse(isPrivateSmartTvAddress(it), it) }
    }

    @Test fun vendorKeyMappingsHaveValidPaths() {
        SmartTvKey.entries.forEach { key ->
            assertTrue(key.samsung?.startsWith("KEY_") == true, key.name)
            assertTrue(
                key.lgPointer in setOf(null, "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME") ||
                    key.lgSsap?.startsWith("ssap://") == true, key.name,
            )
        }
        assertEquals("KEY_RETURN", SmartTvKey.Back.samsung)
        assertEquals("BACK", SmartTvKey.Back.lgPointer)
    }
}
