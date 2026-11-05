package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogCheckboxRow
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogSectionLabel
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
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

@OptIn(ExperimentalLayoutApi::class)
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
    DialogSurface(
        onDismissRequest = onDismiss,
        title = if (movie) "Download for offline" else "Download episodes",
        message = meta.name,
    ) {
        if (!movie) {
            DialogSectionLabel("Season")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { number ->
                    Chip(
                        label = if (number == 0) "Specials" else "Season $number",
                        selected = season == number,
                        onClick = { season = number },
                    )
                }
            }
            val seasonKeys = seasonEpisodes.map(::androidBatchEpisodeKey).toSet()
            val allSeasonSelected = seasonKeys.isNotEmpty() && seasonKeys.all { it in selected }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DialogSectionLabel("Episodes", Modifier.weight(1f))
                NuvioCompactActionButton(
                    label = if (allSeasonSelected) "Clear season" else "Select all available",
                    icon = null,
                    size = NuvioActionSize.Small,
                    tone = NuvioCompactActionTone.Ghost,
                    enabled = seasonKeys.isNotEmpty(),
                    onClick = { selected = if (allSeasonSelected) selected - seasonKeys else selected + seasonKeys },
                )
            }
            // The episode list scrolls on its own so the options and buttons stay in view.
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(allVideos.filter { (it.season ?: 0) == season }, key = ::androidBatchEpisodeKey) { episode ->
                    val key = androidBatchEpisodeKey(episode)
                    val selectable = available.any { androidBatchEpisodeKey(it) == key }
                    DialogCheckboxRow(
                        text = "E${episode.episode ?: "?"} · ${episode.title.orEmpty()}",
                        description = if (selectable) null else "Not released or already in Downloads",
                        checked = key in selected,
                        enabled = selectable,
                        onCheckedChange = { checked -> selected = if (checked) selected + key else selected - key },
                    )
                }
            }
        } else {
            DialogHint(
                if (available.isEmpty()) "Already downloaded or unavailable"
                else "This movie will be saved for playback without a connection.",
            )
        }
        DialogSectionLabel("Quality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DownloadQuality.Highest, "2160p", "1080p", "720p", DownloadQuality.Any).forEach { value ->
                Chip(label = value, selected = quality == value, onClick = { quality = value })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogSectionLabel("Provider", Modifier.weight(1f))
            Box {
                NuvioCompactActionButton(
                    label = providers.firstOrNull { it.first == provider }?.second ?: "Any provider",
                    icon = null,
                    trailingIcon = Icons.Rounded.ExpandMore,
                    size = NuvioActionSize.Small,
                    onClick = { providerExpanded = true },
                )
                Menu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    providers.forEach { (id, label) ->
                        MenuItem(
                            text = label,
                            selected = id == provider,
                            onClick = {
                                provider = id
                                providerExpanded = false
                            },
                        )
                    }
                }
            }
        }
        DialogHint("Android saves direct video files inside Starlit's offline storage.")
        DialogButtons {
            DialogButton(text = "Cancel", onClick = onDismiss)
            DialogButton(
                text = "Download (${eligible.size})",
                style = DialogButtonStyle.Primary,
                enabled = eligible.isNotEmpty(),
                onClick = {
                    val sourceMeta = if (movie) meta.copy(videos = allVideos) else meta
                    val count = DownloadsRepository.enqueueBatch(sourceMeta, eligible,
                        DownloadSourceSelection(provider, quality, "Direct files"))
                    NuvioToastController.show("$count episode(s) queued in Downloads")
                    onDismiss()
                },
            )
        }
    }
}
