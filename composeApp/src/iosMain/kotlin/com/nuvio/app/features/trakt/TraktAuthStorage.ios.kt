package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.IosCredentialStore
import platform.Foundation.NSUserDefaults

internal actual object TraktAuthStorage {
    private const val payloadKey = "trakt_auth_payload"

    actual fun loadPayload(profileId: Int): String? {
        val key = ProfileScopedKey.of(payloadKey, profileId)
        IosCredentialStore.read(key)?.let { return it }
        val legacy = NSUserDefaults.standardUserDefaults.stringForKey(key) ?: return null
        savePayload(profileId, legacy)
        return legacy
    }

    actual fun savePayload(profileId: Int, payload: String) {
        val key = ProfileScopedKey.of(payloadKey, profileId)
        IosCredentialStore.put(key, payload)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }

    actual fun removeProfile(profileId: Int) {
        val key = ProfileScopedKey.of(payloadKey, profileId)
        IosCredentialStore.remove(key)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }
}
