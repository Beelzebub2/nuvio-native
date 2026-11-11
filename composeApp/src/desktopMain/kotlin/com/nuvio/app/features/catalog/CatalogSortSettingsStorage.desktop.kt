package com.nuvio.app.features.catalog

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CatalogSortSettingsStorage {
    private const val payloadKey = "catalog_sort_settings_payload"
    private val store = DesktopStorage.store("nuvio_catalog_sort_settings")

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
