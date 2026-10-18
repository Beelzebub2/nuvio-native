package com.nuvio.app.features.airing

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioSegmentedSize
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.catalogPosterBaseWidthDp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.LiveCatalogRefreshEffect
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.watchlater.EpisodeSynopsis
import com.nuvio.app.features.watchlater.watchLaterMiddleClick
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.airing_action_browse
import nuvio.composeapp.generated.resources.airing_action_hide
import nuvio.composeapp.generated.resources.airing_action_refresh
import nuvio.composeapp.generated.resources.airing_action_settings
import nuvio.composeapp.generated.resources.airing_coming_up
import nuvio.composeapp.generated.resources.airing_coming_up_empty
import nuvio.composeapp.generated.resources.airing_days_label
import nuvio.composeapp.generated.resources.airing_days_last
import nuvio.composeapp.generated.resources.airing_days_unit
import nuvio.composeapp.generated.resources.airing_episode_label
import nuvio.composeapp.generated.resources.airing_estimated
import nuvio.composeapp.generated.resources.airing_footer
import nuvio.composeapp.generated.resources.airing_hidden
import nuvio.composeapp.generated.resources.airing_hidden_note
import nuvio.composeapp.generated.resources.airing_hidden_title
import nuvio.composeapp.generated.resources.airing_hidden_toast
import nuvio.composeapp.generated.resources.airing_hide_named
import nuvio.composeapp.generated.resources.airing_next_items
import nuvio.composeapp.generated.resources.airing_now_title
import nuvio.composeapp.generated.resources.airing_previous_items
import nuvio.composeapp.generated.resources.airing_recent_empty
import nuvio.composeapp.generated.resources.airing_recent_title
import nuvio.composeapp.generated.resources.airing_saved_schedules
import nuvio.composeapp.generated.resources.airing_settings_estimated
import nuvio.composeapp.generated.resources.airing_settings_history
import nuvio.composeapp.generated.resources.airing_settings_hide_nsfw
import nuvio.composeapp.generated.resources.airing_settings_intro
import nuvio.composeapp.generated.resources.airing_settings_title
import nuvio.composeapp.generated.resources.airing_settings_zone
import nuvio.composeapp.generated.resources.airing_settings_zone_example
import nuvio.composeapp.generated.resources.airing_show_again
import nuvio.composeapp.generated.resources.airing_show_again_named
import nuvio.composeapp.generated.resources.airing_show_all_again
import nuvio.composeapp.generated.resources.airing_today
import nuvio.composeapp.generated.resources.airing_try_broadcast
import nuvio.composeapp.generated.resources.airing_updated_at
import nuvio.composeapp.generated.resources.action_back
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal actual fun BuiltInAiringSection(
    modifier: Modifier,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) = AiringBrowser(modifier, expanded = false, initialWindow = AiringWindow.Now.name,
    onPosterClick = onPosterClick, onPosterLongClick = onPosterLongClick, onCatalogClick = onCatalogClick)

@Composable
internal actual fun BuiltInAiringCatalog(
    window: String,
    onBack: () -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: (MetaPreview) -> Unit,
) = AiringBrowser(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
    expanded = true, initialWindow = window, onBack = onBack,
    onPosterClick = onPosterClick, onPosterLongClick = onPosterLongClick)

/**
 * Home's "Recently released" row (newest episode per show from the last 1–5 days) with the
 * upcoming schedule below it, or the full Airing Now page when [expanded].
 */
@Composable
internal fun AiringBrowser(
    modifier: Modifier,
    expanded: Boolean,
    initialWindow: String,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onBack: (() -> Unit)? = null,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    repository: AiringRepository = NativeAiring.repository,
) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.nuvio.colors
    var selectedWindow by rememberSaveable {
        mutableStateOf(AiringWindow.Now)
    }
    var showSettings by remember { mutableStateOf(false) }
    val rowState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val posterStyle = rememberPosterCardStyleUiState()
    val events = remember(state.snapshot, state.settings, state.now, selectedWindow) {
        selectAiringEvents(state.snapshot.events, state.settings, state.now, selectedWindow)
    }
    ScreenActivityEffect(repository) { active -> if (active) repository.refresh() }
    LiveCatalogRefreshEffect(repository, enabled = true) { scope.launch { repository.refresh() } }
    LaunchedEffect(selectedWindow, state.settings.airType) {
        rowState.scrollToItem(0)
        gridState.scrollToItem(0)
    }
    val source = if (state.settings.airType == "broadcast") "AniList · Broadcast" else "Tsuzuki · ${state.settings.airType.uppercase()}"
    val airingNowTitle = stringResource(Res.string.airing_now_title)
    val viewAll: (() -> Unit)? = onCatalogClick?.let { open -> {
        open(HomeCatalogSection(key = "native-airing-${selectedWindow.name}", title = airingNowTitle,
            subtitle = "$source · ${selectedWindow.label}", addonName = "Built-in",
            target = CatalogTarget.Airing(selectedWindow.name), items = events.map { it.preview() }))
    } }
    val hiddenToast = stringResource(Res.string.airing_hidden_toast, "%s")
    val hide: (AiringEvent) -> Unit = { event ->
        scope.launch { repository.hide(event) }
        NuvioToastController.show(hiddenToast.replace("%s", event.name))
    }

    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f).then(if (viewAll != null) Modifier.clickable(onClick = viewAll) else Modifier)) {
                Text(
                    stringResource(if (expanded) Res.string.airing_now_title else Res.string.airing_recent_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("$source · ${state.settings.zone}", color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            HiddenTitlesButton(
                hidden = state.settings.hidden,
                onShowAgain = { mediaId -> scope.launch { repository.showAgain(mediaId) } },
                onShowAllAgain = { scope.launch { repository.showAllAgain() } },
            )
            if (viewAll != null) NuvioCompactActionButton(
                label = stringResource(Res.string.airing_action_browse), icon = null, onClick = viewAll,
                tone = NuvioCompactActionTone.Ghost, size = NuvioActionSize.Small,
            )
            NuvioCompactActionButton(
                label = stringResource(Res.string.airing_action_settings), icon = Icons.Rounded.Tune,
                onClick = { showSettings = true }, tone = NuvioCompactActionTone.Ghost, size = NuvioActionSize.Small,
            )
            NuvioCompactActionButton(
                label = stringResource(Res.string.airing_action_refresh), icon = Icons.Rounded.Refresh,
                onClick = { scope.launch { repository.refresh(force = true) } }, enabled = !state.loading,
                tone = NuvioCompactActionTone.Ghost, size = NuvioActionSize.Small,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.airing_days_last), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            NuvioSegmentedControl(
                segments = (1..MAX_AIRING_HISTORY_DAYS).map { days ->
                    NuvioSegment(days, days.toString(), contentDescription = stringResource(Res.string.airing_days_label, days))
                },
                selected = state.settings.historyDays.coerceIn(1, MAX_AIRING_HISTORY_DAYS),
                onSelect = { days ->
                    if (days != state.settings.historyDays) {
                        scope.launch { repository.updateSettings(state.settings.copy(historyDays = days)) }
                    }
                },
                size = NuvioSegmentedSize.Dense,
            )
            Text(stringResource(Res.string.airing_days_unit), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            if (!expanded) {
                Spacer(Modifier.weight(1f))
                IconButton(enabled = rowState.canScrollBackward, onClick = {
                    scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex - rowState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)).coerceAtLeast(0)) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.airing_previous_items),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (rowState.canScrollBackward) 1f else 0.38f)) }
                IconButton(enabled = rowState.canScrollForward, onClick = {
                    scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex + rowState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)).coerceAtMost(events.lastIndex.coerceAtLeast(0))) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.airing_next_items),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (rowState.canScrollForward) 1f else 0.38f)) }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { message ->
            val retained = stringResource(if (state.snapshot.savedAt > 0) Res.string.airing_saved_schedules else Res.string.airing_try_broadcast)
            Text("$message. $retained", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (events.isEmpty() && !state.loading && state.error == null) {
            Text(stringResource(Res.string.airing_recent_empty, state.settings.historyDays), color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (expanded) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(columns = GridCells.Adaptive(catalogPosterBaseWidthDp(posterStyle.widthDp).dp),
                    state = gridState, modifier = Modifier.fillMaxSize().testTag("airing-grid"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(8.dp)) {
                    items(events, key = { it.mediaId }) { event ->
                        AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, onHide = { hide(event) })
                    }
                }
                NuvioDesktopVerticalScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        } else {
            NuvioShelfSection(title = "", entries = events, state = rowState, itemSpacing = 16.dp, key = { it.mediaId }) { event ->
                AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, onHide = { hide(event) })
            }
            HorizontalScrollbar(rememberScrollbarAdapter(rowState), modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        if (!expanded) AnimeSchedule(state, onPosterClick, onPosterLongClick, onHide = hide)
        val refreshed = state.snapshot.savedAt.takeIf { it > 0 }?.let {
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(state.settings.zone)).format(Instant.ofEpochMilli(it))
        }
        Text(
            listOfNotNull(refreshed?.let { stringResource(Res.string.airing_updated_at, it) }, stringResource(Res.string.airing_footer))
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = colors.textMuted,
        )
    }
    if (showSettings) AiringSettingsDialog(state.settings, onDismiss = { showSettings = false },
        onSave = { settings -> showSettings = false; scope.launch { repository.updateSettings(settings) } })
}

/** "Hidden N" button and the list where hidden titles can be shown again. */
@Composable
private fun HiddenTitlesButton(
    hidden: List<HiddenAiringTitle>,
    onShowAgain: (Int) -> Unit,
    onShowAllAgain: () -> Unit,
) {
    if (hidden.isEmpty()) return
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    Box {
        NuvioCompactActionButton(
            label = stringResource(Res.string.airing_hidden, hidden.size),
            icon = Icons.Rounded.VisibilityOff,
            onClick = { open = !open },
            tone = NuvioCompactActionTone.Ghost,
            size = NuvioActionSize.Small,
            selected = open,
        )
        Menu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier.widthIn(min = 320.dp, max = 360.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(Res.string.airing_hidden_title), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(stringResource(Res.string.airing_hidden_note), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            hidden.sortedByDescending { it.hiddenAt }.forEach { title ->
                Row(
                    modifier = Modifier.widthIn(min = 320.dp, max = 360.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioAsyncImage(
                        model = title.poster,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp, 48.dp)
                            .background(colors.surfaceCard, RoundedCornerShape(NuvioTokens.Radius.sm)),
                    )
                    Text(title.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.airing_show_again),
                        icon = Icons.Rounded.Visibility,
                        onClick = { onShowAgain(title.mediaId); if (hidden.size == 1) open = false },
                        tone = NuvioCompactActionTone.Secondary,
                        size = NuvioActionSize.Small,
                        accessibilityLabel = stringResource(Res.string.airing_show_again_named, title.name),
                    )
                }
            }
            if (hidden.size > 1) {
                TextButton(onClick = { onShowAllAgain(); open = false }, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(stringResource(Res.string.airing_show_all_again))
                }
            }
        }
    }
}

@Composable
private fun AnimeSchedule(
    state: AiringState,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onHide: (AiringEvent) -> Unit,
) {
    val today = state.now.atZone(ZoneId.of(state.settings.zone)).toLocalDate()
    var date by rememberSaveable(today.toString()) { mutableStateOf(today.toString()) }
    val selected = java.time.LocalDate.parse(date)
    val episodes = remember(state.snapshot, state.settings, selected) { selectScheduledEvents(state.snapshot.events, state.settings, selected) }
    val todayLabel = stringResource(Res.string.airing_today)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("anime-schedule"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.airing_coming_up), color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                NuvioSegmentedControl(
                    segments = (0..6).map { offset ->
                        val day = today.plusDays(offset.toLong())
                        NuvioSegment(
                            key = day,
                            label = if (offset == 0) todayLabel else day.format(DateTimeFormatter.ofPattern("EEE d")),
                            testTag = "schedule-$day",
                        )
                    },
                    selected = selected,
                    onSelect = { date = it.toString() },
                    size = NuvioSegmentedSize.Compact,
                )
            }
        }
        if (episodes.isEmpty()) Text(stringResource(Res.string.airing_coming_up_empty), color = MaterialTheme.nuvio.colors.textSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(episodes, key = { "${it.mediaId}:${it.episode}" }) { event ->
                AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, onHide = { onHide(event) })
            }
        }
    }
}

@Composable
private fun AiringPoster(
    event: AiringEvent,
    zone: String,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onHide: (() -> Unit)? = null,
) {
    val preview = event.preview()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(Modifier.hoverable(interactionSource)) {
        EpisodeSynopsis(preview) {
        Column(Modifier.watchLaterMiddleClick(preview), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HomePosterCard(item = preview, onClick = { onPosterClick?.invoke(preview) },
                onLongClick = onPosterLongClick?.let { { it(preview) } })
            val formatted = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.of(zone)).format(Instant.ofEpochSecond(event.airingAt))
            val label = stringResource(Res.string.airing_episode_label, event.episode, formatted) +
                if (event.estimated) " ${stringResource(Res.string.airing_estimated)}" else ""
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier.widthIn(max = 240.dp))
        }
        }
        if (hovered && onHide != null) {
            NuvioCompactActionButton(
                label = stringResource(Res.string.airing_action_hide),
                icon = Icons.Rounded.VisibilityOff,
                onClick = onHide,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                tone = NuvioCompactActionTone.Tonal,
                size = NuvioActionSize.Small,
                accessibilityLabel = stringResource(Res.string.airing_hide_named, event.name),
            )
        }
    }
}

@Composable
private fun AiringSettingsDialog(settings: AiringSettings, onDismiss: () -> Unit, onSave: (AiringSettings) -> Unit) {
    var zone by remember { mutableStateOf(settings.zone) }
    var airType by remember { mutableStateOf(settings.airType) }
    var days by remember { mutableStateOf(settings.historyDays.coerceIn(1, MAX_AIRING_HISTORY_DAYS)) }
    var estimated by remember { mutableStateOf(settings.includeEstimated) }
    var hideNsfw by remember { mutableStateOf(settings.hideNsfw) }
    val validZone = remember(zone) { runCatching { ZoneId.of(zone.trim()) }.isSuccess }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(Res.string.airing_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.airing_settings_intro))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("broadcast" to "Broadcast", "sub" to "Sub", "dub" to "Dub").forEach { (value, label) ->
                        FilterChip(selected = value == airType, onClick = { airType = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = zone, onValueChange = { zone = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.airing_settings_zone)) }, isError = !validZone,
                    supportingText = { Text(stringResource(Res.string.airing_settings_zone_example)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.airing_settings_history, days), modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(enabled = days > 1, onClick = { days-- }) { Text("−") }
                    TextButton(enabled = days < MAX_AIRING_HISTORY_DAYS, onClick = { days++ }) { Text("+") }
                }
                Row {
                    Checkbox(checked = estimated, onCheckedChange = { estimated = it })
                    Text(stringResource(Res.string.airing_settings_estimated), modifier = Modifier.padding(top = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hideNsfw, onCheckedChange = { hideNsfw = it },
                        modifier = Modifier.testTag("airing-hide-nsfw"))
                    Text(stringResource(Res.string.airing_settings_hide_nsfw))
                }
            }
        },
        confirmButton = { TextButton(enabled = validZone, onClick = {
            onSave(settings.copy(zone = zone.trim(), airType = airType, historyDays = days,
                includeEstimated = estimated, hideNsfw = hideNsfw))
        }) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
