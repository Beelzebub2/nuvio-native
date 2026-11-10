package com.nuvio.app.features.catalog

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CatalogSortSettingsStorage {
    private const val preferencesName = "nuvio_catalog_sort_settings"
    private const val payloadKey = "catalog_sort_settings_payload"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? = preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(payloadKey), payload)?.apply()
    }
}
