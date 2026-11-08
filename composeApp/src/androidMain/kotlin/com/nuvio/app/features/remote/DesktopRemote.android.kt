package com.nuvio.app.features.remote

internal actual fun createDesktopRemoteTransport(): DesktopRemoteTransport =
    LanDesktopRemote(
        bluetoothClient = AndroidBluetoothRemoteClient,
        bluetoothTrustStore = AndroidRemoteBluetoothTrustStore,
        lanTrustStore = AndroidRemoteLanTrustStore,
    )
