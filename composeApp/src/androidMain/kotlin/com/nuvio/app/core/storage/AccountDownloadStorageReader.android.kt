package com.nuvio.app.core.storage

import android.content.Context

internal actual object AccountDownloadStorageReader {
    actual fun loadSelectedFolder(): String? = null
    actual fun isUsableFolder(path: String): Boolean = false

    actual fun loadPayload(profileId: Int): String? =
        PlatformLocalAccountDataCleaner.appContext
            ?.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE)
            ?.getString(ProfileScopedKey.of("downloads_payload", profileId), null)
}
