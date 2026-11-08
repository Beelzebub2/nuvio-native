package com.nuvio.app.features.remote

import android.content.Context
import com.nuvio.app.core.storage.AndroidCredentialProtection
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Account-scoped LAN keys encrypted using the Android Keystore. */
internal object AndroidRemoteLanTrustStore : RemoteLanTrustStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences("starlit_lan_remote_trust", Context.MODE_PRIVATE)

    override fun read(account: RemoteAccount, entry: String): RemoteLanTrust? = runCatching {
        val key = remoteLanTrustKey(account, entry)
        val saved = prefs()?.getString(key, null) ?: return null
        if (!AndroidCredentialProtection.isProtected(saved)) return null
        json.decodeFromString<RemoteLanTrust>(AndroidCredentialProtection.reveal(key, saved))
            .takeIf(RemoteLanTrust::valid)
    }.getOrNull()

    override fun write(account: RemoteAccount, entry: String, trust: RemoteLanTrust) {
        require(trust.valid())
        val key = remoteLanTrustKey(account, entry)
        val ciphertext = AndroidCredentialProtection.protect(key, json.encodeToString(trust))
        check(prefs()?.edit()?.putString(key, ciphertext)?.commit() == true) {
            "LAN trust could not be saved"
        }
    }
    override fun delete(account: RemoteAccount, entry: String) {
        val key = remoteLanTrustKey(account, entry)
        check(prefs()?.edit()?.remove(key)?.commit() == true) { "LAN trust could not be forgotten" }
    }
}

