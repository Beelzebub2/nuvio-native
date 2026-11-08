package com.nuvio.app.features.remote

import kotlinx.serialization.Serializable

/**
 * A remembered PC is a reconnect hint. Its optional random desktop ID is
 * untrusted metadata; it carries no approval, secret or authorization token.
 */
@Serializable
internal data class RememberedRemoteDevice(
    val name: String,
    val address: String,
    val lastSeenEpochMs: Long,
    val wakeMac: String? = null,
    val bluetooth: Boolean = false,
    val trustedId: String? = null,
)

/** A saved LAN entry is an explicit reconnect request, not just another discovery scan. */
internal fun connectRememberedRemoteDevice(transport: DesktopRemoteTransport, saved: RememberedRemoteDevice) {
    if (saved.bluetooth) {
        transport.connect(RemoteDesktop(saved.address, 0, saved.name, bluetooth = true))
    } else {
        transport.discover(saved.address, connect = true)
    }
}

/** Reconnect automatically only when Bluetooth is the most recently used transport. */
internal fun preferredBluetoothReconnect(
    remembered: List<RememberedRemoteDevice>,
    bluetoothSupported: Boolean,
): RememberedRemoteDevice? = remembered.firstOrNull()?.takeIf { it.bluetooth && bluetoothSupported }

internal expect object RememberedRemoteDeviceStore {
    fun read(account: RemoteAccount): List<RememberedRemoteDevice>
    fun write(account: RemoteAccount, devices: List<RememberedRemoteDevice>)
}

internal fun rememberRemoteDevice(
    existing: List<RememberedRemoteDevice>,
    name: String,
    address: String,
    nowEpochMs: Long,
    bluetooth: Boolean = false,
    trustedId: String? = null,
): List<RememberedRemoteDevice> {
    val safeAddress = normalizeRememberedRemoteAddress(address, bluetooth) ?: return existing
    val safeName = name.trim().replace('|', ' ').take(80).ifBlank { "Starlit PC" }
    val safeId = trustedId?.takeIf { !bluetooth && it.matches(Regex("[0-9a-f]{32}")) }
    val prior = existing.firstOrNull { !it.bluetooth && safeId != null && it.trustedId == safeId }
        ?: existing.firstOrNull { it.bluetooth == bluetooth && it.address == safeAddress }
    val latest = RememberedRemoteDevice(
        safeName, safeAddress, nowEpochMs.coerceAtLeast(0L),
        wakeMac = if (bluetooth) null else prior?.wakeMac,
        bluetooth = bluetooth,
        trustedId = if (bluetooth) null else safeId,
    )
    return (listOf(latest) + existing.filterNot {
        it.bluetooth == bluetooth && (it.address == safeAddress ||
            (!bluetooth && safeId != null && it.trustedId == safeId))
    }).take(10)
}

/** Validate saved discovery hints without accepting DNS names, URLs or arbitrary routes. */
internal fun normalizeRememberedRemoteAddress(value: String, bluetooth: Boolean): String? {
    val address = value.trim()
    if (bluetooth) {
        val parts = address.split(':')
        if (parts.size != 6 || parts.any { it.length != 2 ||
                it.any { ch -> ch !in '0'..'9' && ch !in 'a'..'f' && ch !in 'A'..'F' } }) return null
        if (parts.all { it == "00" }) return null
        return address.uppercase()
    }
    val parts = address.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
            it.any { ch -> ch !in '0'..'9' } || (it.toIntOrNull() ?: 256) > 255 }) return null
    return address
}

/** Reject multicast, broadcast and invalid MAC addresses before any packet is sent. */
internal fun normalizedWakeMac(value: String): String? {
    val parts = value.trim().replace('-', ':').split(':')
    if (parts.size != 6 || parts.any { it.length != 2 ||
            it.any { char -> char !in '0'..'9' && char !in 'a'..'f' && char !in 'A'..'F' } }) return null
    val bytes = parts.map { it.toInt(16) }
    if ((bytes.first() and 1) != 0 || bytes.all { it == 0 } || bytes.all { it == 255 }) return null
    return parts.joinToString(":") { it.uppercase() }
}
