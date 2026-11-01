package com.nuvio.app.features.downloads
import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
internal actual object DownloadDesktopFeatures {
    actual fun verificationDispatcher(): CoroutineDispatcher = Dispatchers.Default
    actual fun useImportedDirectory(path: String?) {}
    actual fun reconcileKnownDownloads(items: List<DownloadItem>, path: String?, includeDiscovered: Boolean,
        verifyActive: () -> Boolean): List<DownloadItem> = items
    actual fun directory(): String? = null
    actual fun remove(item: DownloadItem, idle: Boolean) {}
    actual fun parallelDownloads(): Int = Int.MAX_VALUE
}
@Composable internal actual fun DownloadManagementControls() {}
@Composable internal actual fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int) {}
