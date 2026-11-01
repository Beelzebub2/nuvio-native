package com.nuvio.app.core.storage

import platform.Foundation.NSUserDefaults

internal actual object AccountDownloadStorageReader {
    actual fun loadSelectedFolder(): String? = null
    actual fun isUsableFolder(path: String): Boolean = false

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("downloads_payload", profileId))
}
