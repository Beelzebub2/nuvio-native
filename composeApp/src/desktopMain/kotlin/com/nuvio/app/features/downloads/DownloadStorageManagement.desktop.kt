package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption

internal actual object DownloadStorageManagement {
    actual val supported: Boolean = true
    private val store by lazy { DesktopStorage.store("download_storage_budget") }
    private fun key(profileId: Int) = ProfileScopedKey.of("quota_bytes", profileId)
    private fun hardKey(profileId: Int) = ProfileScopedKey.of("hard_quota_enabled", profileId)

    actual fun availableBytes(): Long? = runCatching {
        val folder = File(DownloadDesktopFeatures.directory() ?: return null)
        folder.takeIf { it.isDirectory }?.usableSpace?.takeIf { it > 0L }
    }.getOrNull()

    actual fun quotaBytes(profileId: Int): Long? =
        store.getString(key(profileId))?.toLongOrNull()?.takeIf { it > 0L }

    actual fun setQuotaBytes(profileId: Int, limitBytes: Long?) {
        require(limitBytes == null || limitBytes in (1L shl 30)..(2L shl 40))
        store.putString(key(profileId), limitBytes?.toString() ?: "")
        if (limitBytes == null) store.putString(hardKey(profileId), "false")
    }

    actual fun enforceQuota(profileId: Int): Boolean =
        store.getString(hardKey(profileId)) == "true" && quotaBytes(profileId) != null

    actual fun setEnforceQuota(profileId: Int, enabled: Boolean) {
        require(!enabled || quotaBytes(profileId) != null) { "Choose a storage target first" }
        store.putString(hardKey(profileId), enabled.toString())
    }

    /** Never claim legacy/unowned files or folders just because a queue record points to them. */
    actual fun eligibleCleanupBytes(item: DownloadItem): Long? = runCatching {
        if (!item.isPlayable) return null
        val savedRoot = item.destinationDirectory ?: return null
        if (!safeDownloadName(item.id) || !safeDownloadName(item.fileName)) return null
        val root = File(savedRoot)
        val file = downloadPath(item)
        val directory = file.parentFile
        val rootPath = root.toPath()
        val dirPath = directory.toPath()
        val filePath = file.toPath()
        val marker = File(directory, ".starlit-owner")
        val proof = downloadProofFile(file)

        if (!root.isDirectory || Files.isSymbolicLink(rootPath) || Files.isSymbolicLink(dirPath) ||
            Files.isSymbolicLink(filePath) || Files.isSymbolicLink(marker.toPath()) ||
            Files.isSymbolicLink(proof.toPath())) return null
        if (dirPath.toRealPath() != rootPath.toRealPath().resolve(item.id)) return null
        if (!marker.isFile || marker.length() !in 1..100 || marker.readText() != item.id) return null
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(proof.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (file.length() <= 0L || item.downloadedBytes != file.length()) return null
        val recordedUri = item.localFileUri ?: return null
        if (File(URI(recordedUri)).toPath().toAbsolutePath().normalize() !=
            filePath.toAbsolutePath().normalize()) return null
        file.length()
    }.getOrNull()
}
