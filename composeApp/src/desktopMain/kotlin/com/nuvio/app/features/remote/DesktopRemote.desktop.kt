package com.nuvio.app.features.remote

internal actual fun createDesktopRemoteTransport(): DesktopRemoteTransport =
    LanDesktopRemote(
        bluetoothHost = if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
            WindowsBluetoothRemoteHost()
        else null,
        bluetoothTrustStore = if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
            WindowsRemoteBluetoothTrustStore
        else null,
    )
