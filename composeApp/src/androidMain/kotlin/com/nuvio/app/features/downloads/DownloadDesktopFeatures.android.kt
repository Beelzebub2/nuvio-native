package com.nuvio.app.features.downloads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_mobile_parallel
import nuvio.composeapp.generated.resources.downloads_mobile_pause_all
import nuvio.composeapp.generated.resources.downloads_mobile_resume_all
import org.jetbrains.compose.resources.stringResource

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun DownloadManagementControls() {
    val state by DownloadsRepository.uiState.collectAsState()
    var parallel by remember { mutableIntStateOf(DownloadDesktopFeatures.parallelDownloads()) }
    var showParallel by remember { mutableStateOf(false) }
    val active = state.items.count { it.status == DownloadStatus.Downloading }
    val paused = state.items.count { it.status == DownloadStatus.Paused }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active > 0) TextButton(onClick = DownloadsRepository::pauseActiveDownloads) {
                Text(stringResource(Res.string.downloads_mobile_pause_all))
            }
            if (paused > 0) TextButton(onClick = DownloadsRepository::resumeAll) {
                Text(stringResource(Res.string.downloads_mobile_resume_all))
            }
            OutlinedButton(onClick = { showParallel = !showParallel }) {
                Text(stringResource(Res.string.downloads_mobile_parallel, parallel))
            }
        }
        if (showParallel) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { count ->
                FilterChip(selected = parallel == count, onClick = {
                    DownloadDesktopFeatures.setParallelDownloads(count)
                    parallel = count
                    showParallel = false
                }, label = { Text("$count") })
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
