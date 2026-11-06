package com.nuvio.app.features.remote

import com.nuvio.app.core.storage.DesktopCredentialProtection
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Windows-user-bound DPAPI secrets: saved phones never authorize another account. */
internal object WindowsRemoteBluetoothTrustStore : RemoteBluetoothTrustStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val store get() = DesktopStorage.store("starlit_bluetooth_remote_trust")

    override fun read(account: RemoteAccount, entry: String): RemoteBluetoothTrust? = runCatching {
        val key = remoteBluetoothTrustKey(account, entry)
        // This store has never contained plaintext legacy values; don't migrate any.
        if (store.getString(key)?.startsWith("dpapi:v1:") != true) return null
        val plaintext = DesktopCredentialProtection.read(store, key) ?: return null
        json.decodeFromString<RemoteBluetoothTrust>(plaintext).takeIf(RemoteBluetoothTrust::valid)
    }.getOrNull()

    override fun write(account: RemoteAccount, entry: String, trust: RemoteBluetoothTrust) {
        require(trust.valid())
        check(System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
        DesktopCredentialProtection.write(store, remoteBluetoothTrustKey(account, entry), json.encodeToString(trust))
    }

    override fun delete(account: RemoteAccount, entry: String) {
        DesktopCredentialProtection.write(store, remoteBluetoothTrustKey(account, entry), null)
    }
}
