package com.nuvio.app.features.airing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.features.catalog.LiveCatalogRefreshEffect
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal actual fun BuiltInAiringSection(modifier: Modifier, onPosterClick: ((MetaPreview) -> Unit)?) {
    val repository = remember { NativeAiring.repository }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedWindow by rememberSaveable { mutableStateOf(AiringWindow.Today) }
    var showSettings by remember { mutableStateOf(false) }
    val rowState = rememberLazyListState()
    val events = remember(state.snapshot, state.settings, state.now, selectedWindow) {
        selectAiringEvents(state.snapshot.events, state.settings, state.now, selectedWindow)
    }
    ScreenActivityEffect(repository) { active -> if (active) repository.refresh() }
    LiveCatalogRefreshEffect(repository, enabled = true) { scope.launch { repository.refresh() } }
    LaunchedEffect(selectedWindow, state.settings.airType) { rowState.scrollToItem(0) }

    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Airing Now", style = MaterialTheme.typography.headlineSmall)
                val source = if (state.settings.airType == "broadcast") "AniList · Broadcast" else "Tsuzuki · ${state.settings.airType.uppercase()}"
                Text("$source · ${state.settings.zone}", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
                TextButton(enabled = !state.loading, onClick = { scope.launch { repository.refresh(force = true) } }) { Text("Refresh") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiringWindow.entries.forEach { window ->
                FilterChip(selected = window == selectedWindow, onClick = { selectedWindow = window }, label = { Text(window.label) })
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { message ->
            val retained = if (state.snapshot.savedAt > 0) " Showing saved schedules." else " Try Broadcast in Settings for an independent source."
            Text("$message.$retained", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (events.isEmpty() && !state.loading && state.error == null) {
            Text("No matching episodes in this window.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(events, key = { it.mediaId }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomePosterCard(item = event.preview(), onClick = { onPosterClick?.invoke(event.preview()) })
                    val formatted = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.of(state.settings.zone))
                        .format(Instant.ofEpochSecond(event.airingAt))
                    Text("Episode ${event.episode} · $formatted${if (event.estimated) " (estimated)" else ""}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 240.dp))
                }
            }
        }
        val refreshed = state.snapshot.savedAt.takeIf { it > 0 }?.let {
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(state.settings.zone)).format(Instant.ofEpochMilli(it))
        }
        Text("${refreshed?.let { "Updated $it · " }.orEmpty()}Schedules refresh automatically. Air times do not guarantee stream availability.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showSettings) AiringSettingsDialog(state.settings,
        onDismiss = { showSettings = false },
        onSave = { settings -> showSettings = false; scope.launch { repository.updateSettings(settings) } })
}

@Composable
private fun AiringSettingsDialog(settings: AiringSettings, onDismiss: () -> Unit, onSave: (AiringSettings) -> Unit) {
    var zone by remember { mutableStateOf(settings.zone) }
    var airType by remember { mutableStateOf(settings.airType) }
    var days by remember { mutableStateOf(settings.historyDays) }
    var estimated by remember { mutableStateOf(settings.includeEstimated) }
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
            }
        },
        confirmButton = { TextButton(enabled = validZone, onClick = { onSave(AiringSettings(zone.trim(), airType, days, estimated)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
