package com.nuvio.app.features.remote

// A PC is a remote host, not an Android controller. No desktop-side remembered
// authorization list exists; remote connections always need live consent.
internal actual object RememberedRemoteDeviceStore {
    actual fun read(account: RemoteAccount): List<RememberedRemoteDevice> = emptyList()
    actual fun write(account: RemoteAccount, devices: List<RememberedRemoteDevice>) = Unit
    actual fun autoReconnectEnabled(account: RemoteAccount): Boolean = false
    actual fun setAutoReconnectEnabled(account: RemoteAccount, enabled: Boolean) = Unit
}
