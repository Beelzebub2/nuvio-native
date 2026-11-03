package com.nuvio.app.features.remote

import android.content.Context
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** App-private and account-scoped: never store credentials, encryption keys or approvals. */
internal actual object RememberedRemoteDeviceStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences("starlit_remembered_remote_pcs", Context.MODE_PRIVATE)

    private fun key(account: RemoteAccount): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${account.server}\u0000${account.userId}".toByteArray(Charsets.UTF_8))
        return "pc_" + digest.joinToString("") { "%02x".format(it) }
    }

    actual fun read(account: RemoteAccount): List<RememberedRemoteDevice> = runCatching {
        val encoded = prefs()?.getString(key(account), null) ?: return emptyList()
        if (encoded.length > 16_384) return emptyList()
        json.decodeFromString<List<RememberedRemoteDevice>>(encoded).asSequence()
            .filter { it.address.length in 7..15 && it.address.all { ch -> ch in '0'..'9' || ch == '.' } }
            .map { it.copy(
                name = it.name.trim().take(80),
                lastSeenEpochMs = it.lastSeenEpochMs.coerceAtLeast(0L),
                wakeMac = it.wakeMac?.let(::normalizedWakeMac),
            ) }
            .distinctBy { it.address }.take(10).toList()
    }.getOrDefault(emptyList())

    actual fun write(account: RemoteAccount, devices: List<RememberedRemoteDevice>) {
        prefs()?.edit()?.putString(key(account), json.encodeToString(devices.take(10)))?.apply()
    }
}
