package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.watchprogress.CurrentDateProvider

internal fun androidBatchEpisodeKey(video: MetaVideo): String =
    "${video.season}:${video.episode}:${video.id}"

/** Skip future episodes and existing tasks; failed tasks may be retried in a batch. */
internal fun androidBatchAvailableEpisodes(
    meta: MetaDetails, videos: List<MetaVideo>, existing: List<DownloadItem>, today: String,
): List<MetaVideo> {
    val reserved = existing.filter {
        it.parentMetaId == meta.id && it.parentMetaType == meta.type && it.status != DownloadStatus.Failed
    }.mapTo(mutableSetOf()) { it.logicalContentKey }
    return videos.filter { video ->
        val key = if (video.season != null && video.episode != null) {
            "${meta.type}|${meta.id}|${video.season}|${video.episode}"
        } else "${meta.type}|${meta.id}|${video.id}"
        video.id.isNotBlank() && video.isReleasedBy(today) && key !in reserved
    }.distinctBy(::androidBatchEpisodeKey)
}

@Composable
internal fun AndroidBatchDownloadDialog(
    meta: MetaDetails,
    initialSeason: Int,
    onDismiss: () -> Unit,
) {
    val movie = meta.type.equals("movie", ignoreCase = true) && meta.videos.isEmpty()
    val allVideos = remember(meta) {
        if (movie) listOf(MetaVideo(meta.defaultVideoId ?: meta.id, meta.name))
        else meta.videos
    }
    val state by DownloadsRepository.uiState.collectAsState()
    val today = remember { CurrentDateProvider.todayIsoDate() }
    val available = remember(meta, allVideos, state.items, today) {
        androidBatchAvailableEpisodes(meta, allVideos, state.items, today)
    }
    val seasons = remember(allVideos) { allVideos.map { it.season ?: 0 }.distinct().sorted() }
    var season by remember(meta.id) {
        mutableStateOf(initialSeason.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 0)
    }
    var selected by remember(meta.id) {
        mutableStateOf(available.filter { movie || it.season == season }
            .map(::androidBatchEpisodeKey).toSet())
    }
    var quality by remember { mutableStateOf(DownloadQuality.Highest) }
    val providers = remember(meta.id, allVideos) {
        buildList<Pair<String?, String>> {
            add(null to "Any provider")
            val videoId = allVideos.firstOrNull()?.id ?: meta.id
            AddonRepository.uiState.value.addons.enabledAddons().forEach { addon ->
                val manifest = addon.manifest ?: return@forEach
                if (manifest.supportsStream(meta.type, videoId)) {
                    add(addon.streamAddonInstanceId(manifest.id) to
                        addon.displayTitle.ifBlank { manifest.name })
                }
            }
            PluginRepository.getEnabledScrapersForType(meta.type).forEach {
                add("plugin:${it.id}" to it.name)
            }
        }.distinctBy { it.first }
    }
    var provider by remember { mutableStateOf<String?>(null) }
    var providerExpanded by remember { mutableStateOf(false) }
    val eligible = available.filter { androidBatchEpisodeKey(it) in selected }
    val seasonEpisodes = available.filter { (it.season ?: 0) == season }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (movie) "Download for offline" else "Download episodes")
                Text(meta.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!movie) {
                    Text("Season", fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { number ->
                            FilterChip(
                                selected = season == number,
                                onClick = { season = number },
                                label = { Text(if (number == 0) "Specials" else "Season $number") },
                            )
                        }
                    }
                    TextButton(onClick = {
                        val keys = seasonEpisodes.map(::androidBatchEpisodeKey).toSet()
                        selected = if (keys.all { it in selected }) selected - keys else selected + keys
                    }, enabled = seasonEpisodes.isNotEmpty()) {
                        Text("Select / clear available episodes in this season")
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 270.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(allVideos.filter { (it.season ?: 0) == season },
                            key = ::androidBatchEpisodeKey) { episode ->
                            val key = androidBatchEpisodeKey(episode)
                            val selectable = available.any { androidBatchEpisodeKey(it) == key }
                            Row(Modifier.fillMaxWidth().clickable(enabled = selectable) {
                                selected = if (key in selected) selected - key else selected + key
                            }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = key in selected, enabled = selectable,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + key else selected - key
                                    })
                                Column(Modifier.weight(1f)) {
                                    Text("E${episode.episode ?: "?"} · ${episode.title.orEmpty()}",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!selectable) Text("Not released or already in Downloads",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Text(if (available.isEmpty()) "Already downloaded or unavailable"
                        else "This movie will be saved for playback without a connection.")
                }
                Text("Quality", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(DownloadQuality.Highest, "2160p", "1080p", "720p", DownloadQuality.Any)) { value ->
                        FilterChip(selected = quality == value, onClick = { quality = value },
                            label = { Text(value) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Provider", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Column {
                        TextButton(onClick = { providerExpanded = true }) {
                            Text(providers.firstOrNull { it.first == provider }?.second ?: "Any provider")
                        }
                        DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providers.forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    provider = id
                                    providerExpanded = false
                                })
                            }
                        }
                    }
                }
                Text("Android saves direct video files inside Starlit's offline storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = eligible.isNotEmpty(), onClick = {
                val sourceMeta = if (movie) meta.copy(videos = allVideos) else meta
                val count = DownloadsRepository.enqueueBatch(sourceMeta, eligible,
                    DownloadSourceSelection(provider, quality, "Direct files"))
                NuvioToastController.show("$count episode(s) queued in Downloads")
                onDismiss()
            }) { Text("Download (${eligible.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
