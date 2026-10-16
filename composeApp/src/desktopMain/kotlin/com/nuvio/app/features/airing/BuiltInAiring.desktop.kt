package com.nuvio.app.features.airing

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.catalogPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.LiveCatalogRefreshEffect
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.watchlater.EpisodeSynopsis
import com.nuvio.app.features.watchlater.watchLaterMiddleClick
import kotlinx.coroutines.launch
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
    val viewAll: (() -> Unit)? = onCatalogClick?.let { open -> {
        open(HomeCatalogSection(key = "native-airing-${selectedWindow.name}", title = "Airing Now",
            subtitle = "$source · ${selectedWindow.label}", addonName = "Built-in",
            target = CatalogTarget.Airing(selectedWindow.name), items = events.map { it.preview() }))
    } }

    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f).then(if (viewAll != null) Modifier.clickable(onClick = viewAll) else Modifier)) {
                Text("Airing Now", color = MaterialTheme.colorScheme.onBackground,
                    style = if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall)
                Text("$source · ${state.settings.zone}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (viewAll != null) TextButton(onClick = viewAll) { Text("Browse") }
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
            TextButton(enabled = !state.loading, onClick = { scope.launch { repository.refresh(force = true) } }) { Text("Refresh") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!expanded) {
                Spacer(Modifier.weight(1f))
                IconButton(enabled = rowState.canScrollBackward, onClick = {
                    scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex - rowState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)).coerceAtLeast(0)) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous airing items",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (rowState.canScrollBackward) 1f else 0.38f)) }
                IconButton(enabled = rowState.canScrollForward, onClick = {
                    scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex + rowState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)).coerceAtMost(events.lastIndex.coerceAtLeast(0))) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next airing items",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (rowState.canScrollForward) 1f else 0.38f)) }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { message ->
            val retained = if (state.snapshot.savedAt > 0) " Showing saved schedules." else " Try Broadcast in Settings for an independent source."
            Text("$message.$retained", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (events.isEmpty() && !state.loading && state.error == null) {
            Text("No matching episodes in this window.", color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (expanded) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(columns = GridCells.Adaptive(catalogPosterBaseWidthDp(posterStyle.widthDp).dp),
                    state = gridState, modifier = Modifier.fillMaxSize().testTag("airing-grid"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(8.dp)) {
                    items(events, key = { it.mediaId }) { event ->
                        AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick)
                    }
                }
                NuvioDesktopVerticalScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        } else {
            NuvioShelfSection(title = "", entries = events, state = rowState, itemSpacing = 16.dp, key = { it.mediaId }) { event ->
                AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick)
            }
            HorizontalScrollbar(rememberScrollbarAdapter(rowState), modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        if (!expanded) AnimeSchedule(state, onPosterClick, onPosterLongClick)
        val refreshed = state.snapshot.savedAt.takeIf { it > 0 }?.let {
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(state.settings.zone)).format(Instant.ofEpochMilli(it))
        }
        Text("${refreshed?.let { "Updated $it · " }.orEmpty()}Schedules refresh automatically. Middle-click to save to Watch Later.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showSettings) AiringSettingsDialog(state.settings, onDismiss = { showSettings = false },
        onSave = { settings -> showSettings = false; scope.launch { repository.updateSettings(settings) } })
}

@Composable
private fun AnimeSchedule(state: AiringState, onPosterClick: ((MetaPreview) -> Unit)?, onPosterLongClick: ((MetaPreview) -> Unit)?) {
    val today = state.now.atZone(ZoneId.of(state.settings.zone)).toLocalDate()
    var date by rememberSaveable(today.toString()) { mutableStateOf(today.toString()) }
    val selected = java.time.LocalDate.parse(date)
    val episodes = remember(state.snapshot, state.settings, selected) { selectScheduledEvents(state.snapshot.events, state.settings, selected) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("anime-schedule"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Anime schedule", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { offset -> val day = today.plusDays(offset.toLong())
                FilterChip(selected = day == selected, onClick = { date = day.toString() },
                    label = { Text(day.format(DateTimeFormatter.ofPattern("EEE d"))) }, modifier = Modifier.testTag("schedule-$day"))
            }
        }
        if (episodes.isEmpty()) Text("No scheduled episodes for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(episodes, key = { "${it.mediaId}:${it.episode}" }) { event -> AiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick) }
        }
    }
}

@Composable
private fun AiringPoster(event: AiringEvent, zone: String, onPosterClick: ((MetaPreview) -> Unit)?, onPosterLongClick: ((MetaPreview) -> Unit)?) {
    val preview = event.preview()
    EpisodeSynopsis(preview) {
    Column(Modifier.watchLaterMiddleClick(preview), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HomePosterCard(item = preview, onClick = { onPosterClick?.invoke(preview) },
            onLongClick = onPosterLongClick?.let { { it(preview) } })
        val formatted = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.of(zone)).format(Instant.ofEpochSecond(event.airingAt))
        Text("Episode ${event.episode} · $formatted${if (event.estimated) " (estimated)" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 240.dp))
    }
    }
}

@Composable
private fun AiringSettingsDialog(settings: AiringSettings, onDismiss: () -> Unit, onSave: (AiringSettings) -> Unit) {
    var zone by remember { mutableStateOf(settings.zone) }
    var airType by remember { mutableStateOf(settings.airType) }
    var days by remember { mutableStateOf(settings.historyDays) }
    var estimated by remember { mutableStateOf(settings.includeEstimated) }
    var hideNsfw by remember { mutableStateOf(settings.hideNsfw) }
    val validZone = remember(zone) { runCatching { ZoneId.of(zone.trim()) }.isSuccess }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Airing Now settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Broadcast uses AniList's original airing times. Sub and dub use Tsuzuki's release schedules.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("broadcast" to "Broadcast", "sub" to "Sub", "dub" to "Dub").forEach { (value, label) ->
                        FilterChip(selected = value == airType, onClick = { airType = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = zone, onValueChange = { zone = it }, singleLine = true,
                    label = { Text("Time zone") }, isError = !validZone,
                    supportingText = { Text("Example: Europe/Lisbon") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("History: $days ${if (days == 1) "day" else "days"}", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(enabled = days > 1, onClick = { days-- }) { Text("−") }
                    TextButton(enabled = days < 7, onClick = { days++ }) { Text("+") }
                }
                Row {
                    Checkbox(checked = estimated, onCheckedChange = { estimated = it })
                    Text("Include estimated times", modifier = Modifier.padding(top = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hideNsfw, onCheckedChange = { hideNsfw = it },
                        modifier = Modifier.testTag("airing-hide-nsfw"))
                    Text("Hide NSFW / hentai anime")
                }
            }
        },
        confirmButton = { TextButton(enabled = validZone, onClick = {
            onSave(AiringSettings(zone.trim(), airType, days, estimated, hideNsfw))
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
