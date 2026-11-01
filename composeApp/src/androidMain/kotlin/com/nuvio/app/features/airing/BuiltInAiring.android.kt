package com.nuvio.app.features.airing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.airing_action_browse
import nuvio.composeapp.generated.resources.airing_action_refresh
import nuvio.composeapp.generated.resources.airing_action_settings
import nuvio.composeapp.generated.resources.airing_coming_up
import nuvio.composeapp.generated.resources.airing_now_title
import nuvio.composeapp.generated.resources.airing_recent_title
import nuvio.composeapp.generated.resources.airing_settings_estimated
import nuvio.composeapp.generated.resources.airing_settings_hide_nsfw
import nuvio.composeapp.generated.resources.airing_settings_title
import nuvio.composeapp.generated.resources.airing_settings_zone
import nuvio.composeapp.generated.resources.airing_show_all_again
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
) {
    MobileAiringBrowser(modifier, false, null, onPosterClick, onPosterLongClick, onCatalogClick)
}

@Composable
internal actual fun BuiltInAiringCatalog(
    window: String,
    onBack: () -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: (MetaPreview) -> Unit,
) {
    MobileAiringBrowser(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        true, onBack, onPosterClick, onPosterLongClick, null,
    )
}

/**
 * Android uses the same classified schedule, cached data, metadata mappings and profile-scoped
 * preferences as desktop; only the layout is adapted to touch and narrow screens.
 */
@Composable
private fun MobileAiringBrowser(
    modifier: Modifier,
    expanded: Boolean,
    onBack: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
    repository: AiringRepository = NativeAiring.repository,
) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val recent = remember(state.snapshot, state.settings, state.now) {
        selectAiringEvents(state.snapshot.events, state.settings, state.now, AiringWindow.Now)
    }
    val coming = remember(state.snapshot, state.settings, state.now) {
        filterAiringContent(state.snapshot.events, state.settings)
            .filter {
                it.airingAt > state.now.epochSecond && it.airingAt <= state.now.plusSeconds(7 * 86400L).epochSecond &&
                    !it.isBreak && it.airType == state.settings.airType &&
                    (state.settings.includeEstimated || !it.estimated) &&
                    it.mediaId !in state.settings.hiddenMediaIds
            }
            .sortedBy { it.airingAt }.distinctBy { "${it.mediaId}:${it.episode}" }.take(30)
    }
    ScreenActivityEffect(repository) { active -> if (active) repository.refresh() }
    val title = stringResource(if (expanded) Res.string.airing_now_title else Res.string.airing_recent_title)
    val openCatalog: (() -> Unit)? = onCatalogClick?.let { open ->
        {
            open(HomeCatalogSection(
                key = "native-airing-Now", title = title,
                subtitle = "Airing Now", addonName = "Built-in",
                target = CatalogTarget.Airing(AiringWindow.Now.name),
                items = recent.map { it.preview() },
            ))
        }
    }
    val hide: (AiringEvent) -> Unit = { event -> scope.launch { repository.hide(event) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${if (state.settings.airType == "broadcast") "AniList" else "Tsuzuki"} · ${state.settings.zone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (openCatalog != null) TextButton(onClick = openCatalog) {
                Text(stringResource(Res.string.airing_action_browse))
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Rounded.Settings, stringResource(Res.string.airing_action_settings))
            }
            IconButton(onClick = { scope.launch { repository.refresh(force = true) } }, enabled = !state.loading) {
                Icon(Icons.Rounded.Refresh, stringResource(Res.string.airing_action_refresh))
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!expanded) {
            if (recent.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.mediaId }) { event ->
                        MobileAiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, hide)
                    }
                }
            } else if (!state.loading) {
                Text("No recently aired episodes for this period.", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Text(stringResource(Res.string.airing_coming_up), style = MaterialTheme.typography.titleMedium)
            if (coming.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(coming, key = { "${it.mediaId}:${it.episode}" }) { event ->
                        MobileAiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, hide)
                    }
                }
            } else if (!state.loading) {
                Text("No upcoming episodes found.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            if (recent.isEmpty() && !state.loading) {
                Text("No recently aired episodes for this period.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 126.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(recent, key = { it.mediaId }) { event ->
                    MobileAiringPoster(event, state.settings.zone, onPosterClick, onPosterLongClick, hide)
                }
            }
        }
    }
    if (editing) MobileAiringSettingsDialog(
        state.settings, onDismiss = { editing = false },
        onSave = { choice ->
            editing = false
            scope.launch { repository.updateSettings(choice) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileAiringPoster(
    event: AiringEvent,
    zone: String,
    onClick: ((MetaPreview) -> Unit)?,
    onLongClick: ((MetaPreview) -> Unit)?,
    onHide: (AiringEvent) -> Unit,
) {
    Column(Modifier.width(128.dp).combinedClickable(
        onClick = { onClick?.invoke(event.preview()) },
        onLongClick = { onLongClick?.invoke(event.preview()) },
    ), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Card(Modifier.fillMaxWidth().height(184.dp)) {
                if (event.poster != null) NuvioAsyncImage(
                    model = event.poster, contentDescription = event.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
            IconButton(onClick = { onHide(event) }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Icons.Rounded.VisibilityOff, "Hide ${event.name}",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        Text(event.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        val whenText = remember(event.airingAt, zone) {
            runCatching {
                DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.of(zone))
                    .format(Instant.ofEpochSecond(event.airingAt))
            }.getOrDefault("")
        }
        Text("E${event.episode} · $whenText", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun MobileAiringSettingsDialog(
    original: AiringSettings,
    onDismiss: () -> Unit,
    onSave: (AiringSettings) -> Unit,
) {
    var edited by remember(original) { mutableStateOf(original) }
    val validZone = remember(edited.zone) { runCatching { ZoneId.of(edited.zone) }.isSuccess }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.airing_settings_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recently released: days", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..MAX_AIRING_HISTORY_DAYS).forEach { days ->
                        FilterChip(selected = edited.historyDays == days, onClick = {
                            edited = edited.copy(historyDays = days)
                        }, label = { Text("$days") })
                    }
                }
                Text("Episode source", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("broadcast", "sub", "dub").forEach { type ->
                        FilterChip(selected = edited.airType == type,
                            onClick = { edited = edited.copy(airType = type) },
                            label = { Text(type.replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(
                    value = edited.zone, onValueChange = { edited = edited.copy(zone = it) },
                    label = { Text(stringResource(Res.string.airing_settings_zone)) },
                    singleLine = true, isError = !validZone, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.airing_settings_hide_nsfw), Modifier.weight(1f))
                    Switch(checked = edited.hideNsfw, onCheckedChange = { edited = edited.copy(hideNsfw = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.airing_settings_estimated), Modifier.weight(1f))
                    Switch(checked = edited.includeEstimated,
                        onCheckedChange = { edited = edited.copy(includeEstimated = it) })
                }
                if (edited.hidden.isNotEmpty()) {
                    HorizontalDivider()
                    TextButton(onClick = { edited = edited.copy(hidden = emptyList()) }) {
                        Text(stringResource(Res.string.airing_show_all_again))
                    }
                    edited.hidden.forEach { hidden ->
                        TextButton(onClick = {
                            edited = edited.copy(hidden = edited.hidden.filterNot { it.mediaId == hidden.mediaId })
                        }) { Text("Show again: ${hidden.name}") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(edited) }, enabled = validZone) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
