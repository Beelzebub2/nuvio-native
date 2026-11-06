package com.nuvio.app.features.remote

// Phones only connect out to the PC, so there is no inbound firewall to open.
internal actual fun createRemoteFirewallAccess(): RemoteFirewallAccess = NoRemoteFirewallAccess
