package com.nuvio.app.features.remote

import java.io.InputStream
import java.io.OutputStream

/** Shared RFCOMM service ID; this is a service selector, not an authentication key. */
internal const val REMOTE_BLUETOOTH_SERVICE = "7e454db1-4605-4d90-b8c6-f8167d83344e"

/** A Bluetooth socket's streams are subjected to the same encrypted, approved handshake as LAN. */
internal class RemoteBluetoothConnection(
    val input: InputStream,
    val output: OutputStream,
    val peer: String,
    val close: () -> Unit,
)

internal interface RemoteBluetoothClient {
    val supported: Boolean
    /**
     * Reports nearby PCs, then calls [onFinished] once when the search ends:
     * null if it ran, or why it could not. Nothing is reported after [stop].
     */
    fun discover(onFound: (RemoteDesktop) -> Unit, onFinished: (failure: String?) -> Unit)
    suspend fun connect(address: String): RemoteBluetoothConnection
    fun stop()
}

/** Native desktop service bridges RFCOMM to a dedicated loopback-only TCP listener. */
internal interface RemoteBluetoothHost {
    fun start(loopbackPort: Int): Boolean
    fun stop()
}
