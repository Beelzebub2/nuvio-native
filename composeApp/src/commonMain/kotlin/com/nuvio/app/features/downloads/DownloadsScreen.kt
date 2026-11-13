package com.nuvio.app.features.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.key
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioSectionHeader
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvioCheckboxColors
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
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
    LaunchedEffect(visibleIds) {
        selectedIds = selectedIds.intersect(visibleIds)
        if (visibleIds.isEmpty()) selectionMode = false
    }
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
            val downloadsTitle = stringResource(Res.string.compose_settings_root_downloads_title)
            val pageTitle = if (selectedShowId == null) {
                downloadsTitle
            } else {
                selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
            }
            val goBack = {
                if (selectedShowId != null) {
                    onBackFromShow?.invoke() ?: run { selectedShowId = null }
                } else {
                    onBack()
                }
            }
            if (isDesktop) {
                // Desktop shows the folder, with Open folder, in its overview card.
                NuvioScreenHeader(title = pageTitle, onBack = goBack)
            } else {
                MobileDownloadsHeader(
                    title = if (selectionMode) {
                        stringResource(Res.string.downloads_mobile_selected_count, selection.size)
                    } else {
                        pageTitle
                    },
                    // While selecting, the page name moves up; a show page keeps "Downloads" above it.
                    eyebrow = when {
                        selectionMode -> pageTitle
                        selectedShowId != null -> downloadsTitle
                        else -> null
                    },
                    selecting = selectionMode,
                    canSelect = canManage && visibleIds.isNotEmpty(),
                    onBack = goBack,
                    // Done leaves selection mode and clears what was ticked.
                    onToggleSelecting = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds = emptySet()
                    },
                    onOpenFolder = {
                        if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                            NuvioToastController.show(openDownloadsDirectoryFailedText)
                        }
                    },
                )
            }
        }

        if (selectedShowId == null && canManage && profileId != null) {
            item { DownloadStorageManagementCard(uiState.items, profileId) }
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
        val startSelection: (Set<String>) -> Unit = { ids ->
            if (canManage) {
                selectionMode = true
                selectedIds = selection + ids
            }
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                selecting = selectionMode,
                selectedIds = selection,
                onToggleSelection = toggleSelection,
                onStartSelection = startSelection,
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                onOpenDownload = onOpenDownload,
                selecting = selectionMode,
                selectedIds = selection,
                onToggleSelection = toggleSelection,
                onStartSelection = startSelection,
            )
        }
        // Room for the floating selection bar, so the last rows stay reachable.
        if (selectionMode) item(key = "downloads-selection-spacer") { Spacer(Modifier.height(64.dp)) }
    }
    if (!isDesktop && selectionMode && canManage) {
        DownloadSelectionBar(
            allSelected = selection.containsAll(visibleIds),
            selectedCount = selection.size,
            onSelectAll = { selectedIds = visibleIds },
            onRemove = {
                bulkDeletion = true
                pendingDeletionIds = selection.toSet()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = MaterialTheme.nuvio.spacing.screenHorizontal)
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        )
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

/** The phone header: back, title (or the selection count) and the Select / Done pill. */
@Composable
private fun MobileDownloadsHeader(
    title: String,
    eyebrow: String?,
    selecting: Boolean,
    canSelect: Boolean,
    onBack: () -> Unit,
    onToggleSelecting: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // With a native navigation bar the title and back button are already on screen.
    val nativeNavigation = LocalUseNativeNavigation.current && !LocalNativeNavigationBarHidden.current
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().background(tokens.colors.background).nuvioConsumePointerEvents())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = if (nativeNavigation) 0.dp else statusBarTop, bottom = 4.dp)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nativeNavigation) {
                Spacer(Modifier.weight(1f))
            } else {
                NuvioSquareIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                    onClick = onBack,
                    size = 36.dp,
                    style = NuvioSquareIconButtonStyle.Surface,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (eyebrow != null) {
                        Text(eyebrow, style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(title, style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!selecting) {
                NuvioSquareIconButton(
                    icon = Icons.Rounded.Folder,
                    contentDescription = stringResource(Res.string.downloads_open_directory),
                    onClick = onOpenFolder,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
            }
            if (canSelect) {
                NuvioPillButton(
                    text = stringResource(if (selecting) Res.string.downloads_mobile_done else Res.string.downloads_mobile_select),
                    onClick = onToggleSelecting,
                    style = if (selecting) NuvioPillButtonStyle.Primary else NuvioPillButtonStyle.Secondary,
                )
            }
        }
    }
}

/** Select all and Remove selected, floating over the list while selecting. */
@Composable
private fun DownloadSelectionBar(
    allSelected: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.thin, tokens.colors.textPrimary.copy(alpha = tokens.opacity.pressed), shape)
            .nuvioConsumePointerEvents()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DialogButton(
            text = stringResource(Res.string.downloads_select_all),
            onClick = onSelectAll,
            modifier = Modifier.weight(1f),
            enabled = !allSelected,
        )
        DialogButton(
            text = stringResource(Res.string.downloads_remove_selected, selectedCount),
            onClick = onRemove,
            modifier = Modifier.weight(1f),
            style = DialogButtonStyle.Destructive,
            enabled = selectedCount > 0,
        )
    }
}

@Composable
private fun MobileDownloadsOverview(state: DownloadsUiState) {
    val tokens = MaterialTheme.nuvio
    val downloading = state.items.count { it.status == DownloadStatus.Downloading }
    val completed = state.completedItems.size
    val problems = state.items.count { it.status == DownloadStatus.Failed }
    val downloadedSize = state.completedItems.fold(0L) { total, item ->
        total + (item.totalBytes ?: item.downloadedBytes).coerceAtLeast(0L)
    }
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NuvioIconChip(Icons.Rounded.DownloadForOffline, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.downloads_mobile_library),
                    style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(stringResource(Res.string.downloads_mobile_storage_used, formatDownloadBytes(downloadedSize)),
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric(downloading, stringResource(Res.string.downloads_mobile_in_progress), Modifier.weight(1f))
            OverviewMetric(completed, stringResource(Res.string.downloads_mobile_ready), Modifier.weight(1f))
            OverviewMetric(problems, stringResource(Res.string.downloads_mobile_attention), Modifier.weight(1f),
                highlight = problems > 0)
        }
    }
}

@Composable
private fun OverviewMetric(count: Int, title: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier
            .fillMaxHeight()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$count", style = MaterialTheme.nuvioTypeScale.titleMd.tabular,
            color = if (highlight) tokens.colors.warning else tokens.colors.textPrimary)
        Text(title, style = MaterialTheme.nuvioTypeScale.labelSm, fontWeight = FontWeight.Normal,
            color = tokens.colors.textMuted, maxLines = 2)
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (Set<String>) -> Unit,
    onStartSelection: (Set<String>) -> Unit,
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
        item(key = "downloads-active") {
            val downloading = activeItems.any { it.status == DownloadStatus.Downloading }
            val paused = activeItems.any { it.status == DownloadStatus.Paused }
            DownloadSection(
                title = stringResource(Res.string.downloads_section_active),
                // Pause wins while anything is running; otherwise offer to resume what is paused.
                actionLabel = when {
                    selecting -> null
                    downloading -> stringResource(Res.string.downloads_mobile_pause_all)
                    paused -> stringResource(Res.string.downloads_mobile_resume_all)
                    else -> null
                },
                onAction = if (downloading) DownloadsRepository::pauseActiveDownloads else DownloadsRepository::resumeAll,
            ) {
                activeItems.forEachIndexed { index, item ->
                    key(item.id) {
                        if (index > 0) NuvioGroupDivider(startInset = WideThumbnailInset)
                        DownloadRow(
                            item = item,
                            wideThumbnail = true,
                            selecting = selecting,
                            selected = item.id in selectedIds,
                            onToggleSelection = { onToggleSelection(setOf(item.id)) },
                            onStartSelection = { onStartSelection(setOf(item.id)) },
                            onOpen = { onOpenDownload(item) },
                        )
                    }
                }
            }
        }
    }

    if (completedMovies.isNotEmpty()) {
        item(key = "downloads-movies") {
            DownloadSection(title = stringResource(Res.string.downloads_section_movies)) {
                completedMovies.forEachIndexed { index, item ->
                    key(item.id) {
                        if (index > 0) NuvioGroupDivider(startInset = PosterInset)
                        DownloadRow(
                            item = item,
                            wideThumbnail = false,
                            selecting = selecting,
                            selected = item.id in selectedIds,
                            onToggleSelection = { onToggleSelection(setOf(item.id)) },
                            onStartSelection = { onStartSelection(setOf(item.id)) },
                            onOpen = { onOpenDownload(item) },
                        )
                    }
                }
            }
        }
    }

    if (completedShows.isNotEmpty()) {
        item(key = "downloads-shows") {
            DownloadSection(title = stringResource(Res.string.downloads_section_shows)) {
                completedShows.forEachIndexed { index, (item, episodes) ->
                    key(item.parentMetaId) {
                        if (index > 0) NuvioGroupDivider(startInset = PosterInset)
                        val episodeIds = episodes.map { it.id }.toSet()
                        DownloadedShowRow(
                            item = item,
                            episodeCount = episodes.size,
                            selecting = selecting,
                            selection = when {
                                selectedIds.containsAll(episodeIds) -> ToggleableState.On
                                episodeIds.any { it in selectedIds } -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            onToggleSelection = { onToggleSelection(episodeIds) },
                            onStartSelection = { onStartSelection(episodeIds) },
                            onOpen = { onOpenShow(item.parentMetaId, item.title) },
                        )
                    }
                }
            }
        }
    }

    if (uiState.items.isEmpty()) {
        item(key = "downloads-empty") {
            NuvioSurfaceCard(outlined = true) {
                NuvioEmptyBlock(
                    icon = Icons.Rounded.DownloadForOffline,
                    title = stringResource(Res.string.downloads_empty_title),
                    message = stringResource(Res.string.downloads_mobile_empty_description),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (Set<String>) -> Unit,
    onStartSelection: (Set<String>) -> Unit,
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
        item(key = "downloads-show-empty") {
            NuvioSurfaceCard(outlined = true) {
                NuvioEmptyBlock(
                    icon = Icons.Rounded.DownloadForOffline,
                    title = stringResource(Res.string.downloads_empty_episodes),
                    tone = NuvioIconChipTone.Neutral,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item(key = "downloads-season-$seasonNumber") {
            DownloadSection(
                title = if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            ) {
                entries.sortedForSeriesDownloads().forEachIndexed { index, item ->
                    key(item.id) {
                        if (index > 0) NuvioGroupDivider(startInset = WideThumbnailInset)
                        DownloadRow(
                            item = item,
                            wideThumbnail = true,
                            selecting = selecting,
                            selected = item.id in selectedIds,
                            onToggleSelection = { onToggleSelection(setOf(item.id)) },
                            onStartSelection = { onStartSelection(setOf(item.id)) },
                            onOpen = { onOpenDownload(item) },
                        )
                    }
                }
            }
        }
    }
}

// Row text starts after the leading artwork: 16 dp padding + artwork + 12 dp gap.
private val WideThumbnailInset = 92.dp
private val PosterInset = 68.dp

/** A section label, an optional inline action, and its rows in one group. */
@Composable
private fun DownloadSection(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionHeader(title = title.uppercase(), actionLabel = actionLabel, onAction = onAction)
        NuvioGroup(content = rows)
    }
}

/** Artwork behind a download row: a 16:9 still for episodes and queue items, a poster otherwise. */
@Composable
private fun DownloadArtwork(model: String?, wide: Boolean) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    Box(
        Modifier
            .size(width = if (wide) 64.dp else 40.dp, height = if (wide) 36.dp else 60.dp)
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DownloadForOffline, contentDescription = null, tint = tokens.colors.textMuted,
            modifier = Modifier.size(18.dp))
        if (model != null) {
            NuvioAsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    wideThumbnail: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onStartSelection: () -> Unit,
    onOpen: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(item, displayTitle)
    val artwork = if (wideThumbnail) item.episodeThumbnail ?: item.background ?: item.poster
        else item.poster ?: item.episodeThumbnail ?: item.background
    val selectionLabel = stringResource(Res.string.downloads_select_item, displayTitle)
    val failed = item.status == DownloadStatus.Failed
    val paused = item.status == DownloadStatus.Paused
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = true,
                onClick = when {
                    selecting -> onToggleSelection
                    item.isPlayable -> onOpen
                    else -> ({})
                },
                // A long press starts selecting with this row ticked.
                onLongClick = if (selecting) null else onStartSelection,
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadArtwork(artwork, wide = wideThumbnail)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(displayTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (displaySubtitle.isNotBlank()) {
                Text(displaySubtitle, style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                statusText(item),
                style = MaterialTheme.nuvioTypeScale.bodySm.tabular,
                color = if (failed) tokens.colors.danger else tokens.colors.textMuted,
                maxLines = if (failed) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val showProgress = item.status == DownloadStatus.Downloading || (paused && item.downloadedBytes > 0L)
            if (showProgress && item.totalBytes != null && item.totalBytes > 0L) {
                NuvioProgressBar(
                    progress = item.progressFraction,
                    modifier = Modifier.padding(top = 2.dp),
                    trackColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.pressed),
                    fillColor = if (paused) tokens.colors.textMuted else tokens.colors.accent,
                )
            }
            if (item.torrentHash != null && item.sourceUrl.isBlank()) {
                NuvioTag("P2P", style = NuvioTagStyle.Outline, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(40.dp).semantics { contentDescription = selectionLabel },
                colors = nuvioCheckboxColors(),
            )
        } else {
            when (item.status) {
                DownloadStatus.Downloading -> NuvioSquareIconButton(
                    icon = Icons.Rounded.Pause,
                    contentDescription = stringResource(Res.string.compose_action_pause),
                    onClick = { DownloadsRepository.pauseDownload(item.id) },
                    iconSize = 18.dp,
                )
                DownloadStatus.Paused -> NuvioSquareIconButton(
                    icon = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.action_resume),
                    onClick = { DownloadsRepository.resumeDownload(item.id) },
                    iconSize = 18.dp,
                )
                DownloadStatus.Failed -> NuvioSquareIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.action_retry),
                    onClick = { DownloadsRepository.retryDownload(item.id) },
                    iconSize = 18.dp,
                )
                DownloadStatus.Completed -> NuvioSquareIconButton(
                    icon = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.action_play),
                    onClick = onOpen,
                    style = NuvioSquareIconButtonStyle.AccentSoft,
                    enabled = item.isPlayable,
                    iconSize = 18.dp,
                )
            }
        }
    }
}

/** A show with finished episodes. It opens the show's episode list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedShowRow(
    item: DownloadItem,
    episodeCount: Int,
    selecting: Boolean,
    selection: ToggleableState,
    onToggleSelection: () -> Unit,
    onStartSelection: () -> Unit,
    onOpen: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val selectionLabel = stringResource(Res.string.downloads_select_item, item.title)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = if (selecting) onToggleSelection else onOpen,
                onLongClick = if (selecting) null else onStartSelection,
            )
            .padding(start = 16.dp, end = if (selecting) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadArtwork(item.poster ?: item.episodeThumbnail, wide = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.downloads_episode_count, episodeCount),
                style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted)
        }
        if (selecting) {
            TriStateCheckbox(
                state = selection,
                onClick = onToggleSelection,
                modifier = Modifier.size(40.dp).semantics { contentDescription = selectionLabel },
                colors = nuvioCheckboxColors(),
            )
        } else {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp))
        }
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
