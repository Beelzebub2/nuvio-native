package com.nuvio.app.features.remote

import android.content.Context
import com.nuvio.app.core.storage.AndroidCredentialProtection
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Encrypted with a non-exportable Android Keystore key; never backed by plain preferences. */
internal object AndroidRemoteBluetoothTrustStore : RemoteBluetoothTrustStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences("starlit_bluetooth_remote_trust", Context.MODE_PRIVATE)

    override fun read(account: RemoteAccount, entry: String): RemoteBluetoothTrust? = runCatching {
        val key = remoteBluetoothTrustKey(account, entry)
        val encrypted = prefs()?.getString(key, null) ?: return null
        if (!AndroidCredentialProtection.isProtected(encrypted)) return null
        json.decodeFromString<RemoteBluetoothTrust>(AndroidCredentialProtection.reveal(key, encrypted))
            .takeIf(RemoteBluetoothTrust::valid)
    }.getOrNull()

    override fun write(account: RemoteAccount, entry: String, trust: RemoteBluetoothTrust) {
        require(trust.valid())
        val key = remoteBluetoothTrustKey(account, entry)
        val encrypted = AndroidCredentialProtection.protect(key, json.encodeToString(trust))
        check(prefs()?.edit()?.putString(key, encrypted)?.commit() == true) { "Bluetooth trust could not be saved" }
    }

    override fun delete(account: RemoteAccount, entry: String) {
        val key = remoteBluetoothTrustKey(account, entry)
        check(prefs()?.edit()?.remove(key)?.commit() == true) { "Bluetooth trust could not be forgotten" }
    }
}
