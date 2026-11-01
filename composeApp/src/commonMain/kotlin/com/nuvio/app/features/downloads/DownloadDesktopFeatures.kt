package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetails

internal expect object DownloadDesktopFeatures {
    fun directory(): String?
    /** Apply the previous anonymous download folder only during an explicit settings import. */
    fun useImportedDirectory(path: String?)
    /** Repair known task paths by proof; discover additional identities only after explicit folder selection. */
    fun reconcileKnownDownloads(items: List<DownloadItem>, path: String?, includeDiscovered: Boolean): List<DownloadItem>
    fun remove(item: DownloadItem, idle: Boolean)
    /** How many queued downloads may transfer at the same time. */
    fun parallelDownloads(): Int
}
@Composable internal expect fun DownloadManagementControls()
@Composable internal expect fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int)
