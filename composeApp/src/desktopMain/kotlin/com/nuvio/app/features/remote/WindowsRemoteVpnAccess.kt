package com.nuvio.app.features.remote

import com.nuvio.app.core.storage.DesktopStorage
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * VPN exposure is separately opted into for each Starlit account and bound
 * to one active, recognizable overlay adapter. A routed public/Wi-Fi address
 * cannot become eligible merely because it shares the remote port.
 */
internal object WindowsRemoteVpnAccess : RemoteVpnHostAccess {
    @Volatile private var account: RemoteAccount? = null
    private val store get() = DesktopStorage.store("nuvio_remote_vpn")

    override fun accountChanged(account: RemoteAccount?) {
        this.account = account
    }

    private fun key(account: RemoteAccount): String = remoteLanTrustKey(account, "vpn-approved-address")

    override fun interfaces(): List<RemoteVpnInterface> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback && isRemoteVpnInterface(it.name.orEmpty(), it.displayName.orEmpty()) }
            .flatMap { nic ->
                Collections.list(nic.inetAddresses).asSequence().mapNotNull { address ->
                    (address as? Inet4Address)?.takeIf(::isPrivateRemoteVpnAddress)?.hostAddress?.let {
                        RemoteVpnInterface(it, nic.displayName.orEmpty().take(96))
                    }
                }
            }
            .distinctBy { it.address }
            .toList()
    }.getOrDefault(emptyList())

    override fun approvedAddress(): String? {
        val current = account ?: return null
        return runCatching { store.getString(key(current)) }
            .getOrNull()?.takeIf { normalizedRemoteVpnHost(it) == it && strictRemoteIpv4(it) != null }
    }

    override fun approveAddress(address: String?): Boolean {
        val current = account ?: return false
        if (address != null && interfaces().none { it.address == address }) return false
        return runCatching {
            store.putString(key(current), address)
            true
        }.getOrDefault(false)
    }

    override fun permits(socket: Socket): Boolean {
        val selected = approvedAddress() ?: return false
        return allowsRemoteVpnPeer(
            approvedLocalAddress = selected,
            activeVpnAddresses = interfaces().map { it.address },
            localAddress = socket.localAddress,
            peerAddress = socket.inetAddress,
        )
    }
}
