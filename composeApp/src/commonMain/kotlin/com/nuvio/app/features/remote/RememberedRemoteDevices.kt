package com.nuvio.app.features.remote

import kotlinx.serialization.Serializable

/**
 * A remembered PC is only a reconnect hint. It carries no remote trust,
 * pairing secret, authorization token or stored approval.
 */
@Serializable
internal data class RememberedRemoteDevice(
    val name: String,
    val address: String,
    val lastSeenEpochMs: Long,
    val wakeMac: String? = null,
)

internal expect object RememberedRemoteDeviceStore {
    fun read(account: RemoteAccount): List<RememberedRemoteDevice>
    fun write(account: RemoteAccount, devices: List<RememberedRemoteDevice>)
}

internal fun rememberRemoteDevice(
    existing: List<RememberedRemoteDevice>,
    name: String,
    address: String,
    nowEpochMs: Long,
): List<RememberedRemoteDevice> {
    val safeAddress = address.trim()
    if (safeAddress.length !in 7..15 || safeAddress.any { it !in '0'..'9' && it != '.' })
        return existing
    val safeName = name.trim().replace('|', ' ').take(80).ifBlank { "Starlit PC" }
    val latest = RememberedRemoteDevice(
        safeName, safeAddress, nowEpochMs.coerceAtLeast(0L),
        wakeMac = existing.firstOrNull { it.address == safeAddress }?.wakeMac,
    )
    return (listOf(latest) + existing.filterNot { it.address == safeAddress }).take(10)
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
