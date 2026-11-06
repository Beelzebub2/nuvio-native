package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemotePairingQrTest {
    @Test
    fun onlyCanonicalPrivateIpv4DiscoveryHintsRoundTrip() {
        listOf("10.0.0.2", "172.16.2.3", "172.31.255.254", "192.168.1.50").forEach { address ->
            assertEquals("starlit-pc:1:$address", remotePairingQrPayload(address))
            assertEquals(address, parseRemotePairingQr(checkNotNull(remotePairingQrPayload(address))))
        }
        listOf("127.0.0.1", "8.8.8.8", "172.32.0.1", "169.254.1.2", "224.0.0.1",
            "192.168.001.2", "192.168.1.256", "192.168.1.2:47788", "192.168.1.2\n",
            "192.168.1.2?token=secret", "https://192.168.1.2", "desktop.local", "::1", "").forEach {
            assertNull(remotePairingQrPayload(it), it)
            assertNull(parseRemotePairingQr("starlit-pc:1:$it"), it)
        }
        listOf("starlit-pc:2:192.168.1.2", "STARLIT-PC:1:192.168.1.2",
            "starlit-pc:1:192.168.1.2#play", "nuvio://auth?code=secret", "x".repeat(10000))
            .forEach { assertNull(parseRemotePairingQr(it)) }
    }
}
