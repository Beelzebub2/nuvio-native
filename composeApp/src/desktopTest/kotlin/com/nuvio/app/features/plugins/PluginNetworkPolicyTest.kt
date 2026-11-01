package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.network.requirePublicPluginUrl
import com.nuvio.app.features.addons.isPublicPluginAddress
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginNetworkPolicyTest {
    @Test fun onlyPublicHttpsUrlsAreAllowed() {
        assertEquals("https://public.example.org/stream", requirePublicPluginUrl("https://public.example.org/stream"))
        assertEquals("https://feed.cafe/file.js", requirePublicPluginUrl("https://feed.cafe/file.js"))
        for (url in listOf(
            "http://public.example.org", "https://localhost:443/", "https://internal.local",
            "https://127.0.0.1", "https://169.254.169.254/metadata", "https://10.0.0.1",
            "https://[::1]/", "https://user:password@public.example.org/", "https://remote.internal/api",
            "file:///etc/passwd", "https://public.example.org@localhost/",
        )) assertFailsWith<IllegalArgumentException>(url) { requirePublicPluginUrl(url) }
    }

    @Test fun resolvedAddressesCannotEnterLocalOrMetadataNetworks() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "169.254.169.254", "192.168.1.1", "172.20.2.4",
            "100.100.100.200", "198.18.1.2", "203.0.113.45", "::1", "fe80::1", "fc00::1")) {
            assertFalse(isPublicPluginAddress(InetAddress.getByName(ip)), ip)
        }
        assertTrue(isPublicPluginAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(isPublicPluginAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicPluginAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
