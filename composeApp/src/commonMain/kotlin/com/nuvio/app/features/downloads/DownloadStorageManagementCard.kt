package com.nuvio.app.features.downloads

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
    val lastWatchedByDownloadId = remember(items, progressState.entries) {
        lastWatchedEpochMsByDownloadedEpisodeId(
            items, progressState.entries, kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
    }
    var target by remember(profileId) { mutableStateOf(DownloadStorageManagement.quotaBytes(profileId)) }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = 0.04f))
            .border(1.dp, tokens.colors.borderSubtle, tokens.shapes.compactCard)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Storage, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(Res.string.downloads_storage_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    stringResource(Res.string.downloads_storage_recorded, formatDownloadBytes(usage)),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                available?.let {
                    Text(
                        stringResource(Res.string.downloads_storage_available, formatDownloadBytes(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        // With a target set, a bar shows how much of it the downloads use; it turns red past the target.
        target?.takeIf { it > 0L }?.let { limit ->
            LinearProgressIndicator(
                progress = { (usage.toDouble() / limit).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (overTarget) tokens.colors.danger else tokens.colors.accent,
                trackColor = tokens.colors.textPrimary.copy(alpha = 0.08f),
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
                NuvioCompactActionButton(
                    label = stringResource(Res.string.downloads_storage_target_button, targetLabel(target)),
                    icon = Icons.Rounded.DataUsage,
                    trailingIcon = Icons.Rounded.ExpandMore,
                    size = NuvioActionSize.Small,
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
            NuvioCompactActionButton(
                label = stringResource(Res.string.downloads_storage_review),
                icon = Icons.Rounded.CleaningServices,
                size = NuvioActionSize.Small,
                onClick = {
                    watchedOnly = false
                    selectedIds = emptySet()
                    showPreview = true
                },
            )
            NuvioCompactActionButton(
                label = stringResource(Res.string.downloads_storage_remove_watched),
                icon = Icons.Rounded.DoneAll,
                size = NuvioActionSize.Small,
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
        DialogHint(stringResource(Res.string.downloads_storage_target_note))
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
