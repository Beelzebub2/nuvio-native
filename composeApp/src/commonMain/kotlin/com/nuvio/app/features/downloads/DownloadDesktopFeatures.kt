package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetails

internal expect object DownloadDesktopFeatures {
    fun directory(): String?
    fun remove(item: DownloadItem, idle: Boolean)
}
@Composable internal expect fun DownloadManagementControls()
@Composable internal expect fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int)
