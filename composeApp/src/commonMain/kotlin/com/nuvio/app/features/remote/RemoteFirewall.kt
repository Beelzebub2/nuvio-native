package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the operating system lets phones on the local network reach this PC's
 * remote ports (UDP 47788 discovery, TCP 47789 sessions).
 */
internal enum class RemoteFirewallState {
    /** Nothing to ask for: not a desktop host, or the OS needs no extra permission. */
    NotNeeded,
    /** A check is running. */
    Checking,
    /** Phones on a private network can reach the remote ports. */
    Allowed,
    /** Inbound traffic to the remote ports is blocked; [RemoteFirewallAccess.requestAccess] can fix it. */
    Blocked,
    /** The rules allow private networks, but the active network is marked Public. */
    PublicNetwork,
    /** Waiting for the user to answer the operating system's administrator prompt. */
    Requesting,
    /** The user declined the prompt, or adding the rules failed. */
    Failed,
}

internal interface RemoteFirewallAccess {
    val state: StateFlow<RemoteFirewallState>

    /** Re-checks without asking for elevation. Returns immediately; the result lands in [state]. */
    fun refresh()

    /** Asks the OS for permission (one administrator prompt on Windows) to allow the remote ports. */
    fun requestAccess()

    /** Opens the OS page where the user can mark the current network as Private. */
    fun openNetworkSettings()
}

internal expect fun createRemoteFirewallAccess(): RemoteFirewallAccess

/** For platforms where the firewall never blocks the remote (phones, and desktops that prompt on their own). */
internal object NoRemoteFirewallAccess : RemoteFirewallAccess {
    private val fixed = MutableStateFlow(RemoteFirewallState.NotNeeded)
    override val state: StateFlow<RemoteFirewallState> = fixed.asStateFlow()
    override fun refresh() = Unit
    override fun requestAccess() = Unit
    override fun openNetworkSettings() = Unit
}
