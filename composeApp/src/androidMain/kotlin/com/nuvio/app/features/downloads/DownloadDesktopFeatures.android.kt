package com.nuvio.app.features.downloads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.surfaceInset
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_mobile_parallel
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
    actual suspend fun removeVerified(item: DownloadItem, expectedProfileId: Int): Boolean = false
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

/** How many downloads run at once. Pause all and Resume all sit on the Active section header. */
@Composable
internal actual fun DownloadManagementControls() {
    var parallel by remember { mutableIntStateOf(DownloadDesktopFeatures.parallelDownloads()) }
    val tokens = MaterialTheme.nuvio
    NuvioGroup {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.downloads_mobile_parallel, parallel),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary,
            )
            NuvioSegmentedControl(
                segments = (1..5).map { count -> NuvioSegment(count, "$count") },
                selected = parallel,
                onSelect = { count ->
                    DownloadDesktopFeatures.setParallelDownloads(count)
                    parallel = count
                },
                size = NuvioSegmentedSize.Dense,
                containerColor = tokens.colors.surfaceInset,
            )
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
