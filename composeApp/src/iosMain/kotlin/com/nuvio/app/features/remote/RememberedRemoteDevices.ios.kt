package com.nuvio.app.features.remote

internal actual object RememberedRemoteDeviceStore {
    actual fun read(account: RemoteAccount): List<RememberedRemoteDevice> = emptyList()
    actual fun write(account: RemoteAccount, devices: List<RememberedRemoteDevice>) = Unit
}
