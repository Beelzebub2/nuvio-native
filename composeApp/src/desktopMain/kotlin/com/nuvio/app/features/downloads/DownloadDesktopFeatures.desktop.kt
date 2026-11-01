package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.nuvio
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
import com.nuvio.app.core.ui.DesktopFileDialogs

/** Choices for how many episodes download at once; a few keep the connection busy when one source slows. */
internal val ParallelDownloadChoices = 1..5
internal const val DefaultParallelDownloads = 3

internal actual object DownloadDesktopFeatures {
    private val store by lazy { DesktopStorage.store("desktop_download_preferences") }
    actual fun directory(): String? = store.getString("folder") ?: legacyDownloadsDirectory.also { it.mkdirs() }.absolutePath
    internal fun setDirectory(folder: File) {
        require(folder.isDirectory && folder.canWrite()) { "Choose an available writable folder" }
        store.putString("folder", folder.canonicalPath)
    }
    actual fun useImportedDirectory(path: String?) {
        // Importing an anonymous list must not change an account's own chosen destination.
        if (!store.getString("folder").isNullOrBlank()) return
        val folder = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (folder.isDirectory && folder.canWrite()) setDirectory(folder)
    }
    actual fun reconcileKnownDownloads(items: List<DownloadItem>, path: String?, includeDiscovered: Boolean): List<DownloadItem> {
        val verified = items.map { item ->
            // Only the item's saved task directory and verified owner/content proof
            // authorize a completed file. A legacy filename alone is insufficient.
            val playable = if (item.status == DownloadStatus.Completed)
                runCatching { recoverFinalDownload(item) }.getOrNull() else null
            if (playable != null) {
                recordDesktopDownloadIdentity(item)
                item.copy(
                    localFileUri = playable.toURI().toString(),
                    downloadedBytes = playable.length(), totalBytes = playable.length(),
                    phase = null, bytesPerSecond = 0L, errorMessage = null,
                )
            } else {
                // The account may already have another preferred download directory.
                // Recover guest-owned files in their saved location before trying the
                // newly selected folder; never change the account's destination.
                recoverKnownDesktopDownload(item, item.destinationDirectory)
                    ?: recoverKnownDesktopDownload(item, path)
                    ?: item
            }
        }
        if (!includeDiscovered) return verified
        val byKey = linkedMapOf<String, DownloadItem>()
        val usedIds = mutableSetOf<String>()
        verified.forEach { item ->
            byKey.putIfAbsent(item.logicalContentKey, item)
            usedIds.add(item.id)
        }
        discoverDesktopDownloads(path).forEach { found ->
            val previous = byKey[found.logicalContentKey]
            if (previous == null) {
                if (usedIds.add(found.id)) byKey[found.logicalContentKey] = found
            } else if (previous.status != DownloadStatus.Completed ||
                runCatching { recoverFinalDownload(previous) }.getOrNull() == null) {
                // The recovered item has a proof; never discard another live, playable record.
                if (previous.id == found.id || found.id !in usedIds) {
                    usedIds.remove(previous.id)
                    usedIds.add(found.id)
                    byKey[found.logicalContentKey] = found
                }
            }
        }
        return byKey.values.toList()
    }
    actual fun remove(item: DownloadItem, idle: Boolean) { requestDownloadRemoval(item) }

    actual fun parallelDownloads(): Int = store.getInt("parallel")?.takeIf { it in ParallelDownloadChoices } ?: DefaultParallelDownloads
    internal fun setParallelDownloads(count: Int) {
        require(count in ParallelDownloadChoices)
        store.putInt("parallel", count)
        DownloadsRepository.onParallelLimitChanged()
    }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal actual fun DownloadManagementControls() {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val state by DownloadsRepository.uiState.collectAsState()
    var folder by remember { mutableStateOf(DownloadDesktopFeatures.directory().orEmpty()) }
    var parallel by remember { mutableStateOf(DownloadDesktopFeatures.parallelDownloads()) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveredNotice by remember { mutableStateOf<String?>(null) }
    val items = state.items
    val transferring = items.count { it.status == DownloadStatus.Downloading }
    val paused = items.count { it.status == DownloadStatus.Paused }
    val failed = items.count { it.status == DownloadStatus.Failed }
    val ready = items.count { it.status == DownloadStatus.Completed }
    val queueButtons: @Composable () -> Unit = {
        DownloadsPillButton("Pause all", Icons.Rounded.Pause, DownloadsRepository::pauseActiveDownloads, enabled = transferring > 0)
        DownloadsPillButton("Resume all", Icons.Rounded.PlayArrow, DownloadsRepository::resumeAll, enabled = paused > 0)
    }
    val folderButtons: @Composable () -> Unit = {
        DownloadsPillButton(if (picking) "Choosing…" else "Change folder", null, enabled = !picking, onClick = {
            picking = true
            scope.launch {
                try {
                    val selected = chooseDownloadFolder(folder)
                    if (selected != null) {
                        val recovered = withContext(Dispatchers.IO) {
                            DownloadDesktopFeatures.setDirectory(selected)
                            DownloadsRepository.recoverFromSelectedFolder()
                        }
                        recoveredNotice = if (recovered > 0)
                            "Recovered $recovered verified offline ${if (recovered == 1) "download" else "downloads"}."
                        else "No additional verified Starlit downloads were found in this folder."
                    }
                    folder = DownloadDesktopFeatures.directory().orEmpty()
                    error = null
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                finally { picking = false }
            }
        })
        DownloadsPillButton("Open folder", null, onClick = { scope.launch(Dispatchers.IO) {
            if (!openDownloadDirectory(File(folder))) withContext(Dispatchers.Main) { error = "Download folder is unavailable" }
        } })
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = 0.04f))
            .border(1.dp, tokens.colors.borderSubtle, tokens.shapes.compactCard)
            .padding(20.dp),
    ) {
    // Narrow windows put the buttons under their text instead of beside it.
    val narrow = maxWidth < 680.dp
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DownloadForOffline, null, tint = tokens.colors.accent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = if (items.isEmpty()) "Nothing downloaded yet"
                        else "$ready ready to watch  ·  ${formatDownloadBytes(items.sumOf { it.fileSize })} on disk",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(
                        transferring.takeIf { it > 0 }?.let { "$it in the queue" },
                        paused.takeIf { it > 0 }?.let { "$it paused" },
                        failed.takeIf { it > 0 }?.let { "$it failed" },
                    ).joinToString("  ·  ").ifEmpty {
                        if (items.isEmpty()) "Queue whole seasons from any series page and watch them here offline." else "Nothing in the queue"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            if (!narrow) queueButtons()
        }
        if (narrow) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { queueButtons() }
        HorizontalDivider(color = tokens.colors.borderSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(File(folder).name.ifBlank { folder }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    File(folder).parent?.let {
                        Text(compactFolderPath(it, maxLength = 48), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("New downloads are saved here. Earlier files stay where they were saved.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
            if (!narrow) folderButtons()
        }
        if (narrow) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { folderButtons() }
        HorizontalDivider(color = tokens.colors.borderSubtle)
        val picker: @Composable () -> Unit = {
            ParallelDownloadsPicker(parallel) { count ->
                DownloadDesktopFeatures.setParallelDownloads(count)
                parallel = count
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Speed, null, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Downloads at once", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Running a few together keeps your connection busy when one source slows down.",
                    style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
            if (!narrow) picker()
        }
        if (narrow) picker()
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger) }
        recoveredNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
    }
    }
}
/** A segmented 1–5 choice; lowering it lets transfers already running finish. */
@Composable
private fun ParallelDownloadsPicker(value: Int, onSelect: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.08f)).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ParallelDownloadChoices.forEach { count ->
            val selected = count == value
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(if (selected) tokens.colors.accent else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(count) })
                    .semantics { contentDescription = "$count at once" },
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", style = MaterialTheme.typography.labelLarge,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary)
            }
        }
    }
}

internal suspend fun chooseDownloadFolder(current: String): File? =
    DesktopFileDialogs.folder("Choose download folder", current)

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
