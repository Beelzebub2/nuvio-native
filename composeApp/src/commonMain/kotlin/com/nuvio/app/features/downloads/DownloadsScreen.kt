package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val profileId = uiState.profileId
    val canManage = profileId != null && profileId == activeProfileId
    var selectedIds by remember(profileId, selectedShowId) { mutableStateOf(emptySet<String>()) }
    var selectionMode by remember(profileId, selectedShowId) { mutableStateOf(false) }
    var pendingDeletionIds by remember(profileId, selectedShowId) { mutableStateOf<Set<String>?>(null) }
    var bulkDeletion by remember(profileId, selectedShowId) { mutableStateOf(false) }
    val visibleIds = remember(uiState.items, selectedShowId) {
        uiState.items.filter { selectedShowId == null ||
            (it.status == DownloadStatus.Completed && it.isEpisode && it.parentMetaId == selectedShowId) }
            .map { it.id }.toSet()
    }
    val selection = selectedIds.intersect(visibleIds)
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    val toggleSelection: (Set<String>) -> Unit = { ids ->
        if (canManage) selectedIds = if (selection.containsAll(ids)) selection - ids else selection + ids
    }
    val requestSingleDeletion: (String) -> Unit = { id ->
        bulkDeletion = false
        if (canManage) pendingDeletionIds = setOf(id)
    }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    BoxWithConstraints {
    NuvioScreen(
        horizontalPadding = if (isDesktop) desktopPageHorizontalPaddingForWidth(maxWidth.value)
            else MaterialTheme.nuvio.spacing.screenHorizontal,
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    // Desktop shows the folder, with Open folder, in its overview card.
                    if (!isDesktop) IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        if (isDesktop) {
            desktopDownloadsContent(
                uiState = uiState,
                selectedShowId = selectedShowId,
                canManage = canManage,
                visibleIds = visibleIds,
                selection = selection,
                onSelectAll = { selectedIds = visibleIds },
                onClearSelection = { selectedIds = emptySet() },
                onRemoveSelected = {
                    bulkDeletion = true
                    pendingDeletionIds = selection.toSet()
                },
                onToggleSelection = toggleSelection,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDelete = requestSingleDeletion,
            )
            return@NuvioScreen
        }
        if (selectedShowId == null) {
            item { MobileDownloadsOverview(uiState) }
            item { DownloadManagementControls() }
        }
        if (canManage && visibleIds.isNotEmpty()) {
            item {
                DownloadSelectionControls(
                    selecting = selectionMode,
                    allSelected = selection.containsAll(visibleIds),
                    selectedCount = selection.size,
                    onToggleMode = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds = emptySet()
                    },
                    onSelectAll = { selectedIds = visibleIds },
                    onClear = { selectedIds = emptySet() },
                    onRemove = {
                        bulkDeletion = true
                        pendingDeletionIds = selection.toSet()
                    },
                )
            }
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = requestSingleDeletion,
                selecting = selectionMode,
                selectedIds = selection,
                onToggleSelection = toggleSelection,
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = requestSingleDeletion,
                selecting = selectionMode,
                selectedIds = selection,
                onToggleSelection = toggleSelection,
            )
        }
    }
    }

    val deletionIds = pendingDeletionIds
    if (deletionIds != null && canManage && profileId != null) {
        NuvioStatusModal(
            title = if (bulkDeletion) stringResource(Res.string.downloads_remove_selected, deletionIds.size)
                else stringResource(Res.string.action_delete_confirm_title),
            message = if (bulkDeletion) stringResource(Res.string.downloads_remove_selected_message, deletionIds.size)
                else stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownloads(deletionIds, expectedProfileId = profileId)
                selectedIds = selectedIds - deletionIds
                pendingDeletionIds = null
            },
            onDismiss = { pendingDeletionIds = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadSelectionControls(
    selecting: Boolean,
    allSelected: Boolean,
    selectedCount: Int,
    onToggleMode: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            if (selecting) {
                Text(
                    stringResource(Res.string.downloads_mobile_selected_count, selectedCount),
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToggleMode) {
                Text(stringResource(if (selecting) Res.string.downloads_mobile_done else
                    Res.string.downloads_mobile_select))
            }
        }
        if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSelectAll, enabled = !allSelected) {
                Text(stringResource(Res.string.downloads_select_all))
            }
            TextButton(onClick = onClear, enabled = selectedCount > 0) {
                Text(stringResource(Res.string.downloads_clear_selection))
            }
            TextButton(onClick = onRemove, enabled = selectedCount > 0) {
                Text(stringResource(Res.string.downloads_remove_selected, selectedCount), color =
                    if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MobileDownloadsOverview(state: DownloadsUiState) {
    val downloading = state.items.count { it.status == DownloadStatus.Downloading }
    val completed = state.completedItems.size
    val problems = state.items.count { it.status == DownloadStatus.Failed }
    val downloadedSize = state.completedItems.fold(0L) { total, item ->
        total + (item.totalBytes ?: item.downloadedBytes).coerceAtLeast(0L)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DownloadForOffline, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(Res.string.downloads_mobile_library),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.downloads_mobile_storage_used, formatDownloadBytes(downloadedSize)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OverviewMetric(downloading, stringResource(Res.string.downloads_mobile_in_progress))
                OverviewMetric(completed, stringResource(Res.string.downloads_mobile_ready))
                OverviewMetric(problems, stringResource(Res.string.downloads_mobile_attention))
            }
        }
    }
}

@Composable
private fun OverviewMetric(count: Int, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$count", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(title, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (Set<String>) -> Unit,
) {
    val activeItems = uiState.activeItems
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (activeItems.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        items(
            items = activeItems,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                selecting = selecting,
                selected = item.id in selectedIds,
                onToggleSelection = { onToggleSelection(setOf(item.id)) },
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedMovies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = completedMovies,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                selecting = selecting,
                selected = item.id in selectedIds,
                onToggleSelection = { onToggleSelection(setOf(item.id)) },
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = completedShows,
            key = { (item, _) -> item.parentMetaId },
        ) { (item, episodes) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onOpenShow(item.parentMetaId, item.title) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val episodeIds = episodes.map { it.id }.toSet()
                    val selectionLabel = stringResource(Res.string.downloads_select_item, item.title)
                    if (selecting) TriStateCheckbox(
                            state = when {
                                selectedIds.containsAll(episodeIds) -> ToggleableState.On
                                episodeIds.any { it in selectedIds } -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            onClick = { onToggleSelection(episodeIds) },
                            modifier = Modifier.semantics { contentDescription = selectionLabel },
                        )
                    val artwork = item.poster ?: item.episodeThumbnail
                    Box(Modifier.width(52.dp).height(74.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center) {
                        if (artwork != null) NuvioAsyncImage(model = artwork, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(74.dp), contentScale = ContentScale.Crop)
                        else Icon(Icons.Rounded.DownloadForOffline, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(Res.string.downloads_episode_count, episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.DownloadForOffline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(46.dp))
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(Res.string.downloads_mobile_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (Set<String>) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                selecting = selecting,
                selected = item.id in selectedIds,
                onToggleSelection = { onToggleSelection(setOf(item.id)) },
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(item, displayTitle)
    val artwork = item.episodeThumbnail ?: item.poster ?: item.background
    val selectionLabel = stringResource(Res.string.downloads_select_item, displayTitle)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(enabled = selecting || item.isPlayable,
                onClick = if (selecting) onToggleSelection else onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(82.dp).height(112.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center) {
                    if (artwork != null) {
                        NuvioAsyncImage(model = artwork, contentDescription = null,
                            modifier = Modifier.width(82.dp).height(112.dp), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Rounded.DownloadForOffline, contentDescription = null,
                            modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selecting) Checkbox(
                            checked = selected, onCheckedChange = { onToggleSelection() },
                            modifier = Modifier.semantics {
                                contentDescription = selectionLabel
                            },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(displayTitle, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (displaySubtitle.isNotBlank()) Text(displaySubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (item.providerName.isNotBlank()) Text(item.providerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DownloadStateTag(item.status)
                        if (item.torrentHash != null && item.sourceUrl.isBlank()) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("P2P", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }

            if (item.status == DownloadStatus.Downloading ||
                (item.status == DownloadStatus.Paused && item.downloadedBytes > 0L)) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                } else if (item.status == DownloadStatus.Downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
            }
            Text(statusText(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (item.status == DownloadStatus.Failed) 3 else 2,
                overflow = TextOverflow.Ellipsis)
            if (!selecting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    DownloadStatus.Downloading -> TextButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.compose_action_pause))
                    }
                    DownloadStatus.Paused -> TextButton(onClick = onResume) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.action_resume))
                    }
                    DownloadStatus.Failed -> TextButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.action_retry))
                    }
                    DownloadStatus.Completed -> TextButton(onClick = onOpen, enabled = item.isPlayable) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.action_play))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DownloadStateTag(status: DownloadStatus) {
    val container = when (status) {
        DownloadStatus.Downloading -> MaterialTheme.colorScheme.primaryContainer
        DownloadStatus.Paused -> MaterialTheme.colorScheme.surfaceContainerHigh
        DownloadStatus.Completed -> MaterialTheme.colorScheme.tertiaryContainer
        DownloadStatus.Failed -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (status) {
        DownloadStatus.Downloading -> MaterialTheme.colorScheme.onPrimaryContainer
        DownloadStatus.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.Completed -> MaterialTheme.colorScheme.onTertiaryContainer
        DownloadStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }
    val title = when (status) {
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_mobile_in_progress)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_mobile_paused)
        DownloadStatus.Completed -> stringResource(Res.string.downloads_mobile_ready)
        DownloadStatus.Failed -> stringResource(Res.string.downloads_mobile_attention)
    }
    Surface(color = container, shape = CircleShape) {
        Text(title, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}"
    } else {
        formatDownloadBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Downloading -> if (item.phase != null) "${item.phase} - $size" +
            (if (item.bytesPerSecond > 0) " - ${formatDownloadBytes(item.bytesPerSecond)}/s" else "")
            else stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatDownloadBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
