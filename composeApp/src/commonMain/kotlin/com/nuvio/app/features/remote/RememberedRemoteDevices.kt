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
    val vpn: Boolean = false,
    /** Verified, account-scoped desktop identity; only assigned after an authorized session. */
    val pcId: String? = null,
)

/** Transport endpoints remain separate for trust and wake settings, but share a single visible PC. */
internal data class RememberedRemotePc(val key: String, val devices: List<RememberedRemoteDevice>) {
    val primary: RememberedRemoteDevice get() = devices.first()
    val lan: RememberedRemoteDevice? get() = devices.firstOrNull { !it.bluetooth && !it.vpn }
    val bluetooth: RememberedRemoteDevice? get() = devices.firstOrNull { it.bluetooth }
    val vpn: RememberedRemoteDevice? get() = devices.firstOrNull { it.vpn }
    val name: String get() = lan?.name ?: primary.name
}

private val REMOTE_PC_ID_PATTERN = Regex("[0-9a-f]{32}")

/** Never merge PCs on their display names or unverified discovery advertisements. */
internal fun groupRememberedRemotePcs(devices: List<RememberedRemoteDevice>): List<RememberedRemotePc> {
    val groups = linkedMapOf<String, MutableList<RememberedRemoteDevice>>()
    devices.forEach { device ->
        val identity = device.pcId?.takeIf(REMOTE_PC_ID_PATTERN::matches)
            ?: device.trustedId?.takeIf { !device.bluetooth && REMOTE_PC_ID_PATTERN.matches(it) }
        val key = identity?.let { "pc:$it" }
            ?: "endpoint:${device.bluetooth}:${device.vpn}:${device.address}"
        groups.getOrPut(key) { mutableListOf() }.add(device)
    }
    return groups.map { (key, endpoints) -> RememberedRemotePc(key, endpoints) }
}

/** Use a reachable LAN endpoint first, otherwise the separately approved Bluetooth link. */
internal fun connectRememberedRemotePc(
    transport: DesktopRemoteTransport,
    pc: RememberedRemotePc,
    reachableLanAddresses: Set<String> = emptySet(),
) {
    val candidate = pc.lan?.takeIf { it.address in reachableLanAddresses }
        ?: pc.bluetooth?.takeIf { transport.bluetoothSupported }
        ?: pc.lan ?: pc.vpn ?: pc.bluetooth
    candidate?.let { connectRememberedRemoteDevice(transport, it) }
}

/** A saved LAN entry is an explicit reconnect request, not just another discovery scan. */
internal fun connectRememberedRemoteDevice(transport: DesktopRemoteTransport, saved: RememberedRemoteDevice) {
    if (saved.bluetooth) {
        transport.connect(RemoteDesktop(saved.address, 0, saved.name, bluetooth = true))
    } else if (saved.vpn) {
        transport.discoverVpn(saved.address, connect = true)
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
    fun autoReconnectEnabled(account: RemoteAccount): Boolean
    fun setAutoReconnectEnabled(account: RemoteAccount, enabled: Boolean)
}

internal fun rememberRemoteDevice(
    existing: List<RememberedRemoteDevice>,
    name: String,
    address: String,
    nowEpochMs: Long,
    bluetooth: Boolean = false,
    trustedId: String? = null,
    vpn: Boolean = false,
    pcId: String? = null,
): List<RememberedRemoteDevice> {
    val safeAddress = (if (vpn && !bluetooth) normalizePrivateVpnAddress(address)
        else normalizeRememberedRemoteAddress(address, bluetooth)) ?: return existing
    val safeName = name.trim().replace('|', ' ').take(80).ifBlank { "Starlit PC" }
    val safeId = trustedId?.takeIf { !bluetooth && REMOTE_PC_ID_PATTERN.matches(it) }
    val safePcId = pcId?.takeIf(REMOTE_PC_ID_PATTERN::matches) ?: safeId
    val prior = existing.firstOrNull { it.bluetooth == bluetooth && it.vpn == vpn &&
        (safePcId != null && (it.pcId == safePcId || (!it.bluetooth && it.trustedId == safePcId)) ||
            !bluetooth && safeId != null && it.trustedId == safeId) }
        ?: existing.firstOrNull { it.bluetooth == bluetooth && it.vpn == vpn && it.address == safeAddress }
    val latest = RememberedRemoteDevice(
        safeName, safeAddress, nowEpochMs.coerceAtLeast(0L),
        wakeMac = if (bluetooth) null else prior?.wakeMac,
        bluetooth = bluetooth,
        trustedId = if (bluetooth) null else safeId ?: prior?.trustedId,
        vpn = vpn && !bluetooth,
        pcId = safePcId ?: prior?.pcId,
    )
    return (listOf(latest) + existing.filterNot {
        it.bluetooth == bluetooth && it.vpn == vpn && (it.address == safeAddress ||
            (!bluetooth && safeId != null && it.trustedId == safeId) ||
            (safePcId != null && (it.pcId == safePcId || !it.bluetooth && it.trustedId == safePcId)))
    }).take(10)
}

/** A saved private endpoint never authorizes a session; only existing cryptographic trust can. */
internal fun normalizePrivateVpnAddress(value: String): String? {
    val parts = value.trim().split('.')
    if (parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' }
        }) {
        val bytes = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return when {
            bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
                bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 100 && bytes[1] in 64..127 ->
                    bytes.joinToString(".")
            else -> null
        }
    }
    // Hostnames must resolve to an allowed private IP before a socket is opened.
    val host = value.trim().lowercase()
    val labels = host.split('.')
    if (host.length !in 4..253 || labels.size < 2 || labels.last().length < 2 ||
        labels.last().any { it !in 'a'..'z' } || labels.any {
            it.length !in 1..63 || it.first() == '-' || it.last() == '-' ||
                it.any { ch -> ch !in 'a'..'z' && ch !in '0'..'9' && ch != '-' }
        }) return null
    return host
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
