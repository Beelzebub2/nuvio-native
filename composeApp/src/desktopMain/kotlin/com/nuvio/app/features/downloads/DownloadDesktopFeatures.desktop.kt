package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.*
import com.nuvio.app.features.details.*
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.*
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

internal actual object DownloadDesktopFeatures {
    private val store by lazy { DesktopStorage.store("desktop_download_preferences") }
    actual fun directory(): String? = store.getString("folder") ?: legacyDownloadsDirectory.also { it.mkdirs() }.absolutePath
    internal fun setDirectory(folder: File) {
        require(folder.isDirectory && folder.canWrite()) { "Choose an available writable folder" }
        store.putString("folder", folder.canonicalPath)
    }
    actual fun remove(item: DownloadItem, idle: Boolean) { requestDownloadRemoval(item) }

    /** The provider, quality and source type chosen last time, so the dialog opens on them. */
    internal fun lastSelection() = DownloadSourceSelection(
        providerId = store.getString("provider"),
        quality = store.getString("quality") ?: DownloadQuality.Highest,
        transport = store.getString("transport") ?: "Any",
    )
    internal fun saveSelection(selection: DownloadSourceSelection) {
        store.putString("provider", selection.providerId)
        store.putString("quality", selection.quality)
        store.putString("transport", selection.transport)
    }
}

@Composable internal actual fun DownloadManagementControls() {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(DownloadDesktopFeatures.directory().orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Download folder", style = MaterialTheme.typography.titleSmall)
        Text(folder, style = MaterialTheme.typography.bodySmall)
        Text("Changing this folder applies to new downloads. Existing files stay in their original folders.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !picking, onClick = {
                picking = true
                scope.launch {
                    try {
                        val selected = chooseDownloadFolder(folder)
                        if (selected != null) withContext(Dispatchers.IO) { DownloadDesktopFeatures.setDirectory(selected) }
                        folder = DownloadDesktopFeatures.directory().orEmpty()
                    } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                    finally { picking = false }
                }
            }) { Text("Choose folder") }
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) {
                if (!openDownloadDirectory(File(folder))) withContext(Dispatchers.Main) { error = "Download folder is unavailable" }
            } }) { Text("Open folder") }
            TextButton(onClick = DownloadsRepository::pauseActiveDownloads) { Text("Pause all") }
            TextButton(onClick = DownloadsRepository::resumeAll) { Text("Resume all") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
internal suspend fun chooseDownloadFolder(current: String): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val chooser = JFileChooser(current).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose download folder" }
        val selected = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        if (continuation.isActive) continuation.resume(selected)
    }
}

internal fun batchDownloadEpisodes(meta: MetaDetails, selectedIds: Set<String>, today: String): List<MetaVideo> =
    meta.videos.filter { downloadEpisodeKey(it) in selectedIds && it.id.isNotBlank() && it.isReleasedBy(today) }
        .distinctBy { Triple(it.id, it.season, it.episode) }
        .sortedWith(compareBy<MetaVideo> { it.season ?: 0 }.thenBy { it.episode ?: 0 }.thenBy { it.id })

internal fun downloadEpisodeKey(video: MetaVideo): String = "${video.season}|${video.episode}|${video.id}"

@Composable internal actual fun BatchDownloadAction(meta: MetaDetails, initialSeason: Int) {
    var visible by remember(meta.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { visible = true }) {
        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (meta.videos.isEmpty()) "Download for offline" else "Download episodes")
    }
    if (visible) BatchDownloadDialog(meta, initialSeason, onDismiss = { visible = false })
}
