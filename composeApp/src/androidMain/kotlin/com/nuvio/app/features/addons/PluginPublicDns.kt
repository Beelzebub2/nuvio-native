package com.nuvio.app.features.addons

import com.nuvio.app.core.network.IPv4FirstDns
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** A plugin must not resolve any host to loopback, LAN, metadata or reserved addresses. */
internal object PluginPublicDns : Dns {
    private val delegate = IPv4FirstDns()

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicPluginAddress(it) }) {
            throw UnknownHostException("Plugin network access denied")
        }
        return addresses
    }
}

internal fun isPublicPluginAddress(ip: InetAddress): Boolean {
    if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isSiteLocalAddress ||
        ip.isLinkLocalAddress || ip.isMulticastAddress) return false
    val bytes = ip.address.map { it.toInt() and 0xff }
    if (bytes.size == 4) {
        val (a, b, c) = bytes
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            a == 100 && b in 64..127 || a == 169 && b == 254 ||
            a == 172 && b in 16..31 || a == 192 && b == 168 ||
            a == 192 && b == 0 && c == 0 || a == 192 && b == 0 && c == 2 ||
            a == 198 && b in 18..19 || a == 198 && b == 51 && c == 100 ||
            a == 203 && b == 0 && c == 113)
    }
    // Only global unicast IPv6, excluding tunneling and documentation ranges.
    return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
            (bytes[2] == 0x00 && bytes[3] == 0x00 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02)
}
