package com.nuvio.app.features.downloads

import android.content.Context
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import java.io.File
import java.net.URI

internal actual object DownloadStorageManagement {
    actual val supported: Boolean = true
    private const val Preferences = "nuvio_download_storage_budget"
    private fun store() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
    private fun key(profileId: Int) = "profile_${profileId}_quota_bytes"
    private fun hardKey(profileId: Int) = "profile_${profileId}_hard_quota_enabled"

    actual fun availableBytes(): Long? = PlatformLocalAccountDataCleaner.appContext
        ?.filesDir?.usableSpace?.takeIf { it >= 0L }

    internal fun enforcedQuotaBytes(context: Context, profileId: Int): Long? {
        val preferences = context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(hardKey(profileId), false)) return null
        return preferences.getString(key(profileId), null)?.toLongOrNull()?.takeIf { it > 0L }
    }

    actual fun quotaBytes(profileId: Int): Long? =
        store()?.getString(key(profileId), null)?.toLongOrNull()?.takeIf { it > 0L }

    actual fun setQuotaBytes(profileId: Int, limitBytes: Long?) {
        require(limitBytes == null || limitBytes in (1L shl 30)..(2L shl 40))
        checkNotNull(store()) { "Download storage is unavailable" }.edit()
            .putString(key(profileId), limitBytes?.toString())
            .apply { if (limitBytes == null) putBoolean(hardKey(profileId), false) }
            .apply()
    }

    actual fun enforceQuota(profileId: Int): Boolean =
        store()?.getBoolean(hardKey(profileId), false) == true && quotaBytes(profileId) != null

    actual fun setEnforceQuota(profileId: Int, enabled: Boolean) {
        require(!enabled || quotaBytes(profileId) != null) { "Choose a storage target first" }
        checkNotNull(store()) { "Download storage is unavailable" }.edit()
            .putBoolean(hardKey(profileId), enabled).apply()
    }

    /** Only files directly inside Starlit's private downloads directory are eligible. */
    actual fun eligibleCleanupBytes(item: DownloadItem): Long? = runCatching {
        if (!item.isPlayable || item.fileName.isBlank() || item.fileName == "." || item.fileName == ".." ||
            '/' in item.fileName || '\\' in item.fileName) return null
        val context = PlatformLocalAccountDataCleaner.appContext ?: return null
        val transfer = DownloadsPlatformDownloader.managedTransfers().firstOrNull {
            it.item.id == item.id && it.item.fileName == item.fileName &&
                it.profileId == com.nuvio.app.features.profiles.ProfileRepository.activeProfileId &&
                it.item.status == DownloadStatus.Completed && it.item.downloadedBytes == item.downloadedBytes
        } ?: return null
        if (transfer.item.localFileUri != item.localFileUri) return null
        val root = File(context.filesDir, "downloads")
        val file = File(root, item.fileName)
        if (!isPrivateStarlitDownloadFile(root, file)) return null
        val recordedUri = item.localFileUri ?: return null
        if (File(URI(recordedUri)).canonicalFile != file.canonicalFile) return null
        if (file.length() <= 0L || file.length() != item.downloadedBytes) return null
        file.length()
    }.getOrNull()
}

/**
 * Android supports API 24+, while java.nio.file.Files is only available from
 * API 26. Canonical path comparisons work on all supported Android versions
 * and reject symlinks in either the downloads directory itself or the media
 * file, including links that point to another file within the same directory.
 * Canonicalize the parent first: Android's system-provided app-private parent
 * can itself contain aliases, which are not evidence that the downloads
 * directory is a symlink.
 */
internal fun isPrivateStarlitDownloadFile(root: File, file: File): Boolean = runCatching {
    val parent = root.parentFile?.canonicalFile ?: return false
    val expectedRoot = File(parent, root.name)
    val actualRoot = root.canonicalFile
    if (!root.isDirectory || actualRoot != expectedRoot) return false
    val expectedFile = File(actualRoot, file.name)
    file.isFile && file.canonicalFile == expectedFile
}.getOrDefault(false)
