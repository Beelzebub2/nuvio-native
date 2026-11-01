package com.nuvio.app.features.downloads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual object DownloadDesktopFeatures {
    actual fun verificationDispatcher(): CoroutineDispatcher = Dispatchers.IO
    private const val preferenceName = "nuvio_android_download_preferences"
    private const val parallelKey = "parallel_downloads"
    actual fun useImportedDirectory(path: String?) = Unit // Android owns its private media directory.
    actual fun reconcileKnownDownloads(
        items: List<DownloadItem>, path: String?, includeDiscovered: Boolean,
        verifyActive: () -> Boolean,
    ): List<DownloadItem> = items // Android validates finished files via DownloadsPlatformDownloader.
    actual fun directory(): String? = null
    actual fun remove(item: DownloadItem, idle: Boolean) = Unit
    actual fun parallelDownloads(): Int {
        val context = PlatformLocalAccountDataCleaner.appContext ?: return 3
        return context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .getInt(ProfileScopedKey.of(parallelKey), 3).coerceIn(1, 5)
    }
    fun setParallelDownloads(count: Int) {
        require(count in 1..5)
        PlatformLocalAccountDataCleaner.appContext
            ?.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(ProfileScopedKey.of(parallelKey), count)
            ?.apply()
        DownloadsRepository.onParallelLimitChanged()
    }
}

@Composable
internal actual fun DownloadManagementControls() {
    val state by DownloadsRepository.uiState.collectAsState()
    var parallel by remember { mutableIntStateOf(DownloadDesktopFeatures.parallelDownloads()) }
    val active = state.items.count { it.status == DownloadStatus.Downloading }
    val paused = state.items.count { it.status == DownloadStatus.Paused }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = DownloadsRepository::pauseActiveDownloads, enabled = active > 0) { Text("Pause all") }
            TextButton(onClick = DownloadsRepository::resumeAll, enabled = paused > 0) { Text("Resume all") }
        }
        Text("Downloads at once", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { count ->
                FilterChip(
                    selected = parallel == count,
                    onClick = {
                        DownloadDesktopFeatures.setParallelDownloads(count)
                        parallel = count
                    },
                    label = { Text("$count") },
                )
            }
        }
    }
}

@Composable
internal actual fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int) {
    var visible by remember(meta.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { visible = true }) {
        Icon(Icons.Rounded.Download, null)
        Text(if (meta.videos.isEmpty()) " Download for offline" else " Download episodes")
    }
    if (visible) AndroidBatchDownloadDialog(meta, initialSeason, onDismiss = { visible = false })
}
