package com.nuvio.app.features.p2p

import java.net.NetworkInterface

internal data class DesktopP2pEnvironment(
    val wallTimeMs: Long,
    val monotonicTimeMs: Long,
    // null means enumeration failed; an empty set means all usable interfaces are down.
    val networkAddresses: Set<String>?,
)

internal fun desktopP2pEnvironment(): DesktopP2pEnvironment = DesktopP2pEnvironment(
    wallTimeMs = System.currentTimeMillis(),
    monotonicTimeMs = System.nanoTime() / 1_000_000L,
    networkAddresses = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    .map { "${network.index}:${it.hostAddress}" }
            }.toSet()
    }.getOrNull(),
)

internal enum class DesktopP2pRecoveryReason { Resume, NetworkChange, EngineUnavailable }

/** Sample regularly while the engine is ready. Wait for interfaces to settle before rebuilding sockets. */
internal class DesktopP2pRecoverySignals(initial: DesktopP2pEnvironment) {
    private var previous = initial
    private var network = initial.networkAddresses
    private var stableSamples = 0
    private var pending: DesktopP2pRecoveryReason? = null

    fun observe(current: DesktopP2pEnvironment): DesktopP2pRecoveryReason? {
        // Wall time also catches suspend on platforms whose monotonic clock pauses in sleep.
        if (current.wallTimeMs - previous.wallTimeMs >= 20_000L ||
            current.monotonicTimeMs - previous.monotonicTimeMs >= 20_000L) {
            pending = DesktopP2pRecoveryReason.Resume
            stableSamples = 0
        }
        previous = current
        val addresses = current.networkAddresses
        if (addresses != null) {
            if (network != null && addresses != network) {
                pending = pending ?: DesktopP2pRecoveryReason.NetworkChange
                stableSamples = 0
            } else {
                stableSamples += 1
            }
            network = addresses
        } else if (pending == DesktopP2pRecoveryReason.Resume) {
            stableSamples += 1
        }
        val usableNetwork = !addresses.isNullOrEmpty() ||
            (addresses == null && pending == DesktopP2pRecoveryReason.Resume && network?.isEmpty() != true)
        if (pending != null && usableNetwork && stableSamples >= 2) {
            return pending.also { pending = null }
        }
        return null
    }

    val waitingForNetwork: Boolean get() = network?.isEmpty() == true
    val recoveryPending: Boolean get() = pending != null
}
