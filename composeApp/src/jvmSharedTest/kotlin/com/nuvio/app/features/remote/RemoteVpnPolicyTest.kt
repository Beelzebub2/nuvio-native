package com.nuvio.app.features.remote

import java.net.InetAddress
import java.net.Inet4Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteVpnPolicyTest {
    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun privateOverlayIpsAreCandidatesButNeverAuthorization() {
        listOf("100.64.0.1", "100.127.255.254", "10.10.0.2", "172.16.0.2", "192.168.10.2")
            .forEach { assertTrue(isPrivateRemoteVpnAddress(ip(it)), it) }
        listOf("100.63.255.255", "100.128.0.1", "8.8.8.8", "127.0.0.1", "169.254.1.2",
            "224.0.0.1", "0.0.0.0", "255.255.255.255")
            .forEach { assertFalse(isPrivateRemoteVpnAddress(ip(it)), it) }
    }

    @Test
    fun overlappingVpnPrivateAddressCannotInheritPhysicalLanPermission() {
        val home = RemoteLanInterface(
            address = ip("10.0.0.20") as Inet4Address,
            networkPrefixLength = 8,
            broadcast = null,
            name = "Ethernet",
            displayName = "Ethernet",
        )
        assertTrue(isRemoteLanInbound(ip("10.0.0.20"), ip("10.9.8.7"), listOf(home)))
        assertFalse(isRemoteLanInbound(ip("10.9.8.6"), ip("10.9.8.7"), listOf(home)))
        assertFalse(isRemoteLanInbound(ip("100.101.10.2"), ip("100.101.10.3"), listOf(home)))
    }

    @Test
    fun remoteVpnMustArriveOnTheApprovedAndCurrentlyActiveOverlayAddress() {
        val active = listOf("100.100.12.20")
        assertTrue(allowsRemoteVpnPeer("100.100.12.20", active, ip("100.100.12.20"), ip("100.80.21.1")))
        assertFalse(allowsRemoteVpnPeer(null, active, ip("100.100.12.20"), ip("100.80.21.1")))
        assertFalse(allowsRemoteVpnPeer("100.100.12.20", emptyList(), ip("100.100.12.20"), ip("100.80.21.1")))
        assertFalse(allowsRemoteVpnPeer("100.100.12.20", active, ip("192.168.1.20"), ip("100.80.21.1")))
        assertFalse(allowsRemoteVpnPeer("100.100.12.20", active, ip("100.100.12.20"), ip("8.8.8.8")))
        assertFalse(allowsRemoteVpnPeer("100.100.12.20", active, ip("100.100.12.20"), ip("127.0.0.1")))
    }

    @Test
    fun validatesExplicitEndpointWithoutAcceptingArbitraryUrlsOrPorts() {
        assertEquals("100.101.42.18", normalizedRemoteVpnHost(" 100.101.42.18 "))
        assertEquals("pc.example.ts.net", normalizedRemoteVpnHost("PC.Example.TS.NET"))
        assertNull(normalizedRemoteVpnHost("http://pc.example.ts.net"))
        assertNull(normalizedRemoteVpnHost("100.101.42.18:47789"))
        assertNull(normalizedRemoteVpnHost("100.101.42.999"))
        assertNull(normalizedRemoteVpnHost("1.2.3.4/32"))
        assertNull(normalizedRemoteVpnHost("pc..ts.net"))
        assertNull(normalizedRemoteVpnHost("pc.example.ts.net@proxy"))
        assertNull(normalizedRemoteVpnHost("localhost"))
    }

    @Test
    fun neverMistakesVirtualOverlaysForLanAdapters() {
        assertTrue(isRemoteVpnInterface("Tailscale", "Tailscale Tunnel"))
        assertTrue(isRemoteVpnInterface("wg0", "WireGuard"))
        assertTrue(isRemoteVpnInterface("zt123", "ZeroTier One"))
        assertTrue(isRemoteVpnInterface("NetBird", "NetBird Tunnel"))
        assertFalse(isRemoteVpnInterface("Ethernet", "Intel 2.5Gb Ethernet"))
        assertFalse(isRemoteVpnInterface("Wi-Fi", "802.11ax Wireless Adapter"))
        assertFalse(isRemoteVpnInterface("vEthernet (Default Switch)", "Hyper-V Virtual Ethernet"))
    }

    @Test
    fun vpnAndLanRememberedPathsKeepDistinctHintsButNotIndependentTrust() {
        val id = "0123456789abcdef0123456789abcdef"
        val local = rememberRemoteDevice(emptyList(), "Home", "192.168.1.20", 1L, trustedId = id)
        val vpn = rememberRemoteDevice(local, "Home", "pc.example.ts.net", 2L, trustedId = id, vpn = true)
        assertEquals(2, vpn.size)
        assertTrue(vpn[0].vpn)
        assertEquals("pc.example.ts.net", vpn[0].address)
        assertFalse(vpn[1].vpn)
        assertEquals(id, vpn[0].trustedId)
        assertEquals(id, vpn[1].trustedId)
        assertEquals(vpn, rememberRemoteDevice(vpn, "bad", "8.8.8.8", 3L, vpn = true))
    }
}
