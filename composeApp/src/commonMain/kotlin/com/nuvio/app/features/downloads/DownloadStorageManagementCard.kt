package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.tabular
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogCheckboxRow
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

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
    val lastWatchedByDownloadId = remember(items, progressState.entries) {
        lastWatchedEpochMsByDownloadedEpisodeId(
            items, progressState.entries, kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
    }
    var target by remember(profileId) { mutableStateOf(DownloadStorageManagement.quotaBytes(profileId)) }
    var hardLimitEnabled by remember(profileId) {
        mutableStateOf(DownloadStorageManagement.enforceQuota(profileId))
    }
    var showTargetMenu by remember(profileId) { mutableStateOf(false) }
    var showPreview by remember(profileId) { mutableStateOf(false) }
    var watchedOnly by remember(profileId) { mutableStateOf(false) }
    var selectedIds by remember(profileId) { mutableStateOf(emptySet<String>()) }
    var notice by remember(profileId) { mutableStateOf<String?>(null) }
    val usage = remember(items) { trackedDownloadBytes(items) }
    val available = remember(items) { DownloadStorageManagement.availableBytes() }
    val tokens = MaterialTheme.nuvio
    val overTarget = target?.let { usage > it } == true
    val lowSpace = available?.let { it in 0L until 2L * Gib } == true
    val noTargetLabel = stringResource(Res.string.downloads_storage_no_target)
    fun targetLabel(bytes: Long?) = bytes?.let { "${it / Gib} GiB" } ?: noTargetLabel

    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvioIconChip(Icons.Rounded.Storage, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.downloads_storage_heading),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    stringResource(Res.string.downloads_storage_recorded, formatDownloadBytes(usage)),
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    color = tokens.colors.textMuted,
                )
                available?.let {
                    Text(
                        stringResource(Res.string.downloads_storage_available, formatDownloadBytes(it)),
                        style = MaterialTheme.typography.bodyMedium.tabular,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        // With a target set, a bar shows how much of it the downloads use; it turns red past the target.
        target?.takeIf { it > 0L }?.let { limit ->
            NuvioProgressBar(
                progress = (usage.toDouble() / limit).toFloat().coerceIn(0f, 1f),
                trackColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover),
                fillColor = if (overTarget) tokens.colors.danger else tokens.colors.accent,
            )
        }
        if (overTarget) DialogHint(stringResource(Res.string.downloads_storage_above_target), isError = true)
        if (lowSpace) {
            DialogHint(
                stringResource(Res.string.downloads_storage_low_space, formatDownloadBytes(available ?: 0L)),
                isError = true,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                NuvioPillButton(
                    text = stringResource(Res.string.downloads_storage_target_button, targetLabel(target)),
                    icon = Icons.Rounded.DataUsage,
                    onClick = { showTargetMenu = true },
                )
                Menu(expanded = showTargetMenu, onDismissRequest = { showTargetMenu = false }) {
                    listOf<Long?>(null).plus(StorageTargets.map { it * Gib }).forEach { bytes ->
                        MenuItem(
                            text = targetLabel(bytes),
                            selected = bytes == target,
                            onClick = {
                                runCatching {
                                    DownloadStorageManagement.setQuotaBytes(profileId, bytes)
                                }.onSuccess {
                                    target = bytes
                                    if (bytes == null) hardLimitEnabled = false
                                    DownloadsRepository.onStoragePolicyChanged()
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
            if (target != null) {
                NuvioPillButton(
                    text = stringResource(if (hardLimitEnabled) {
                        Res.string.downloads_storage_enforce_disable
                    } else {
                        Res.string.downloads_storage_enforce_enable
                    }),
                    icon = Icons.Rounded.DataUsage,
                    onClick = {
                        val enabled = !hardLimitEnabled
                        runCatching {
                            DownloadStorageManagement.setEnforceQuota(profileId, enabled)
                        }.onSuccess {
                            hardLimitEnabled = enabled
                            DownloadsRepository.onStoragePolicyChanged()
                            notice = null
                        }.onFailure { error ->
                            notice = error.message
                        }
                    },
                )
            }
            NuvioPillButton(
                text = stringResource(Res.string.downloads_storage_review),
                icon = Icons.Rounded.CleaningServices,
                onClick = {
                    watchedOnly = false
                    selectedIds = emptySet()
                    showPreview = true
                },
            )
            NuvioPillButton(
                text = stringResource(Res.string.downloads_storage_remove_watched),
                icon = Icons.Rounded.DoneAll,
                enabled = watchedEpisodeIds.isNotEmpty(),
                onClick = {
                    // The preview will further restrict this snapshot to verified,
                    // Starlit-owned files before any confirmation can be accepted.
                    watchedOnly = true
                    selectedIds = watchedEpisodeIds
                    showPreview = true
                },
            )
        }
        DialogHint(stringResource(if (hardLimitEnabled) {
            Res.string.downloads_storage_enforce_note_on
        } else {
            Res.string.downloads_storage_enforce_note_off
        }))
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
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
        val oldSuggestions = remember(eligible) {
            suggestedOldDownloadCleanupIds(eligible, kotlin.time.Clock.System.now().toEpochMilliseconds())
        }
        val targetSuggestions = remember(eligible, usage, target) {
            suggestedTargetDownloadCleanupIds(eligible, usage, target)
        }
        val leastRecentlyWatchedSuggestions =
            remember(eligible, usage, target, watchedEpisodeIds, lastWatchedByDownloadId) {
                suggestedLeastRecentlyWatchedCleanupIds(
                    eligible, watchedEpisodeIds, lastWatchedByDownloadId, usage, target,
                )
            }
        val selectedBytes = eligible.filter { it.id in validSelection }
            .fold(0L) { sum, entry ->
                if (entry.bytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + entry.bytes
            }
        DownloadCleanupDialog(
            title = stringResource(if (watchedOnly)
                Res.string.downloads_storage_remove_watched else Res.string.downloads_storage_review),
            watchedOnly = watchedOnly,
            eligible = eligible,
            selection = validSelection,
            onToggle = { id, checked -> selectedIds = if (checked) selectedIds + id else selectedIds - id },
            oldSuggestionsAvailable = !watchedOnly && oldSuggestions.isNotEmpty(),
            targetSuggestionsAvailable = !watchedOnly && targetSuggestions.isNotEmpty(),
            leastRecentlyWatchedAvailable = !watchedOnly && leastRecentlyWatchedSuggestions.isNotEmpty(),
            onSuggestOld = { selectedIds = oldSuggestions },
            onSuggestTarget = { selectedIds = targetSuggestions },
            onSuggestLeastRecentlyWatched = { selectedIds = leastRecentlyWatchedSuggestions },
            onDismiss = { showPreview = false },
            onConfirm = {
                showPreview = false
                notice = "Removing selected verified offline copies…"
                scope.launch {
                val removed = if (watchedOnly) {
                    DownloadsRepository.removeSelectedVerifiedWatchedEpisodes(validSelection, profileId)
                } else {
                    DownloadsRepository.removeSelectedVerifiedDownloads(validSelection, profileId)
                }
                notice = if (removed == validSelection.size) {
                    "Removed ${removed} verified offline ${if (removed == 1) "copy" else "copies"} (${formatDownloadBytes(selectedBytes)} selected)."
                } else {
                    "Removed ${removed} verified offline copies. Changed, unavailable or unowned items were preserved."
                }
                }
            },
        )
    }
}

/** Lets the user pick which verified offline copies to remove before anything is deleted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadCleanupDialog(
    title: String,
    watchedOnly: Boolean,
    eligible: List<DownloadCleanupEntry>,
    selection: Set<String>,
    onToggle: (id: String, checked: Boolean) -> Unit,
    oldSuggestionsAvailable: Boolean = false,
    targetSuggestionsAvailable: Boolean = false,
    leastRecentlyWatchedAvailable: Boolean = false,
    onSuggestOld: () -> Unit = {},
    onSuggestTarget: () -> Unit = {},
    onSuggestLeastRecentlyWatched: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = stringResource(Res.string.downloads_storage_safety_notice),
    ) {
        if (watchedOnly) DialogHint(stringResource(Res.string.downloads_storage_watched_notice))
        if (eligible.isEmpty()) {
            Text(
                stringResource(if (watchedOnly) Res.string.downloads_storage_no_watched_eligible
                    else Res.string.downloads_storage_none_eligible),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        } else {
            if (!watchedOnly) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.downloads_storage_suggest_old),
                        icon = Icons.Rounded.History,
                        size = NuvioActionSize.Small,
                        enabled = oldSuggestionsAvailable,
                        onClick = onSuggestOld,
                    )
                    if (targetSuggestionsAvailable) {
                        NuvioCompactActionButton(
                            label = stringResource(Res.string.downloads_storage_suggest_target),
                            icon = Icons.Rounded.DataUsage,
                            size = NuvioActionSize.Small,
                            onClick = onSuggestTarget,
                        )
                    }
                    if (leastRecentlyWatchedAvailable) {
                        NuvioCompactActionButton(
                            label = stringResource(Res.string.downloads_storage_suggest_lru),
                            icon = Icons.Rounded.History,
                            size = NuvioActionSize.Small,
                            onClick = onSuggestLeastRecentlyWatched,
                        )
                    }
                }
                DialogHint(stringResource(Res.string.downloads_storage_suggest_note))
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                eligible.forEach { entry ->
                    DialogCheckboxRow(
                        text = entry.title,
                        description = formatDownloadBytes(entry.bytes),
                        checked = entry.id in selection,
                        onCheckedChange = { checked -> onToggle(entry.id, checked) },
                    )
                }
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.downloads_storage_remove_selected, selection.size),
                style = DialogButtonStyle.Destructive,
                enabled = selection.isNotEmpty(),
                onClick = onConfirm,
            )
        }
    }
}
