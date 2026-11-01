package com.nuvio.app.features.trakt

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.AndroidCredentialProtection

internal actual object TraktAuthStorage {
    private const val preferencesName = "nuvio_trakt_auth"
    private const val payloadKey = "trakt_auth_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? {
        val key = ProfileScopedKey.of(payloadKey, profileId)
        val stored = preferences?.getString(key, null) ?: return null
        return runCatching {
            AndroidCredentialProtection.reveal(key, stored).also { plaintext ->
                if (!AndroidCredentialProtection.isProtected(stored)) savePayload(profileId, plaintext)
            }
        }.getOrNull()
    }

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey, profileId),
                AndroidCredentialProtection.protect(ProfileScopedKey.of(payloadKey, profileId), payload))
            ?.apply()
    }

    actual fun removeProfile(profileId: Int) {
        preferences
            ?.edit()
            ?.remove(ProfileScopedKey.of(payloadKey, profileId))
            ?.apply()
    }
}
