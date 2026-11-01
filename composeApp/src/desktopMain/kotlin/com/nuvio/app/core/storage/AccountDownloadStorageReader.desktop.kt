package com.nuvio.app.core.storage

import com.nuvio.app.features.downloads.DownloadsCodec
import java.io.File

internal actual object AccountDownloadStorageReader {
    private val store = DesktopStorage.store("nuvio_downloads")

    actual fun loadPayload(profileId: Int): String? =
        store.getStringValidated(ProfileScopedKey.of("downloads", profileId), DownloadsCodec::isValid)

    actual fun loadSelectedFolder(): String? =
        DesktopStorage.store("desktop_download_preferences").getString("folder")

    actual fun isUsableFolder(path: String): Boolean =
        File(path).let { it.isDirectory && it.canWrite() }
}
