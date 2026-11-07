package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSectionLabel
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSwitchColors
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Tall enough that every card in a row has the same height, whatever its title length.
private val PosterWidth = 96.dp
private val PosterHeight = 144.dp
private val ControlHeight = 34.dp
private val CardMinWidth = 500.dp
private val ScoreGold = Color(0xFFF5C518)

/** MAL's list colours, brightened slightly so they read on Starlit's dark surfaces. */
internal fun malStatusColor(status: MalStatus?): Color = when (status) {
    MalStatus.WATCHING -> Color(0xFF3DBE5A)
    MalStatus.COMPLETED -> Color(0xFF5B8DEF)
    MalStatus.ON_HOLD -> Color(0xFFF2C14E)
    MalStatus.DROPPED -> Color(0xFFE5534B)
    MalStatus.PLAN_TO_WATCH, null -> Color(0xFF9AA0AA)
}

/**
 * Optional desktop navigation destination. Uses the user's own MAL connection; edits are shown
 * immediately and saved to MAL in the background.
 */
@Composable
internal actual fun MyAnimeListTabContent(topChromePadding: Dp?) {
    val connection = MyAnimeListRepository.state.collectAsState().value
    val profile = ProfileRepository.state.collectAsState().value
    LaunchedEffect(profile.activeProfile) { MyAnimeListRepository.ensureLoaded() }

    val colors = MaterialTheme.nuvio.colors
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var statusKey by rememberSaveable { mutableStateOf("") }
    var sortKey by rememberSaveable { mutableStateOf(MalListSort.UPDATED.name) }
    val selectedStatus = MalStatus.entries.firstOrNull { it.name == statusKey }
    val sort = MalListSort.entries.firstOrNull { it.name == sortKey } ?: MalListSort.UPDATED
    var refreshId by remember { mutableIntStateOf(0) }
    var statsVersion by remember { mutableIntStateOf(0) }
    var setup by remember { mutableStateOf(false) }

    val editor = remember(connection.profileId, connection.revision) {
        MalListEditor(
            scope = scope,
            send = { id, patch -> MyAnimeListRepository.update(id, patch) },
            delete = { id -> MyAnimeListRepository.remove(id) },
            onFailure = { NuvioToastController.show(it) },
            onCommitted = { before, after -> if (before.status != after.status) statsVersion++ },
        )
    }
    DisposableEffect(editor) {
        onDispose {
            // Don't lose an edit made just before leaving the tab.
            editor.flush { save -> MyAnimeListRepository.launchDetached { withContext(Dispatchers.Main) { save() } } }
        }
    }

    val listKey = Triple(selectedStatus, sort, refreshId)
    var requestedOffset by remember(editor, listKey) { mutableIntStateOf(0) }
    var nextOffset by remember(editor, listKey) { mutableStateOf<Int?>(null) }
    var loading by remember(editor, listKey) { mutableStateOf(false) }
    var error by remember(editor, listKey) { mutableStateOf<String?>(null) }
    var retryId by remember(editor, listKey) { mutableIntStateOf(0) }
    var shown by remember(editor) { mutableStateOf<Pair<MalStatus?, MalListSort>?>(null) }
    var stats by remember(editor) { mutableStateOf<MalAnimeStatistics?>(null) }
    var editingId by remember(editor) { mutableStateOf<Int?>(null) }
    var removingId by remember(editor) { mutableStateOf<Int?>(null) }

    LaunchedEffect(editor, listKey, requestedOffset, retryId, connection.connected, connection.busy) {
        if (!connection.connected || connection.busy) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = MyAnimeListRepository.animeList(selectedStatus, requestedOffset, sort)
            editor.show(page.entries, append = requestedOffset > 0)
            nextOffset = page.nextOffset
            shown = selectedStatus to sort
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load your MyAnimeList entries."
        }
        loading = false
    }
    LaunchedEffect(editor, connection.connected, connection.busy, refreshId, statsVersion) {
        if (!connection.connected || connection.busy) return@LaunchedEffect
        // Let a burst of status changes settle before re-reading the totals.
        if (statsVersion > 0) delay(1_000)
        try {
            stats = MyAnimeListRepository.animeStatistics()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The totals are decoration; the list itself reports its own errors.
        }
    }

    val grid = rememberLazyGridState()
    val nearEnd by remember(grid) {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, nextOffset, loading, error) {
        val next = nextOffset
        if (nearEnd && next != null && !loading && error == null) requestedOffset = next
    }
    LaunchedEffect(selectedStatus, sort) { grid.scrollToItem(0) }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val inset = desktopPageHorizontalPaddingForWidth(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            NuvioScreenHeader(
                title = "MyAnimeList",
                modifier = Modifier.padding(horizontal = inset),
                topPadding = topChromePadding,
                actions = {
                    if (connection.connected) {
                        NuvioCompactActionButton(
                            label = "Refresh",
                            icon = Icons.Rounded.Refresh,
                            onClick = { refreshId++ },
                            enabled = !loading && !editor.hasPendingWork,
                            tone = NuvioCompactActionTone.Secondary,
                            size = NuvioActionSize.Small,
                        )
                        Spacer(Modifier.width(6.dp))
                        NuvioCompactActionButton(
                            label = "Open on MAL",
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(malProfileUrl(connection.username)) },
                            tone = NuvioCompactActionTone.Ghost,
                            size = NuvioActionSize.Small,
                        )
                    }
                },
            )
            if (connection.connected) {
                MalProfileSummary(
                    username = connection.username.takeIf { it.isNotBlank() && it != "Connected" },
                    stats = stats,
                    modifier = Modifier.padding(horizontal = inset).padding(top = 2.dp, bottom = 16.dp),
                )
                MalListToolbar(
                    selected = selectedStatus,
                    onSelect = { statusKey = it?.name.orEmpty() },
                    stats = stats,
                    sort = sort,
                    onSort = { sortKey = it.name },
                    modifier = Modifier.padding(horizontal = inset),
                )
                Spacer(Modifier.height(16.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val awaitingFirstPage = shown != (selectedStatus to sort)
                when {
                    connection.busy && !connection.connecting -> MalSkeletonGrid(inset)
                    !connection.connected -> MalConnectPrompt(
                        connection = connection,
                        onConnect = { setup = true },
                        modifier = Modifier.padding(horizontal = inset, vertical = 8.dp),
                    )
                    awaitingFirstPage && error != null -> MalMessage(
                        title = "Couldn't load your list",
                        body = error.orEmpty(),
                        action = "Try again",
                        onAction = { retryId++ },
                        modifier = Modifier.padding(horizontal = inset),
                    )
                    awaitingFirstPage || (editor.entries.isEmpty() && loading) -> MalSkeletonGrid(inset)
                    editor.entries.isEmpty() -> MalMessage(
                        title = selectedStatus?.let { "Nothing in ${it.label}" } ?: "Your list is empty",
                        body = selectedStatus?.let { "Titles you mark as ${it.label} on MyAnimeList show up here." }
                            ?: "Add anime on MyAnimeList, or from a title's page in Starlit, and it will show up here.",
                        modifier = Modifier.padding(horizontal = inset),
                    )
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(CardMinWidth),
                            state = grid,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = inset, end = inset, bottom = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(editor.entries, key = { "mal_${it.anime.id}" }) { entry ->
                                val id = entry.anime.id
                                MalEntryCard(
                                    entry = entry,
                                    saving = editor.isSaving(id),
                                    onChange = { change -> editor.edit(id, change) },
                                    onEdit = { editingId = id },
                                    onOpen = { uriHandler.openUri("https://myanimelist.net/anime/$id") },
                                    onRemove = { removingId = id },
                                )
                            }
                            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                                MalListFooter(
                                    loadingMore = loading && requestedOffset > 0,
                                    error = error,
                                    hasMore = nextOffset != null,
                                    count = editor.entries.size,
                                    onRetry = { retryId++ },
                                )
                            }
                        }
                        if (loading && requestedOffset == 0) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().padding(horizontal = inset).height(2.dp).align(Alignment.TopCenter),
                            )
                        }
                        NuvioDesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }

    editingId?.let { id ->
        val entry = editor.entries.firstOrNull { it.anime.id == id }
        if (entry == null) {
            editingId = null
        } else {
            MalEntryEditorDialog(
                entry = entry,
                onDismiss = { editingId = null },
                onSave = { draft ->
                    editingId = null
                    editor.edit(id) { draft }
                },
                onRemove = { removingId = id },
            )
        }
    }
    removingId?.let { id ->
        val entry = editor.entries.firstOrNull { it.anime.id == id }
        if (entry == null) {
            removingId = null
        } else {
            MalRemoveDialog(
                title = entry.anime.title,
                onDismiss = { removingId = null },
                onConfirm = {
                    removingId = null
                    editingId = null
                    editor.remove(id) {
                        statsVersion++
                        NuvioToastController.show("Removed from MyAnimeList")
                    }
                },
            )
        }
    }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
}

private fun malProfileUrl(username: String): String =
    username.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,32}")) }
        ?.let { "https://myanimelist.net/animelist/$it" }
        ?: "https://myanimelist.net/"

@Composable
internal fun MalProfileSummary(username: String?, stats: MalAnimeStatistics?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (username != null) {
            Text(
                "Signed in as $username",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        if (stats != null && stats.total > 0) {
            MalStat("%,d".format(stats.total), if (stats.total == 1) "title" else "titles")
            MalStat("%,d".format(stats.episodes), "episodes")
            MalStat("%.1f".format(stats.days), "days watched")
            if (stats.meanScore > 0) MalStat("%.2f".format(stats.meanScore), "mean score")
        }
    }
}

@Composable
private fun MalStat(value: String, label: String) {
    val colors = MaterialTheme.nuvio.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
internal fun MalListToolbar(
    selected: MalStatus?,
    onSelect: (MalStatus?) -> Unit,
    stats: MalAnimeStatistics?,
    sort: MalListSort,
    onSort: (MalListSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A separate sort row keeps every category visible on smaller windows.
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MalStatusTabs(selected, onSelect, stats, Modifier.fillMaxWidth())
                Box(Modifier.align(Alignment.End)) { MalSortControl(sort, onSort) }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MalStatusTabs(selected, onSelect, stats, Modifier.weight(1f))
                MalSortControl(sort, onSort)
            }
        }
    }
}

@Composable
private fun MalStatusTabs(
    selected: MalStatus?,
    onSelect: (MalStatus?) -> Unit,
    stats: MalAnimeStatistics?,
    modifier: Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        NuvioSegmentedControl(
            segments = (listOf<MalStatus?>(null) + MalStatus.entries).map { status ->
                NuvioSegment(
                    key = status,
                    label = status?.label ?: "All",
                    count = stats?.count(status)?.takeIf { stats.total > 0 }?.let { "%,d".format(it) },
                )
            },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun MalSortControl(sort: MalListSort, onSort: (MalListSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NuvioCompactActionButton(
            label = sort.label,
            icon = Icons.AutoMirrored.Rounded.Sort,
            trailingIcon = Icons.Rounded.ExpandMore,
            accessibilityLabel = "Sort by ${sort.label}",
            onClick = { open = true },
            tone = NuvioCompactActionTone.Secondary,
            size = NuvioActionSize.Small,
        )
        Menu(expanded = open, onDismissRequest = { open = false }) {
            MalListSort.entries.forEach { option ->
                MenuItem(text = option.label, selected = option == sort, onClick = { open = false; onSort(option) })
            }
        }
    }
}

@Composable
internal fun MalEntryCard(
    entry: MalAnimeListEntry,
    saving: Boolean,
    onChange: ((MalAnimeListEntry) -> MalListStatus) -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val list = entry.listStatus
    val accent = malStatusColor(list.status)
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .testTag("mal-entry-${entry.anime.id}")
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .hoverable(interaction)
            .clip(shape)
            .background(colors.surfaceCard)
            .border(NuvioTokens.Border.thin, if (hovered) colors.borderDefault else colors.borderSubtle, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MalPoster(entry, accent, onClick = onEdit)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.anime.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = if (entry.englishTitle != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.englishTitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (saving) {
                    Text(
                        "Saving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
                MalEntryMenu(onEdit = onEdit, onOpen = onOpen, onRemove = onRemove)
            }
            Spacer(Modifier.height(6.dp))
            MalFacts(entry)
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            MalEpisodeProgress(entry, accent)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MalStatusPicker(
                        status = list.status,
                        onSelect = { status -> onChange { it.listStatus.withStatus(status, it.totalEpisodes) } },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MalScorePicker(score = list.score, onSelect = { score -> onChange { it.listStatus.copy(score = score) } })
                }
                MalEpisodeStepper(
                    watched = list.watchedEpisodes,
                    total = entry.totalEpisodes,
                    onStep = { delta ->
                        onChange { it.listStatus.withWatchedEpisodes(it.listStatus.watchedEpisodes + delta, it.totalEpisodes) }
                    },
                    onEdit = onEdit,
                )
            }
        }
    }
}

@Composable
private fun MalPoster(entry: MalAnimeListEntry, accent: Color, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(PosterWidth, PosterHeight)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(colors.skeleton)
            .clickable(onClickLabel = "Edit ${entry.anime.title}", role = Role.Button, onClick = onClick),
    ) {
        NuvioAsyncImage(
            model = entry.imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (entry.airing) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(NuvioTokens.Radius.xs))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(malStatusColor(MalStatus.WATCHING), CircleShape))
                Text("Airing", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(accent))
    }
}

@Composable
private fun MalFacts(entry: MalAnimeListEntry) {
    val colors = MaterialTheme.nuvio.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val facts = malEntryFacts(entry)
        if (facts.isNotEmpty()) {
            Text(
                facts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        entry.meanScore?.let { mean ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "MyAnimeList community score %.2f".format(mean)
                },
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(13.dp))
                Text("%.2f".format(mean), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
        if (entry.listStatus.isRewatching) {
            Row(
                Modifier
                    .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(NuvioTokens.Radius.xs))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Text("Rewatching", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun MalEpisodeProgress(entry: MalAnimeListEntry, accent: Color) {
    val colors = MaterialTheme.nuvio.colors
    val watched = entry.listStatus.watchedEpisodes
    val total = entry.totalEpisodes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    total > 0 -> "$watched of $total episodes"
                    watched == 1 -> "1 episode watched"
                    else -> "$watched episodes watched"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text(
                    "${(watched * 100 / total).coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                )
            }
        }
        if (total > 0) {
            NuvioProgressBar(
                progress = watched.toFloat() / total,
                height = 4.dp,
                trackColor = colors.textPrimary.copy(alpha = 0.08f),
                fillColor = accent,
            )
        } else {
            // Without a known length an empty bar would read as "not started"; keep the row height.
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MalPill(
    onClick: () -> Unit,
    container: Color,
    border: Color,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(ControlHeight).semantics { contentDescription = accessibilityLabel },
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = container,
        border = BorderStroke(NuvioTokens.Border.thin, border),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun MalStatusPicker(status: MalStatus?, onSelect: (MalStatus) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val accent = malStatusColor(status)
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        MalPill(
            onClick = { open = true },
            container = accent.copy(alpha = 0.14f),
            border = accent.copy(alpha = 0.38f),
            accessibilityLabel = "Status: ${status?.label ?: "not set"}. Change status",
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(
                status?.label ?: "Set status",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        Menu(expanded = open, onDismissRequest = { open = false }) {
            MalStatus.entries.forEach { option ->
                MenuItem(text = option.label, selected = option == status, onClick = { open = false; onSelect(option) })
            }
        }
    }
}

@Composable
private fun MalScorePicker(score: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    Box {
        MalPill(
            onClick = { open = true },
            container = colors.textPrimary.copy(alpha = 0.06f),
            border = colors.borderSubtle,
            accessibilityLabel = if (score > 0) "Your score: $score out of 10. Change score" else "Score this title",
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = if (score > 0) ScoreGold else colors.textMuted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (score > 0) "$score" else "Rate",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (score > 0) FontWeight.SemiBold else null,
                color = if (score > 0) colors.textPrimary else colors.textSecondary,
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        Menu(expanded = open, onDismissRequest = { open = false }) {
            (10 downTo 1).forEach { option ->
                MenuItem(
                    text = "$option  ·  ${malScoreLabel(option)}",
                    selected = option == score,
                    onClick = { open = false; onSelect(option) },
                )
            }
            if (score > 0) MenuItem(text = "Remove score", onClick = { open = false; onSelect(0) })
        }
    }
}

@Composable
private fun MalEpisodeStepper(watched: Int, total: Int, onStep: (Int) -> Unit, onEdit: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.chip)
    Row(
        Modifier
            .testTag("mal-episode-stepper")
            .height(ControlHeight)
            .clip(shape)
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .border(NuvioTokens.Border.thin, colors.borderSubtle, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MalStepButton(Icons.Rounded.Remove, "One episode less", enabled = watched > 0, size = ControlHeight) { onStep(-1) }
        Text(
            "$watched / ${if (total > 0) total else "?"}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                .clickable(onClickLabel = "Set episodes watched", onClick = onEdit)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        MalStepButton(Icons.Rounded.Add, "One more episode", enabled = total <= 0 || watched < total, size = ControlHeight) { onStep(1) }
    }
}

@Composable
private fun MalStepButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.textPrimary else colors.textDisabled, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MalEntryMenu(onEdit: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More actions", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        Menu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(text = "Edit entry", onClick = { open = false; onEdit() })
            MenuItem(text = "Open on MyAnimeList", onClick = { open = false; onOpen() })
            MenuItem(text = "Remove from list", onClick = { open = false; onRemove() })
        }
    }
}

@Composable
private fun MalListFooter(loadingMore: Boolean, error: String?, hasMore: Boolean, count: Int, onRetry: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            loadingMore -> NuvioLoadingIndicator(size = 22.dp)
            error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            !hasMore -> Text(
                if (count == 1) "1 title" else "%,d titles".format(count),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun MalSkeletonGrid(inset: Dp) {
    val colors = MaterialTheme.nuvio.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = inset, end = inset, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        items(9) {
            val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceCard)
                    .border(NuvioTokens.Border.thin, colors.borderSubtle, shape)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkeletonBlock(width = PosterWidth, height = PosterHeight, cornerRadius = NuvioTokens.Radius.md)
                Column(Modifier.weight(1f).height(PosterHeight)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f), height = 18.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.45f), height = 12.dp)
                    Spacer(Modifier.weight(1f))
                    SkeletonBlock(Modifier.fillMaxWidth(), height = 4.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(width = 110.dp, height = ControlHeight, cornerRadius = 17.dp)
                        SkeletonBlock(width = 64.dp, height = ControlHeight, cornerRadius = 17.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MalMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier.padding(top = 32.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            NuvioCompactActionButton(
                label = action,
                icon = Icons.Rounded.Refresh,
                onClick = onAction,
                tone = NuvioCompactActionTone.Secondary,
                size = NuvioActionSize.Small,
            )
        }
    }
}

@Composable
private fun MalConnectPrompt(connection: MalConnectionState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(colors.surfaceCard)
            .border(NuvioTokens.Border.thin, colors.borderSubtle, shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Connect your MyAnimeList",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Browse your list here, update statuses, scores and episode progress, and every change syncs straight to MyAnimeList.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        if (connection.connecting) {
            Text("Waiting for you to approve access in your browser…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = MyAnimeListRepository::cancelConnect) { Text("Cancel") }
        } else {
            NuvioCompactActionButton(
                label = "Connect MyAnimeList",
                icon = Icons.Rounded.Link,
                onClick = onConnect,
                enabled = !connection.busy,
                tone = NuvioCompactActionTone.Emphasized,
            )
        }
        connection.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MalEntryEditorDialog(
    entry: MalAnimeListEntry,
    onDismiss: () -> Unit,
    onSave: (MalListStatus) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val total = entry.totalEpisodes
    var draft by remember(entry.anime.id) { mutableStateOf(entry.listStatus) }
    var episodesText by remember(entry.anime.id) { mutableStateOf(entry.listStatus.watchedEpisodes.toString()) }
    fun update(next: MalListStatus) {
        draft = next
        episodesText = next.watchedEpisodes.toString()
    }
    DialogSurface(onDismissRequest = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(NuvioTokens.Radius.sm)).background(colors.skeleton)) {
                NuvioAsyncImage(entry.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                entry.englishTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val facts = malEntryFacts(entry)
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }

        DialogSectionLabel("Status")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MalStatus.entries.forEach { status ->
                val selected = draft.status == status
                val accent = malStatusColor(status)
                MalPill(
                    onClick = { update(draft.withStatus(status, total)) },
                    container = if (selected) accent.copy(alpha = 0.2f) else colors.textPrimary.copy(alpha = 0.05f),
                    border = if (selected) accent else colors.borderSubtle,
                    accessibilityLabel = status.label + if (selected) ", selected" else "",
                ) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text(
                        status.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        DialogSectionLabel("Episodes watched")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MalStepButton(Icons.Rounded.Remove, "One episode less", enabled = draft.watchedEpisodes > 0) {
                update(draft.withWatchedEpisodes(draft.watchedEpisodes - 1, total))
            }
            DialogTextField(
                value = episodesText,
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(5)
                    episodesText = digits
                    digits.toIntOrNull()?.let { count ->
                        draft = draft.withWatchedEpisodes(count, total)
                        if (draft.watchedEpisodes != count) episodesText = draft.watchedEpisodes.toString()
                    }
                },
                label = "Episodes",
                modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
            )
            MalStepButton(Icons.Rounded.Add, "One more episode", enabled = total <= 0 || draft.watchedEpisodes < total) {
                update(draft.withWatchedEpisodes(draft.watchedEpisodes + 1, total))
            }
            Text(
                if (total > 0) "of $total" else "Total not announced",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        DialogSectionLabel("Your score")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { score ->
                val selected = draft.score == score
                Surface(
                    onClick = { draft = draft.copy(score = if (selected) 0 else score) },
                    modifier = Modifier.size(width = 34.dp, height = 34.dp).semantics {
                        contentDescription = "$score, ${malScoreLabel(score)}" + if (selected) ", selected" else ""
                    },
                    shape = RoundedCornerShape(NuvioTokens.Radius.md),
                    color = if (selected) ScoreGold else colors.textPrimary.copy(alpha = 0.05f),
                    border = BorderStroke(NuvioTokens.Border.thin, if (selected) ScoreGold else colors.borderSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$score",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color(0xFF1A1400) else colors.textPrimary,
                        )
                    }
                }
            }
        }
        Text(
            if (draft.score > 0) "${draft.score} · ${malScoreLabel(draft.score)}" else "Not scored. Pick a number, or click it again to clear.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        if (draft.status == MalStatus.COMPLETED) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .toggleable(value = draft.isRewatching, role = Role.Switch, onValueChange = { draft = draft.copy(isRewatching = it) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rewatching", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text("Stays completed while you watch it again.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = draft.isRewatching, onCheckedChange = null, colors = nuvioSwitchColors())
            }
        }

        TextButton(onClick = onRemove, modifier = Modifier.offset(x = (-12).dp)) {
            Text("Remove from list", color = colors.danger)
        }
        DialogButtons {
            DialogButton(text = "Cancel", onClick = onDismiss)
            DialogButton(
                text = "Save",
                onClick = { onSave(draft) },
                style = DialogButtonStyle.Primary,
                enabled = draft != entry.listStatus,
            )
        }
    }
}

@Composable
private fun MalRemoveDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = "Remove from MyAnimeList?",
        message = "\"$title\" will be removed from your list, along with its status, score and episode progress.",
    ) {
        DialogButtons {
            DialogButton(text = "Cancel", onClick = onDismiss)
            DialogButton(text = "Remove", onClick = onConfirm, style = DialogButtonStyle.Destructive)
        }
    }
}
