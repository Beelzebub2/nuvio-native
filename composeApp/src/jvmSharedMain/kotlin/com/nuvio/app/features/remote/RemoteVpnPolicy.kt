package com.nuvio.app.features.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.Locale

/** A VPN address is a route candidate, never a trust identity or proof of a VPN. */
internal fun isPrivateRemoteVpnAddress(address: InetAddress): Boolean {
    if (address !is Inet4Address || address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isMulticastAddress) return false
    val octets = address.address.map { it.toInt() and 0xff }
    // Tailscale uses CGNAT space. Only the *selected host interface*, not this range,
    // establishes whether inbound traffic may use the private VPN route.
    return address.isSiteLocalAddress ||
        (octets[0] == 100 && octets[1] in 64..127)
}

/** Accept only explicit IPv4 addresses or well-formed DNS names, not arbitrary URLs. */
internal fun normalizedRemoteVpnHost(value: String): String? {
    val host = value.trim().lowercase(Locale.ROOT)
    if (host.length !in 1..253 || host.any { it.code < 33 || it.code > 126 }) return null
    strictRemoteIpv4(host)?.let { return it.hostAddress }
    if (host.all { it == '.' || it in '0'..'9' }) return null
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { label ->
            label.length !in 1..63 || label.first() == '-' || label.last() == '-' ||
                label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
        } || labels.last().length < 2 || labels.last().any { it !in 'a'..'z' }) return null
    return host
}

internal fun isRemoteVpnInterface(name: String, displayName: String): Boolean {
    val text = (name + " " + displayName).lowercase(Locale.ROOT)
    return listOf("tailscale", "zerotier", "netbird", "wireguard", "wintun", "openvpn", "tunnelblick",
        "vpn tunnel", "radmin vpn").any(text::contains) ||
        listOf("wg", "tun", "tap", "zt").any { name.lowercase(Locale.ROOT).startsWith(it) }
}

internal data class RemoteVpnInterface(val address: String, val name: String)

internal fun allowsRemoteVpnPeer(
    approvedLocalAddress: String?,
    activeVpnAddresses: Collection<String>,
    localAddress: InetAddress,
    peerAddress: InetAddress,
): Boolean = approvedLocalAddress != null &&
    approvedLocalAddress in activeVpnAddresses &&
    localAddress.hostAddress == approvedLocalAddress &&
    isPrivateRemoteVpnAddress(localAddress) && isPrivateRemoteVpnAddress(peerAddress)

/** Host-only policy. A valid VPN address on the client is not sufficient for inbound access. */
internal interface RemoteVpnHostAccess {
    fun accountChanged(account: RemoteAccount?)
    fun interfaces(): List<RemoteVpnInterface>
    fun approvedAddress(): String?
    fun approveAddress(address: String?): Boolean
    fun permits(socket: Socket): Boolean
}
