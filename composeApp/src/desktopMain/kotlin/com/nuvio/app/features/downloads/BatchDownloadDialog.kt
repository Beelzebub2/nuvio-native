package com.nuvio.app.features.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.SurfaceEdge
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val BatchDownloadEpisodeListTag = "batch-download-episodes"

private enum class EpisodeDownloadState { Available, Failed, Queued, Downloading, Paused, Downloaded, NotAired, Unavailable }

private data class BatchEpisode(val video: MetaVideo, val key: String, val state: EpisodeDownloadState) {
    // Failed episodes are queued again; everything already in Downloads is skipped.
    val selectable: Boolean get() = state == EpisodeDownloadState.Available || state == EpisodeDownloadState.Failed
    val season: Int get() = video.season ?: 0
}

private val sourceTypes = listOf("Any", "Direct files", "Torrents")

@Composable
internal fun BatchDownloadDialog(meta: MetaDetails, initialSeason: Int, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val movie = meta.type.equals("movie", true) && meta.videos.isEmpty()
    val videos = remember(meta) { if (movie) listOf(MetaVideo(meta.defaultVideoId ?: meta.id, meta.name)) else meta.videos }
    val today = remember { CurrentDateProvider.todayIsoDate() }
    val downloads by DownloadsRepository.uiState.collectAsState()
    val episodes = remember(videos, downloads, today) {
        videos.map { BatchEpisode(it, downloadEpisodeKey(it), episodeDownloadState(meta, it, downloads.items, today)) }
    }
    val seasons = remember(episodes) { episodes.groupBy { it.season }.toSortedMap() }
    var focusedSeason by remember(meta.id) {
        mutableStateOf(initialSeason.takeIf { it in seasons } ?: seasons.keys.firstOrNull() ?: 0)
    }
    var selected by remember(meta.id, initialSeason) {
        mutableStateOf(episodes.filter { it.selectable && (movie || it.season == initialSeason) }.map { it.key }.toSet())
    }
    val chosen = episodes.filter { it.selectable && it.key in selected }

    val remembered = remember { DownloadDesktopFeatures.lastSelection() }
    val providers = remember(meta.id) { downloadProviders(meta, videos) }
    var quality by remember { mutableStateOf(remembered.quality.takeIf { it == DownloadQuality.Highest || it == DownloadQuality.Any || it in DownloadQuality.fixed } ?: DownloadQuality.Highest) }
    var transport by remember { mutableStateOf(remembered.transport.takeIf { it in sourceTypes } ?: "Any") }
    var provider by remember { mutableStateOf(remembered.providerId?.takeIf { id -> providers.any { it.first == id } }) }
    val p2pEnabled = remember { P2pSettingsRepository.ensureLoaded(); P2pSettingsRepository.uiState.value.p2pEnabled }

    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(DownloadDesktopFeatures.directory().orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    val chooseFolder: () -> Unit = {
        picking = true
        scope.launch {
            try {
                chooseDownloadFolder(folder)?.let { picked -> withContext(Dispatchers.IO) { DownloadDesktopFeatures.setDirectory(picked) } }
                folder = DownloadDesktopFeatures.directory().orEmpty()
                folderError = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                folderError = e.message
            } finally {
                picking = false
            }
        }
    }
    val confirm: () -> Unit = {
        val selection = DownloadSourceSelection(provider, quality, transport)
        DownloadDesktopFeatures.saveSelection(selection)
        val sourceMeta = if (movie) meta.copy(videos = videos) else meta
        val count = DownloadsRepository.enqueueBatch(sourceMeta,
            batchDownloadEpisodes(sourceMeta, chosen.map { it.key }.toSet(), today), selection)
        NuvioToastController.show(if (count == 0) "Already in Downloads. Manage them in Library > Downloads."
            else "${downloadCountLabel(count, movie)} queued. Manage them in Library > Downloads.")
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = if (movie) 760.dp else 960.dp)
                .heightIn(max = if (movie) 600.dp else 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogHeader(meta, movie, episodes, onDismiss)
                HorizontalDivider(color = tokens.colors.borderSubtle)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 720.dp
                    val selectionPane: @Composable (Modifier) -> Unit = { modifier ->
                        if (movie) {
                            MoviePane(meta, episodes.first(), modifier)
                        } else {
                            EpisodePane(
                                meta = meta,
                                seasons = seasons,
                                focusedSeason = focusedSeason,
                                selected = selected,
                                onFocusSeason = { focusedSeason = it },
                                onSelectedChange = { selected = it },
                                modifier = modifier,
                            )
                        }
                    }
                    val optionsPane: @Composable (Modifier) -> Unit = { modifier ->
                        OptionsPane(
                            quality = quality, onQuality = { quality = it },
                            transport = transport, onTransport = { transport = it },
                            providers = providers, provider = provider, onProvider = { provider = it },
                            p2pEnabled = p2pEnabled,
                            folder = folder, picking = picking, folderError = folderError, onChooseFolder = chooseFolder,
                            modifier = modifier,
                        )
                    }
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            selectionPane(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider(color = tokens.colors.borderSubtle)
                            optionsPane(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            selectionPane(Modifier.fillMaxWidth().height(if (movie) 160.dp else 340.dp))
                            HorizontalDivider(color = tokens.colors.borderSubtle)
                            optionsPane(Modifier.fillMaxWidth())
                        }
                    }
                }
                HorizontalDivider(color = tokens.colors.borderSubtle)
                DialogFooter(movie, chosen, picking, onDismiss, confirm)
            }
        }
    }
}

@Composable
private fun DialogHeader(meta: MetaDetails, movie: Boolean, episodes: List<BatchEpisode>, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (meta.poster ?: meta.background)?.let { art ->
            NuvioAsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)).background(tokens.colors.surfaceCard),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (movie) "Download for offline" else "Download episodes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val seasonCount = episodes.map { it.season }.distinct().size
            val aired = episodes.count { it.state != EpisodeDownloadState.NotAired && it.state != EpisodeDownloadState.Unavailable }
            val downloaded = episodes.count { it.state == EpisodeDownloadState.Downloaded }
            val facts = if (movie) listOf("Plays from disk once it finishes, even without a connection")
                else listOfNotNull(
                    meta.name,
                    "$seasonCount ${if (seasonCount == 1) "season" else "seasons"}",
                    "$aired ${if (aired == 1) "episode" else "episodes"} aired",
                    downloaded.takeIf { it > 0 }?.let { "$it downloaded" },
                )
            Text(
                text = facts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun EpisodePane(
    meta: MetaDetails,
    seasons: Map<Int, List<BatchEpisode>>,
    focusedSeason: Int,
    selected: Set<String>,
    onFocusSeason: (Int) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val allSelectable = seasons.values.flatten().filter { it.selectable }.map { it.key }.toSet()
    val seasonEpisodes = seasons[focusedSeason].orEmpty()
    val seasonKeys = seasonEpisodes.filter { it.selectable }.map { it.key }.toSet()
    val seasonState = when {
        seasonKeys.isEmpty() || seasonKeys.none { it in selected } -> ToggleableState.Off
        seasonKeys.all { it in selected } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Column(modifier.padding(top = 16.dp)) {
        if (seasons.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEach { (season, list) ->
                    val keys = list.filter { it.selectable }.map { it.key }
                    SeasonChip(
                        label = seasonLabel(season),
                        selectedCount = keys.count { it in selected },
                        selectableCount = keys.size,
                        episodeCount = list.size,
                        focused = season == focusedSeason,
                        onClick = { onFocusSeason(season) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(tokens.shapes.compactCard)
                    .triStateToggleable(
                        state = seasonState,
                        enabled = seasonKeys.isNotEmpty(),
                        role = Role.Checkbox,
                        onClick = { onSelectedChange(if (seasonState == ToggleableState.On) selected - seasonKeys else selected + seasonKeys) },
                    )
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(state = seasonState, onClick = null, enabled = seasonKeys.isNotEmpty(), colors = downloadCheckboxColors())
                Column {
                    Text(seasonLabel(focusedSeason), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${seasonEpisodes.size} episodes  ·  ${seasonKeys.count { it in selected }} of ${seasonKeys.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            if (allSelectable.any { it !in selected }) {
                TextButton(onClick = { onSelectedChange(selected + allSelectable) }, enabled = allSelectable.isNotEmpty()) {
                    Text("Select all seasons", color = tokens.colors.textPrimary)
                }
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { onSelectedChange(emptySet()) }) {
                    Text("Clear all", color = tokens.colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(BatchDownloadEpisodeListTag),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(seasonEpisodes, key = { it.key }) { episode ->
                EpisodeRow(
                    episode = episode,
                    fallbackArt = meta.background ?: meta.poster,
                    checked = episode.selectable && episode.key in selected,
                    onToggle = { onSelectedChange(if (episode.key in selected) selected - episode.key else selected + episode.key) },
                )
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selectedCount: Int, selectableCount: Int, episodeCount: Int, focused: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(if (focused) tokens.colors.textPrimary.copy(alpha = 0.14f) else tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .border(1.dp, if (focused) tokens.colors.textPrimary.copy(alpha = 0.32f) else Color.Transparent, tokens.shapes.chip)
            .selectable(selected = focused, role = Role.Tab, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) tokens.colors.textPrimary else tokens.colors.textSecondary,
        )
        when {
            selectableCount > 0 && selectedCount == selectableCount -> Icon(
                Icons.Rounded.CheckCircle, contentDescription = "All selected",
                tint = tokens.colors.accent, modifier = Modifier.size(16.dp),
            )
            selectedCount > 0 -> CountBadge("$selectedCount/$selectableCount")
            else -> Text("$episodeCount", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun CountBadge(text: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        modifier = Modifier.clip(tokens.shapes.chip).background(tokens.colors.accent).padding(horizontal = 7.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tokens.colors.onAccent,
    )
}

@Composable
private fun EpisodeRow(episode: BatchEpisode, fallbackArt: String?, checked: Boolean, onToggle: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val enabled = episode.selectable
    val video = episode.video
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(
                when {
                    hovered && enabled -> tokens.colors.overlayHover
                    checked -> tokens.colors.textPrimary.copy(alpha = 0.035f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = downloadCheckboxColors())
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
                .alpha(if (enabled || episode.state == EpisodeDownloadState.Downloaded) 1f else 0.45f),
        ) {
            (video.thumbnail ?: fallbackArt)?.let { art ->
                NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = video.title.ifBlank { video.episode?.let { "Episode $it" } ?: "Episode" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled || episode.state == EpisodeDownloadState.Downloaded) tokens.colors.textPrimary else tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(
                video.season?.let { seasonLabel(it).replace("Season ", "S") },
                video.episode?.let { "E$it" },
                video.released?.takeIf { it.isNotBlank() }?.let { runCatching { formatReleaseDateForDisplay(it) }.getOrNull() },
                video.runtime?.takeIf { it > 0 }?.let { "$it min" },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        EpisodeStatusBadge(episode)
    }
}

@Composable
private fun EpisodeStatusBadge(episode: BatchEpisode) {
    val tokens = MaterialTheme.nuvio
    val (icon, label, color) = when (episode.state) {
        EpisodeDownloadState.Available -> return
        EpisodeDownloadState.Downloaded -> Triple(Icons.Rounded.CheckCircle, "Downloaded", tokens.colors.success)
        EpisodeDownloadState.Downloading -> Triple(Icons.Rounded.Download, "Downloading", tokens.colors.info)
        EpisodeDownloadState.Queued -> Triple(Icons.Rounded.Schedule, "In queue", tokens.colors.info)
        EpisodeDownloadState.Paused -> Triple(Icons.Rounded.Pause, "Paused", tokens.colors.textSecondary)
        EpisodeDownloadState.Failed -> Triple(Icons.Rounded.ErrorOutline, "Failed · retry", tokens.colors.warning)
        EpisodeDownloadState.NotAired -> Triple(
            Icons.Rounded.Schedule,
            episode.video.released?.takeIf { it.isNotBlank() }
                ?.let { runCatching { "Airs ${formatReleaseDateForDisplay(it)}" }.getOrNull() } ?: "Not aired",
            tokens.colors.textMuted,
        )
        EpisodeDownloadState.Unavailable -> Triple(Icons.Rounded.ErrorOutline, "Unavailable", tokens.colors.textMuted)
    }
    StatusPill(icon, label, color)
}

@Composable
private fun StatusPill(icon: ImageVector, label: String, color: Color) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.clip(tokens.shapes.chip).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun MoviePane(meta: MetaDetails, movie: BatchEpisode, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(8.dp)).background(tokens.colors.textPrimary.copy(alpha = 0.06f))) {
                (meta.background ?: meta.poster)?.let { art ->
                    NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(meta.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(meta.releaseInfo, meta.runtime).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                }
                if (movie.state == EpisodeDownloadState.Available) {
                    StatusPill(Icons.Rounded.Download, "Ready to download", tokens.colors.textSecondary)
                } else {
                    EpisodeStatusBadge(movie)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsPane(
    quality: String,
    onQuality: (String) -> Unit,
    transport: String,
    onTransport: (String) -> Unit,
    providers: List<Pair<String?, String>>,
    provider: String?,
    onProvider: (String?) -> Unit,
    p2pEnabled: Boolean,
    folder: String,
    picking: Boolean,
    folderError: String?,
    onChooseFolder: () -> Unit,
    modifier: Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        OptionSection("Quality", caption = when (quality) {
            DownloadQuality.Highest -> "Checks every source for the highest resolution, preferring remuxes and larger, less compressed files at that resolution."
            DownloadQuality.Any -> "Takes the first file found, so downloads start sooner."
            else -> "Only files labelled $quality. Episodes without one fail and can be retried."
        }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Highest available", quality == DownloadQuality.Highest, { onQuality(DownloadQuality.Highest) }, Icons.Rounded.AutoAwesome)
                DownloadQuality.fixed.forEach { option -> ChoiceChip(option, quality == option, { onQuality(option) }) }
                ChoiceChip("Any", quality == DownloadQuality.Any, { onQuality(DownloadQuality.Any) })
            }
        }
        OptionSection("Source type") {
            SegmentedChoice(sourceTypes, transport, onTransport)
            if (transport != "Direct files" && !p2pEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = tokens.colors.warning, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                    Text(
                        text = if (transport == "Torrents") "P2P is off. Turn it on in Settings to download torrents."
                            else "P2P is off, so torrents are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.warning,
                    )
                }
            }
        }
        OptionSection("Provider") {
            ProviderField(providers, provider, onProvider)
        }
        OptionSection("Save to") {
            FolderCard(folder, picking, onChooseFolder)
            folderError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Info, null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(
                text = "Downloads run one at a time in the background; follow them in Library → Downloads. " +
                    "Streaming-only links and torrent packs without an exact episode file are skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun OptionSection(title: String, caption: String? = null, content: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = tokens.colors.textMuted,
        )
        content()
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val tokens = MaterialTheme.nuvio
    val content = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(if (selected) tokens.colors.accent else tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(14.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
private fun SegmentedChoice(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) tokens.colors.textPrimary.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProviderField(providers: List<Pair<String?, String>>, provider: String?, onProvider: (String?) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .border(1.dp, tokens.colors.borderSubtle, tokens.shapes.compactCard)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = providers.firstOrNull { it.first == provider }?.second ?: "All enabled sources",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp),
            containerColor = tokens.colors.surfacePopover,
            shape = tokens.shapes.compactCard,
        ) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        if (id == provider) Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
                    },
                    onClick = { onProvider(id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun FolderCard(folder: String, picking: Boolean, onChange: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val file = File(folder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .border(1.dp, tokens.colors.borderSubtle, tokens.shapes.compactCard)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name.ifBlank { folder }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            file.parent?.let {
                Text(compactFolderPath(it), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onChange, enabled = !picking) {
            Text(if (picking) "Choosing…" else "Change", color = tokens.colors.textPrimary)
        }
    }
}

@Composable
private fun DialogFooter(movie: Boolean, chosen: List<BatchEpisode>, picking: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!movie) {
                Text(
                    text = if (chosen.isEmpty()) "No episodes selected" else "${downloadCountLabel(chosen.size, false)} selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                selectedSeasonsSummary(chosen)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DialogButton("Cancel", onDismiss, modifier = Modifier.widthIn(min = 112.dp))
        DialogButton(
            text = when {
                movie -> if (chosen.isEmpty()) "Already in Downloads" else "Download movie"
                chosen.isEmpty() -> "Download"
                else -> "Download ${downloadCountLabel(chosen.size, false)}"
            },
            onClick = onConfirm,
            modifier = Modifier.widthIn(min = 180.dp),
            style = DialogButtonStyle.Primary,
            enabled = chosen.isNotEmpty() && !picking,
        )
    }
}

@Composable
private fun downloadCheckboxColors(): CheckboxColors {
    val tokens = MaterialTheme.nuvio
    return CheckboxDefaults.colors(
        checkedColor = tokens.colors.accent,
        uncheckedColor = tokens.colors.textMuted,
        checkmarkColor = tokens.colors.onAccent,
        disabledCheckedColor = tokens.colors.textDisabled,
        disabledUncheckedColor = tokens.colors.textDisabled,
        disabledIndeterminateColor = tokens.colors.textDisabled,
    )
}

private fun downloadProviders(meta: MetaDetails, videos: List<MetaVideo>): List<Pair<String?, String>> =
    listOf<Pair<String?, String>>(null to "All enabled sources") +
        AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            addon.manifest?.takeIf { manifest -> videos.any { manifest.supportsStream(meta.type, it.id) } }
                ?.let { addon.streamAddonInstanceId(it.id) to addon.displayTitle }
        } +
        PluginRepository.getEnabledScrapersForType(meta.type).map { "plugin:${it.id}" to it.name }

private fun episodeDownloadState(meta: MetaDetails, video: MetaVideo, items: List<DownloadItem>, today: String): EpisodeDownloadState {
    val existing = items.firstOrNull { item ->
        item.parentMetaId == meta.id.trim() && item.parentMetaType == meta.type &&
            if (video.season != null || video.episode != null) {
                item.seasonNumber == video.season && item.episodeNumber == video.episode
            } else item.videoId == video.id
    }
    return when (existing?.status) {
        DownloadStatus.Completed -> EpisodeDownloadState.Downloaded
        DownloadStatus.Downloading -> if (existing.phase == null || existing.phase == "Queued") EpisodeDownloadState.Queued else EpisodeDownloadState.Downloading
        DownloadStatus.Paused -> EpisodeDownloadState.Paused
        else -> when {
            !video.available || video.id.isBlank() -> EpisodeDownloadState.Unavailable
            !video.isReleasedBy(today) -> EpisodeDownloadState.NotAired
            existing?.status == DownloadStatus.Failed -> EpisodeDownloadState.Failed
            else -> EpisodeDownloadState.Available
        }
    }
}

/** Keeps the drive and the nearest folders, which tell paths apart better than the middle does. */
internal fun compactFolderPath(path: String, maxLength: Int = 34): String {
    if (path.length <= maxLength) return path
    val separator = if ('\\' in path) '\\' else '/'
    val parts = path.split(separator).filter { it.isNotEmpty() }
    if (parts.size < 3) return path
    val head = if (path.startsWith(separator)) "$separator${parts.first()}" else parts.first()
    val tail = parts.takeLast(2).joinToString(separator.toString())
    return "$head$separator…$separator$tail"
}

private fun seasonLabel(season: Int): String = if (season == 0) "Specials" else "Season $season"

private fun selectedSeasonsSummary(chosen: List<BatchEpisode>): String? {
    val seasons = chosen.map { it.season }.distinct().sorted()
    if (seasons.isEmpty()) return null
    val specials = 0 in seasons
    val numbered = seasons.filter { it != 0 }
    val parts = listOfNotNull(
        when (numbered.size) {
            0 -> null
            1 -> "Season ${numbered.single()}"
            else -> if (numbered.last() - numbered.first() == numbered.size - 1) "Seasons ${numbered.first()}–${numbered.last()}"
                else "Seasons " + numbered.joinToString(", ")
        },
        "Specials".takeIf { specials },
    )
    return parts.joinToString(" and ")
}

internal fun downloadCountLabel(count: Int, movie: Boolean): String = when {
    movie -> if (count == 1) "Movie" else "$count movies"
    count == 1 -> "1 episode"
    else -> "$count episodes"
}
