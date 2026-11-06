package com.nuvio.app.features.remote

internal actual fun createRemoteFirewallAccess(): RemoteFirewallAccess =
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
        WindowsRemoteFirewall
    else NoRemoteFirewallAccess
