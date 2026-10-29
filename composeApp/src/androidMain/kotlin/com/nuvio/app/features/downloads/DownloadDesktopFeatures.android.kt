package com.nuvio.app.features.downloads
import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetails
internal actual object DownloadDesktopFeatures {
    actual fun directory(): String? = null
    actual fun remove(item: DownloadItem, idle: Boolean) {}
    actual fun parallelDownloads(): Int = Int.MAX_VALUE
}
@Composable internal actual fun DownloadManagementControls() {}
@Composable internal actual fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int) {}
