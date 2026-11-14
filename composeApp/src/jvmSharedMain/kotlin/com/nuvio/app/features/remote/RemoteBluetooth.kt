package com.nuvio.app.features.remote

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream

/** Shared RFCOMM service ID; this is a service selector, not an authentication key. */
internal const val REMOTE_BLUETOOTH_SERVICE = "7e454db1-4605-4d90-b8c6-f8167d83344e"

/** Called only for devices actually reported by this inquiry, never for cached bonds. */
internal fun isBluetoothPcCandidate(
    name: String, computerClass: Boolean, savedPc: Boolean,
    serviceAdvertised: Boolean, bondedUnknownClass: Boolean,
): Boolean = computerClass || savedPc || serviceAdvertised || bondedUnknownClass ||
    listOf("desktop", "laptop", "starlit", "macbook").any { name.contains(it, ignoreCase = true) }

/** Secure RFCOMM and the bonded-device legacy compatibility attempt both failed. */
internal class RemoteBluetoothCompatibilityException(cause: IOException) :
    IOException("Both Bluetooth RFCOMM modes failed", cause)

internal fun remoteBluetoothConnectionFailureMessage(error: Exception): String = when (error) {
    is RemoteBluetoothCompatibilityException ->
        "Could not connect in secure or legacy Bluetooth mode. Check that Starlit on the PC shows Bluetooth available, the PC is on and in range, and both devices are paired. Older adapters without Windows Bluetooth Classic/RFCOMM support need the Wi-Fi/QR connection instead."
    is SecurityException ->
        "Bluetooth permission was denied. Allow Nearby devices for Starlit, then try again."
    is IllegalArgumentException ->
        "Pair the PC with this phone in Android Bluetooth settings before connecting."
    is IllegalStateException ->
        "Check that Bluetooth is enabled and the PC is still paired in Android Bluetooth settings."
    else ->
        "Bluetooth connection failed. Open Starlit Phone remote on the PC and check that Bluetooth is available, then try again or use Wi-Fi/QR."
}

/** A Bluetooth socket's streams are subjected to the same encrypted, approved handshake as LAN. */
internal class RemoteBluetoothConnection(
    val input: InputStream,
    val output: OutputStream,
    val peer: String,
    val close: () -> Unit,
)

internal interface RemoteBluetoothClient {
    val supported: Boolean
    /** OS-paired PC candidates may be offline; this never establishes proximity or trust. */
    fun pairedPcs(): List<RemoteDesktop> = emptyList()
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
