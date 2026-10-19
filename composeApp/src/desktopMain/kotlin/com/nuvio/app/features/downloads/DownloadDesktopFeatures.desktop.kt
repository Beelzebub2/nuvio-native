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
private suspend fun chooseDownloadFolder(current: String): File? = suspendCancellableCoroutine { continuation ->
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
        Text(if (meta.videos.isEmpty()) "Download for offline" else "Download episodes / seasons")
    }
    if (visible) BatchDownloadDialog(meta, initialSeason, onDismiss = { visible = false })
}

@Composable internal fun BatchDownloadDialog(meta: MetaDetails, initialSeason: Int, onDismiss: () -> Unit) {
    val movie = meta.type.equals("movie", true) && meta.videos.isEmpty()
    val episodes = remember(meta) { if (movie) listOf(MetaVideo(meta.defaultVideoId ?: meta.id, meta.name)) else meta.videos }
    val today = CurrentDateProvider.todayIsoDate()
    val eligible = episodes.filter { it.id.isNotBlank() && it.isReleasedBy(today) }
    var selected by remember(meta.id, initialSeason) { mutableStateOf(eligible.filter { movie || (it.season ?: 0) == initialSeason }.map(::downloadEpisodeKey).toSet()) }
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(DownloadDesktopFeatures.directory().orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf("Any") }
    var transport by remember { mutableStateOf("Any") }
    val providers = remember(meta.id) {
        listOf(null to "All enabled sources") + AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            addon.manifest?.takeIf { manifest -> eligible.any { manifest.supportsStream(meta.type, it.id) } }?.let { addon.streamAddonInstanceId(it.id) to addon.displayTitle }
        } + PluginRepository.getEnabledScrapersForType(meta.type).map { "plugin:${it.id}" to it.name }
    }
    var provider by remember { mutableStateOf<String?>(null) }
    var providerExpanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (movie) "Download for offline" else "Download episodes and seasons") },
        text = {
            Column(Modifier.widthIn(max = 600.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One file at a time. Uses the first supported file matching your choices. Failures appear in Library > Downloads.")
                Text("Save to: $folder", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !picking, onClick = {
                    picking = true
                    scope.launch {
                        try {
                            chooseDownloadFolder(folder)?.let { selectedFolder -> withContext(Dispatchers.IO) { DownloadDesktopFeatures.setDirectory(selectedFolder) } }
                            folder = DownloadDesktopFeatures.directory().orEmpty()
                        } catch (e: Exception) { if (e is CancellationException) throw e; folderError = e.message }
                        finally { picking = false }
                    }
                }) { Text("Choose folder") }
                folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Box {
                    OutlinedButton(onClick = { providerExpanded = true }) { Text("Provider: " + (providers.firstOrNull { it.first == provider }?.second ?: "All enabled sources")) }
                    DropdownMenu(providerExpanded, onDismissRequest = { providerExpanded = false }) {
                        providers.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { provider = id; providerExpanded = false }) }
                    }
                }
                if (episodes.isEmpty()) Text("No episode metadata is available. Open an episode source to download it individually.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DownloadChoice("Quality", quality, listOf("Any", "2160p", "1080p", "720p", "480p")) { quality = it }
                    DownloadChoice("Sources", transport, listOf("Any", "Direct files", "Torrents")) { transport = it }
                }
                Text("Streaming-only sources cannot be saved offline. Torrent packs need an exact episode file.", style = MaterialTheme.typography.bodySmall)
                if (!movie) Row {
                    TextButton(onClick = { selected = eligible.map(::downloadEpisodeKey).toSet() }) { Text("Select all available") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                }
                LazyColumn(Modifier.height(180.dp)) {
                    episodes.groupBy { it.season ?: 0 }.toSortedMap().forEach { (season, videos) ->
                        if (!movie) item(key = "season-$season") {
                            val ids = videos.filter { it in eligible }.map(::downloadEpisodeKey).toSet()
                            Row(Modifier.fillMaxWidth().clickable(enabled = ids.isNotEmpty()) {
                                selected = if (ids.all { it in selected }) selected - ids else selected + ids
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(ids.isNotEmpty() && ids.all { it in selected }, onCheckedChange = { checked -> selected = if (checked) selected + ids else selected - ids }, enabled = ids.isNotEmpty())
                                Text("Season $season - ${ids.size} available")
                            }
                        }
                        items(videos, key = { "${it.id}|${it.season}|${it.episode}" }) { video ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = video in eligible) {
                                selected = if (downloadEpisodeKey(video) in selected) selected - downloadEpisodeKey(video) else selected + downloadEpisodeKey(video)
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(downloadEpisodeKey(video) in selected, onCheckedChange = { checked -> selected = if (checked) selected + downloadEpisodeKey(video) else selected - downloadEpisodeKey(video) }, enabled = video in eligible)
                                Text((video.episode?.let { "E$it - " } ?: "") + video.title + if (video !in eligible) " - Unavailable / unaired" else "")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(enabled = selected.isNotEmpty() && !picking, onClick = {
            val sourceMeta = if (movie) meta.copy(videos = episodes) else meta
            val count = DownloadsRepository.enqueueBatch(sourceMeta, batchDownloadEpisodes(sourceMeta, selected, today), DownloadSourceSelection(provider, quality, transport))
            NuvioToastController.show(if (count == 0) "Already in Downloads. Manage them in Library > Downloads."
                else "${downloadCountLabel(count)} queued. Manage them in Library > Downloads.")
            onDismiss()
        }) { Text("Queue ${downloadCountLabel(selected.size)}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
private fun downloadCountLabel(count: Int) = if (count == 1) "1 download" else "$count downloads"

@Composable private fun DownloadChoice(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}
