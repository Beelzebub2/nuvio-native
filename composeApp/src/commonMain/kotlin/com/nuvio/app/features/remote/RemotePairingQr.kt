package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

private const val REMOTE_QR_PREFIX = "starlit-pc:1:"

/** A discovery hint only. No credentials, commands or pairing approval in this QR. */
internal fun remotePairingQrPayload(address: String): String? =
    address.takeIf(::isPrivateRemoteQrAddress)?.let { REMOTE_QR_PREFIX + it }

internal fun parseRemotePairingQr(payload: String): String? {
    if (payload.length !in 1..64 || !payload.startsWith(REMOTE_QR_PREFIX)) return null
    return payload.removePrefix(REMOTE_QR_PREFIX).takeIf(::isPrivateRemoteQrAddress)
}

private fun isPrivateRemoteQrAddress(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4 || parts.any { part ->
            part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part.startsWith('0'))
        }) return false
    val bytes = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
    return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
        (bytes[0] == 192 && bytes[1] == 168)
}

@Composable
internal expect fun DesktopPairingQr(addresses: List<String>)

/** null on platforms without a native scanner; discovery and manual IP remain available. */
@Composable
internal expect fun rememberRemoteQrScanner(onResult: (String?) -> Unit): (() -> Unit)?
