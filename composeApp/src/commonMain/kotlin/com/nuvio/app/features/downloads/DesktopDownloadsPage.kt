package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Widest the desktop Downloads content grows; wider windows centre it. */
private val DesktopDownloadsMaxWidth = 1280.dp

/** Below this width rows drop their artwork so the titles and controls keep room. */
private val CompactRowWidth = 600.dp

/**
 * The desktop layout of Downloads: an overview with the save folder, the transfer queue, a poster
 * grid of everything ready to watch, and a page per series. Mobile keeps the list in DownloadsScreen.
 */
internal fun LazyListScope.desktopDownloadsContent(
    uiState: DownloadsUiState,
    selectedShowId: String?,
    canManage: Boolean,
    visibleIds: Set<String>,
    selection: Set<String>,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
    onToggleSelection: (Set<String>) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val selecting = selection.isNotEmpty()
    if (selectedShowId == null) {
        item(key = "overview") { PageWidth { DownloadManagementControls() } }
    } else {
        val episodes = uiState.completedItems.filter { it.isEpisode && it.parentMetaId == selectedShowId }.sortedForSeriesDownloads()
        item(key = "show-banner") { PageWidth { ShowBanner(episodes, onPlay = onOpenDownload) } }
    }
    if (canManage && visibleIds.isNotEmpty()) {
        item(key = "selection") {
            PageWidth {
                SelectionBar(
                    allSelected = selection.containsAll(visibleIds),
                    selectedCount = selection.size,
                    onSelectAll = onSelectAll,
                    onClear = onClearSelection,
                    onRemove = onRemoveSelected,
                )
            }
        }
    }
    if (selectedShowId == null) {
        rootSections(uiState, selection, selecting, onToggleSelection, onOpenDownload, onOpenShow, onDelete)
    } else {
        showSections(uiState, selectedShowId, selection, onToggleSelection, onOpenDownload, onDelete)
    }
}

private fun LazyListScope.rootSections(
    uiState: DownloadsUiState,
    selection: Set<String>,
    selecting: Boolean,
    onToggleSelection: (Set<String>) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val active = uiState.activeItems
    val queued = active.filter { it.isQueued }.map { it.id }
    if (active.isNotEmpty()) {
        item(key = "queue-header") {
            PageWidth {
                SectionHeader(
                    title = "Queue",
                    detail = listOfNotNull(
                        active.count { it.status == DownloadStatus.Downloading && !it.isQueued }.takeIf { it > 0 }?.let { "$it downloading" },
                        queued.size.takeIf { it > 0 }?.let { "$it waiting" },
                        active.count { it.status == DownloadStatus.Paused }.takeIf { it > 0 }?.let { "$it paused" },
                        active.count { it.status == DownloadStatus.Failed }.takeIf { it > 0 }?.let { "$it failed" },
                    ).joinToString("  ·  "),
                )
            }
        }
        items(active, key = { it.id }) { item ->
            PageWidth {
                QueueRow(
                    item = item,
                    queuePosition = queued.indexOf(item.id).takeIf { it >= 0 }?.plus(1),
                    selected = item.id in selection,
                    onToggle = { onToggleSelection(setOf(item.id)) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
    }

    val shows = uiState.completedItems.filter(DownloadItem::isEpisode).groupBy { it.parentMetaId }
        .values.map { it.sortedForSeriesDownloads() }.sortedBy { it.first().title.lowercase() }
    val movies = uiState.completedItems.filterNot(DownloadItem::isEpisode).sortedBy { it.title.lowercase() }
    if (shows.isNotEmpty()) {
        item(key = "shows-header") {
            PageWidth { SectionHeader(stringResource(Res.string.downloads_section_shows), libraryDetail(shows.flatten(), titles = shows.size)) }
        }
        item(key = "shows-grid") {
            PageWidth {
                PosterGrid {
                    shows.forEach { episodes ->
                        val first = episodes.first()
                        val ids = episodes.map { it.id }.toSet()
                        PosterCard(
                            title = first.title,
                            detail = "${episodeCountLabel(episodes.size)}  ·  ${formatDownloadBytes(episodes.sumOf { it.fileSize })}",
                            art = first.poster ?: first.background ?: first.episodeThumbnail,
                            selection = when {
                                selection.containsAll(ids) -> ToggleableState.On
                                ids.any { it in selection } -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            selectionLabel = stringResource(Res.string.downloads_select_item, first.title),
                            selecting = selecting,
                            hoverIcon = Icons.Rounded.ChevronRight,
                            onClick = { onOpenShow(first.parentMetaId, first.title) },
                            onToggle = { onToggleSelection(ids) },
                        )
                    }
                }
            }
        }
    }
    if (movies.isNotEmpty()) {
        item(key = "movies-header") {
            PageWidth { SectionHeader(stringResource(Res.string.downloads_section_movies), libraryDetail(movies, titles = movies.size)) }
        }
        item(key = "movies-grid") {
            PageWidth {
                PosterGrid {
                    movies.forEach { movie ->
                        PosterCard(
                            title = movie.title,
                            detail = formatDownloadBytes(movie.fileSize),
                            art = movie.poster ?: movie.background,
                            selection = if (movie.id in selection) ToggleableState.On else ToggleableState.Off,
                            selectionLabel = stringResource(Res.string.downloads_select_item, movie.title),
                            selecting = selecting,
                            hoverIcon = Icons.Rounded.PlayArrow,
                            onClick = { if (movie.isPlayable) onOpenDownload(movie) },
                            onToggle = { onToggleSelection(setOf(movie.id)) },
                            onDelete = { onDelete(movie.id) },
                        )
                    }
                }
            }
        }
    }
    if (uiState.items.isEmpty()) {
        item(key = "empty") {
            PageWidth {
                EmptyState(
                    title = stringResource(Res.string.downloads_empty_title),
                    message = "Open a series or movie and choose Download episodes to keep it here for watching without a connection.",
                )
            }
        }
    }
}

private fun LazyListScope.showSections(
    uiState: DownloadsUiState,
    showId: String,
    selection: Set<String>,
    onToggleSelection: (Set<String>) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    val seasons = uiState.completedItems.filter { it.isEpisode && it.parentMetaId == showId }
        .sortedForSeriesDownloads()
        .groupBy { it.seasonNumber ?: 0 }
        .toSortedMap(compareBy { if (it == 0) Int.MAX_VALUE else it })
    if (seasons.isEmpty()) {
        item(key = "empty") { PageWidth { EmptyState(stringResource(Res.string.downloads_empty_episodes), message = null) } }
        return
    }
    seasons.forEach { (season, episodes) ->
        item(key = "season-$season") {
            PageWidth {
                SectionHeader(
                    title = if (season == 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, season),
                    detail = "${episodeCountLabel(episodes.size)}  ·  ${formatDownloadBytes(episodes.sumOf { it.fileSize })}",
                )
            }
        }
        items(episodes, key = { it.id }) { episode ->
            PageWidth {
                EpisodeRow(
                    item = episode,
                    selected = episode.id in selection,
                    onToggle = { onToggleSelection(setOf(episode.id)) },
                    onPlay = { onOpenDownload(episode) },
                    onDelete = { onDelete(episode.id) },
                )
            }
        }
    }
}

@Composable
private fun PageWidth(content: @Composable () -> Unit) {
    // The page has no Surface of its own, so give text and icons the theme's colour here.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.nuvio.colors.textPrimary) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = DesktopDownloadsMaxWidth).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String?, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(allSelected: Boolean, selectedCount: Int, onSelectAll: () -> Unit, onClear: () -> Unit, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val count: @Composable () -> Unit = {
        if (selectedCount > 0) {
            Text(
                text = "$selectedCount selected",
                modifier = Modifier.padding(start = 10.dp, end = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    val selectAll: @Composable () -> Unit = {
        TextButton(onClick = onSelectAll, enabled = !allSelected) { Text(stringResource(Res.string.downloads_select_all)) }
    }
    val clear: @Composable () -> Unit = {
        TextButton(onClick = onClear, enabled = selectedCount > 0) { Text(stringResource(Res.string.downloads_clear_selection)) }
    }
    val remove: @Composable () -> Unit = {
        TextButton(
            onClick = onRemove,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.downloads_remove_selected, selectedCount))
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(if (selectedCount > 0) tokens.colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 4.dp),
    ) {
        if (maxWidth < 600.dp) {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) { count(); selectAll(); clear(); remove() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                count(); selectAll(); clear()
                Spacer(Modifier.weight(1f))
                remove()
            }
        }
    }
}

@Composable
private fun QueueRow(item: DownloadItem, queuePosition: Int?, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val failed = item.status == DownloadStatus.Failed
    val selectionLabel = stringResource(Res.string.downloads_select_item, item.rowTitle())
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < CompactRowWidth
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = if (selected) 0.08f else 0.035f))
            .padding(start = 4.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = selectionLabel },
            colors = downloadsCheckboxColors(),
        )
        if (!compact) Thumbnail(item.episodeThumbnail ?: item.background ?: item.poster, Modifier.size(width = 128.dp, height = 72.dp)) {
            if (failed) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = tokens.colors.warning, modifier = Modifier.size(26.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.episodeLine()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Queued tasks and paused ones of unknown size have no progress to show; the status line covers them.
            val sizeKnown = item.totalBytes != null && item.totalBytes > 0L
            if (!failed && !item.isQueued && (sizeKnown || item.status == DownloadStatus.Downloading)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DownloadProgressBar(item, Modifier.weight(1f))
                    if (item.totalBytes != null && item.totalBytes > 0L) {
                        Text(
                            "${(item.progressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textSecondary,
                            modifier = Modifier.widthIn(min = 34.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Text(
                text = queueStatusLine(item, queuePosition),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) tokens.colors.warning else tokens.colors.textMuted,
                maxLines = if (failed) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (item.status) {
            DownloadStatus.Downloading -> IconButton(onClick = { DownloadsRepository.pauseDownload(item.id) }) {
                Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.compose_action_pause))
            }
            DownloadStatus.Paused -> IconButton(onClick = { DownloadsRepository.resumeDownload(item.id) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_resume))
            }
            DownloadStatus.Failed -> TextButton(onClick = { DownloadsRepository.retryDownload(item.id) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.action_retry))
            }
            DownloadStatus.Completed -> Unit
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.action_delete), tint = tokens.colors.textMuted)
        }
    }
    }
}

@Composable
private fun DownloadProgressBar(item: DownloadItem, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val track = tokens.colors.textPrimary.copy(alpha = 0.10f)
    val known = item.totalBytes != null && item.totalBytes > 0L
    val color = if (item.status == DownloadStatus.Paused) tokens.colors.textMuted else tokens.colors.accent
    if (!known && item.status == DownloadStatus.Downloading && !item.isQueued) {
        LinearProgressIndicator(modifier = modifier.height(5.dp).clip(CircleShape), color = color, trackColor = track)
    } else {
        Box(modifier.height(5.dp).clip(CircleShape).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (known) item.progressFraction else 0f).clip(CircleShape).background(color))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterGrid(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
}

@Composable
private fun PosterCard(
    title: String,
    detail: String,
    art: String?,
    selection: ToggleableState,
    selectionLabel: String,
    selecting: Boolean,
    hoverIcon: ImageVector,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val checked = selection != ToggleableState.Off
    Column(Modifier.width(156.dp).hoverable(interaction), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
                .then(if (checked) Modifier.border(2.dp, tokens.colors.accent, RoundedCornerShape(12.dp)) else Modifier)
                .clickable(onClick = onClick),
        ) {
            if (art != null) {
                NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    text = title.take(1).uppercase(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.displaySmall,
                    color = tokens.colors.textMuted,
                )
            }
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                        Icon(hoverIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                    }
                }
            }
            // Always in the tree so it stays reachable; shown on hover or while picking items.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .alpha(if (hovered || selecting || checked) 1f else 0f)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                TriStateCheckbox(
                    state = selection,
                    onClick = onToggle,
                    modifier = Modifier.semantics { contentDescription = selectionLabel },
                    colors = downloadsCheckboxColors(uncheckedOnArt = true),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.action_delete),
                        tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ShowBanner(episodes: List<DownloadItem>, onPlay: (DownloadItem) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val first = episodes.firstOrNull() ?: return
    val seasons = episodes.map { it.seasonNumber ?: 0 }.distinct().size
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.05f)),
    ) {
        (first.background ?: first.episodeThumbnail)?.let { art ->
            NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to tokens.colors.background.copy(alpha = 0.96f),
                    0.55f to tokens.colors.background.copy(alpha = 0.72f),
                    1f to tokens.colors.background.copy(alpha = 0.15f),
                ),
            ),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 112.dp, height = 168.dp).clip(RoundedCornerShape(10.dp)).background(tokens.colors.textPrimary.copy(alpha = 0.08f))) {
                (first.poster ?: first.background)?.let { art ->
                    NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(first.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOf(
                        episodeCountLabel(episodes.size),
                        "$seasons ${if (seasons == 1) "season" else "seasons"}",
                        formatDownloadBytes(episodes.sumOf { it.fileSize }),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Text("Plays from disk, even without a connection.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                Spacer(Modifier.height(6.dp))
                episodes.firstOrNull { it.isPlayable }?.let { next ->
                    DownloadsPillButton(
                        text = "Play ${next.episodeCode() ?: ""}".trim(),
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { onPlay(next) },
                        emphasized = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(item: DownloadItem, selected: Boolean, onToggle: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selectionLabel = stringResource(Res.string.downloads_select_item, item.rowTitle())
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < CompactRowWidth
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(
                when {
                    selected -> tokens.colors.textPrimary.copy(alpha = 0.08f)
                    hovered -> tokens.colors.overlayHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(enabled = item.isPlayable, onClick = onPlay)
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = selectionLabel },
            colors = downloadsCheckboxColors(),
        )
        if (!compact) Thumbnail(item.episodeThumbnail ?: item.background ?: item.poster, Modifier.size(width = 144.dp, height = 81.dp)) {
            if (hovered && item.isPlayable) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.rowTitle(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(item.episodeCode(), formatDownloadBytes(item.fileSize)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        IconButton(onClick = onPlay, enabled = item.isPlayable) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_play))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.action_delete), tint = tokens.colors.textMuted)
        }
    }
    }
}

@Composable
private fun Thumbnail(art: String?, modifier: Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    val tokens = MaterialTheme.nuvio
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(tokens.colors.textPrimary.copy(alpha = 0.06f))) {
        if (art != null) {
            NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.DownloadForOffline, null, tint = tokens.colors.textMuted, modifier = Modifier.align(Alignment.Center).size(24.dp))
        }
        overlay()
    }
}

@Composable
private fun EmptyState(title: String, message: String?) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DownloadForOffline, null, tint = tokens.colors.textMuted, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        message?.let {
            Text(it, modifier = Modifier.widthIn(max = 440.dp), style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}

/** A rounded action button for the desktop Downloads page; [emphasized] fills it with the accent colour. */
@Composable
internal fun DownloadsPillButton(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true, emphasized: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val container = if (emphasized) tokens.colors.accent else tokens.colors.textPrimary.copy(alpha = 0.08f)
    val content = if (emphasized) tokens.colors.onAccent else tokens.colors.textPrimary
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = container.alpha * 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp, top = 9.dp, bottom = 9.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.45f)
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

@Composable
private fun downloadsCheckboxColors(uncheckedOnArt: Boolean = false) = MaterialTheme.nuvio.let { tokens ->
    CheckboxDefaults.colors(
        checkedColor = tokens.colors.accent,
        checkmarkColor = tokens.colors.onAccent,
        uncheckedColor = if (uncheckedOnArt) Color.White.copy(alpha = 0.85f) else tokens.colors.textMuted,
    )
}

private val DownloadItem.isQueued: Boolean get() = status == DownloadStatus.Downloading && phase == "Queued"

/** Bytes this download takes on disk now: the whole file once finished, the partial file before. */
internal val DownloadItem.fileSize: Long
    get() = if (status == DownloadStatus.Completed) totalBytes ?: downloadedBytes else downloadedBytes

private fun DownloadItem.rowTitle(): String =
    if (isEpisode) episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Episode ${episodeNumber ?: ""}".trim() else title

private fun DownloadItem.episodeCode(): String? =
    if (seasonNumber != null && episodeNumber != null) "S$seasonNumber E$episodeNumber" else null

private fun DownloadItem.episodeLine(): String? {
    val code = episodeCode() ?: return null
    return listOfNotNull(code, episodeTitle?.trim()?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
}

private fun episodeCountLabel(count: Int) = if (count == 1) "1 episode" else "$count episodes"

private fun libraryDetail(items: List<DownloadItem>, titles: Int): String =
    listOf(if (titles == 1) "1 title" else "$titles titles", formatDownloadBytes(items.sumOf { it.fileSize })).joinToString("  ·  ")

internal fun queueStatusLine(item: DownloadItem, queuePosition: Int?): String {
    val total = item.totalBytes?.takeIf { it > 0L }
    val progress = if (total != null) "${formatDownloadBytes(item.downloadedBytes)} of ${formatDownloadBytes(total)}"
        else formatDownloadBytes(item.downloadedBytes)
    return when (item.status) {
        DownloadStatus.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: "Download failed"
        DownloadStatus.Paused -> "Paused  ·  $progress"
        DownloadStatus.Completed -> "Finished  ·  ${formatDownloadBytes(item.fileSize)}"
        DownloadStatus.Downloading -> when {
            item.isQueued -> if (queuePosition == null || queuePosition <= 1) "Queued  ·  up next" else "Queued  ·  #$queuePosition in line"
            item.phase == "Finding source" || item.phase == "Connecting" -> "${item.phase}…"
            else -> listOfNotNull(
                progress,
                item.bytesPerSecond.takeIf { it > 0 }?.let { "${formatDownloadBytes(it)}/s" },
                downloadTimeLeft(item)?.let { "$it left" },
            ).joinToString("  ·  ")
        }
    }
}

internal fun downloadTimeLeft(item: DownloadItem): String? {
    val total = item.totalBytes?.takeIf { it > 0L } ?: return null
    if (item.bytesPerSecond <= 0L) return null
    val seconds = ((total - item.downloadedBytes).coerceAtLeast(0L) / item.bytesPerSecond)
    return when {
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}
