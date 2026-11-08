package com.nuvio.app.features.remote

import com.nuvio.app.core.storage.DesktopCredentialProtection
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Windows DPAPI protects LAN proof keys from other OS users. */
internal object WindowsRemoteLanTrustStore : RemoteLanTrustStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val store get() = DesktopStorage.store("starlit_lan_remote_trust")

    override fun read(account: RemoteAccount, entry: String): RemoteLanTrust? = runCatching {
        val key = remoteLanTrustKey(account, entry)
        if (store.getString(key)?.startsWith("dpapi:v1:") != true) return null
        val plaintext = DesktopCredentialProtection.read(store, key) ?: return null
        json.decodeFromString<RemoteLanTrust>(plaintext).takeIf(RemoteLanTrust::valid)
    }.getOrNull()
    override fun write(account: RemoteAccount, entry: String, trust: RemoteLanTrust) {
        require(trust.valid())
        check(System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
        DesktopCredentialProtection.write(store, remoteLanTrustKey(account, entry), json.encodeToString(trust))
    }
    override fun delete(account: RemoteAccount, entry: String) {
        DesktopCredentialProtection.write(store, remoteLanTrustKey(account, entry), null)
    }
}

