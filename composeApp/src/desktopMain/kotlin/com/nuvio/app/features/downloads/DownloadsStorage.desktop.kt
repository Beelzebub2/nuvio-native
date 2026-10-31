package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private val store = DesktopStorage.store("nuvio_downloads")

    actual fun loadPayload(): String? =
        store.getStringValidated(ProfileScopedKey.of("downloads"), DownloadsCodec::isValid)

    actual fun savePayload(payload: String) {
        require(DownloadsCodec.isValid(payload)) { "Refusing to save malformed downloads metadata" }
        store.putString(ProfileScopedKey.of("downloads"), payload)
    }
}
