package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val Gib = 1L shl 30
private val StorageTargets = listOf(5L, 10L, 25L, 50L, 100L, 250L, 500L)

/**
 * On-device storage budget and explicit cleanup preview. The target is an
 * informational warning, never permission to remove media in the background.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadStorageManagementCard(items: List<DownloadItem>, profileId: Int) {
    if (!DownloadStorageManagement.supported) return

    val watchedState by remember(profileId) {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val progressState by remember(profileId) {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedEpisodeIds = remember(items, watchedState.watchedKeys, progressState.entries) {
        watchedDownloadedEpisodeIds(items, watchedState.watchedKeys, progressState.entries)
    }
    var target by remember(profileId) { mutableStateOf(DownloadStorageManagement.quotaBytes(profileId)) }
    var showTargetMenu by remember(profileId) { mutableStateOf(false) }
    var showPreview by remember(profileId) { mutableStateOf(false) }
    var watchedOnly by remember(profileId) { mutableStateOf(false) }
    var selectedIds by remember(profileId) { mutableStateOf(emptySet<String>()) }
    var notice by remember(profileId) { mutableStateOf<String?>(null) }
    val usage = remember(items) { trackedDownloadBytes(items) }
    val available = remember(items) { DownloadStorageManagement.availableBytes() }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.downloads_storage_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(Res.string.downloads_storage_recorded, formatDownloadBytes(usage)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            available?.let {
                Text(
                    stringResource(Res.string.downloads_storage_available, formatDownloadBytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (target != null && usage > target!!) {
                Text(
                    stringResource(Res.string.downloads_storage_above_target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { showTargetMenu = true }) {
                        Text(
                            stringResource(
                                Res.string.downloads_storage_target_button,
                                target?.let { "${it / Gib} GiB" }
                                    ?: stringResource(Res.string.downloads_storage_no_target),
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = showTargetMenu,
                        onDismissRequest = { showTargetMenu = false },
                    ) {
                        listOf<Long?>(null).plus(StorageTargets.map { it * Gib }).forEach { bytes ->
                            DropdownMenuItem(
                                text = {
                                    Text(bytes?.let { "${it / Gib} GiB" }
                                        ?: stringResource(Res.string.downloads_storage_no_target))
                                },
                                onClick = {
                                    runCatching {
                                        DownloadStorageManagement.setQuotaBytes(profileId, bytes)
                                    }.onSuccess {
                                        target = bytes
                                        notice = null
                                    }.onFailure { error ->
                                        notice = error.message
                                    }
                                    showTargetMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    watchedOnly = false
                    selectedIds = emptySet()
                    showPreview = true
                }) {
                    Text(stringResource(Res.string.downloads_storage_review))
                }
                TextButton(
                    enabled = watchedEpisodeIds.isNotEmpty(),
                    onClick = {
                        // The preview will further restrict this snapshot to verified,
                        // Starlit-owned files before any confirmation can be accepted.
                        watchedOnly = true
                        selectedIds = watchedEpisodeIds
                        showPreview = true
                    },
                ) {
                    Text(stringResource(Res.string.downloads_storage_remove_watched))
                }
            }
            Text(
                stringResource(Res.string.downloads_storage_target_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showPreview) {
        // Only enumerate and stat verified owned files while the user has
        // explicitly opened the cleanup flow, not on every transfer progress tick.
        val eligible = remember(items, profileId, watchedOnly, watchedEpisodeIds) {
            eligibleDownloadCleanup(
                if (watchedOnly) items.filter { it.id in watchedEpisodeIds } else items,
                DownloadStorageManagement::eligibleCleanupBytes,
            )
        }
        val validSelection = selectedIds.intersect(eligible.mapTo(mutableSetOf()) { it.id })
        val selectedBytes = eligible.filter { it.id in validSelection }
            .fold(0L) { sum, entry ->
                if (entry.bytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + entry.bytes
            }
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text(stringResource(if (watchedOnly)
                Res.string.downloads_storage_remove_watched else Res.string.downloads_storage_review)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(Res.string.downloads_storage_safety_notice),
                        style = MaterialTheme.typography.bodySmall)
                    if (watchedOnly) {
                        Text(stringResource(Res.string.downloads_storage_watched_notice),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (eligible.isEmpty()) {
                        Text(stringResource(if (watchedOnly) Res.string.downloads_storage_no_watched_eligible
                            else Res.string.downloads_storage_none_eligible))
                    } else {
                        eligible.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = entry.id in validSelection,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + entry.id
                                            else selectedIds - entry.id
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatDownloadBytes(entry.bytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validSelection.isNotEmpty(),
                    onClick = {
                        val removed = if (watchedOnly) {
                            DownloadsRepository.removeSelectedVerifiedWatchedEpisodes(validSelection, profileId)
                        } else {
                            DownloadsRepository.removeSelectedVerifiedDownloads(validSelection, profileId)
                        }
                        notice = if (removed == validSelection.size) {
                            "Scheduled removal of ${removed} verified offline ${if (removed == 1) "copy" else "copies"} (${formatDownloadBytes(selectedBytes)} selected)."
                        } else {
                            "Scheduled removal of ${removed} verified offline copies. Changed or unowned items were preserved."
                        }
                        showPreview = false
                    },
                ) {
                    Text(stringResource(Res.string.downloads_storage_remove_selected, validSelection.size))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPreview = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}
