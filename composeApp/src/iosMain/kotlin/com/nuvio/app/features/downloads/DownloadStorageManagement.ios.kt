package com.nuvio.app.features.downloads

internal actual object DownloadStorageManagement {
    actual val supported: Boolean = false
    actual fun availableBytes(): Long? = null
    actual fun quotaBytes(profileId: Int): Long? = null
    actual fun setQuotaBytes(profileId: Int, limitBytes: Long?) {}
    actual fun enforceQuota(profileId: Int): Boolean = false
    actual fun setEnforceQuota(profileId: Int, enabled: Boolean) {}
    actual fun eligibleCleanupBytes(item: DownloadItem): Long? = null
}
