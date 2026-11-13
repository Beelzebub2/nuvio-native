package com.nuvio.app.features.shuffle

import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioTag
import com.nuvio.app.core.ui.NuvioTagStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.rememberPlaybackAvailability
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeShuffleSheet(
    meta: MetaDetails,
    settings: EpisodeShuffleSettings,
    onSave: (EpisodeShuffleSettings) -> Boolean,
    watchedKeys: Set<String>,
    progressEntries: List<WatchProgressEntry>,
    blurUnwatchedEpisodes: Boolean,
    onDismiss: () -> Unit,
    onPlay: (MetaVideo) -> Unit,
    onPlayManually: ((MetaVideo) -> Unit)?,
    onStartFromBeginning: (MetaVideo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val availability = rememberPlaybackAvailability()
    val progress = remember(meta.id, progressEntries) { shuffleEpisodeProgress(meta.id, progressEntries) }
    val picker by produceState<RandomEpisodePicker?>(null, meta, watchedKeys, progress) {
        val updated = withContext(Dispatchers.Default) {
            RandomEpisodePicker(meta.id, meta.videos,
                watchedShuffleEpisodes(meta.id, meta.type, meta.videos, watchedKeys), progress)
        }
        updated.inheritHistoryFrom(value)
        value = updated
    }
    var includeWatched by remember { mutableStateOf(settings.includeWatched) }
    var selected by remember { mutableStateOf<MetaVideo?>(null) }
    var starting by remember { mutableStateOf(false) }
    val readyPicker = picker
    val preview = selected?.let { readyPicker?.find(it.id, includeWatched) }
    val allCount = readyPicker?.count(true) ?: 0
    val unwatchedCount = readyPicker?.count(false) ?: 0

    fun dismiss(after: () -> Unit = {}) {
        scope.launch {
            dismissNuvioBottomSheet(sheetState) { onDismiss(); after() }
        }
    }

    fun play(action: (MetaVideo) -> Unit) {
        val episode = preview ?: return
        if (starting) return
        starting = true
        if (onSave(EpisodeShuffleSettings(true, includeWatched))) dismiss { action(episode) } else starting = false
    }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!starting) dismiss() },
        sheetState = sheetState,
        fullHeight = true,
    ) {
        val tokens = MaterialTheme.nuvio
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 6.dp, bottom = nuvioSafeBottomPadding(20.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (preview != null) {
                    NuvioSquareIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.action_back),
                        onClick = { selected = null },
                        size = 36.dp,
                        enabled = !starting,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(Res.string.random_episode_title), style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary)
                    Text(meta.name, style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (preview == null) {
                Text(stringResource(Res.string.shuffle_choose_episodes), style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textSecondary)
                when {
                    readyPicker == null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvioLoadingIndicator(Modifier.size(18.dp), color = tokens.colors.accent, size = 18.dp)
                        Text(stringResource(Res.string.random_episode_loading), style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted)
                    }
                    allCount == 0 -> Text(stringResource(Res.string.random_episode_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    else -> {
                        NuvioGroup(containerColor = tokens.colors.surfaceCard) {
                            ShufflePoolRow(
                                icon = Icons.Rounded.VisibilityOff,
                                title = stringResource(Res.string.random_episode_unwatched),
                                description = stringResource(Res.string.shuffle_pool_episode_count, unwatchedCount),
                                // Nothing left to watch: the pool is empty until All episodes is chosen.
                                enabled = unwatchedCount > 0,
                                onClick = { includeWatched = false; selected = readyPicker.pick(false) },
                            )
                            NuvioGroupDivider()
                            ShufflePoolRow(
                                icon = Icons.Rounded.Shuffle,
                                title = stringResource(Res.string.random_episode_include_watched),
                                description = stringResource(Res.string.shuffle_pool_episode_count_all, allCount),
                                enabled = true,
                                onClick = { includeWatched = true; selected = readyPicker.pick(true) },
                            )
                        }
                        if (unwatchedCount == 0) Text(stringResource(Res.string.random_episode_caught_up),
                            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    }
                }
            } else {
                val watched = readyPicker?.isWatched(preview) == true
                val hidden = blurUnwatchedEpisodes && !watched
                val artworkShape = tokens.shapes.button
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(artworkShape)
                        .background(tokens.colors.surfaceCard)
                        .border(tokens.borders.thin, tokens.colors.borderSubtle, artworkShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hidden && !preview.thumbnail.isNullOrBlank()) {
                        AsyncImage(model = preview.thumbnail, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Shuffle,
                                contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(32.dp))
                            if (hidden) Text(stringResource(Res.string.shuffle_artwork_hidden),
                                style = MaterialTheme.nuvioTypeScale.bodySm, color = tokens.colors.textMuted)
                        }
                    }
                    NuvioTag(
                        text = stringResource(if (watched) Res.string.episodes_cd_watched else Res.string.shuffle_unwatched),
                        style = NuvioTagStyle.Overlay,
                        height = 26.dp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NuvioSectionLabel(stringResource(Res.string.shuffle_preview_title).uppercase())
                        NuvioTag(
                            text = stringResource(
                                if (includeWatched) Res.string.random_episode_include_watched else Res.string.random_episode_unwatched,
                            ),
                            style = NuvioTagStyle.Accent,
                        )
                    }
                    localizedSeasonEpisodeCode(preview.season, preview.episode)?.takeIf { it.isNotBlank() }?.let { code ->
                        Text(code, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    }
                    Text(preview.title, style = MaterialTheme.nuvioTypeScale.titleMd, color = tokens.colors.textPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    preview.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            color = tokens.colors.textMuted)
                    }
                }
                val resume = progress[preview.season to preview.episode]?.let {
                    !it.isEffectivelyCompleted && (it.lastPositionMs > 0 || it.progressFraction > 0)
                } == true
                val canPlay = availability.canPlay(meta.type, preview.id, meta.id, preview.season, preview.episode)
                val canShuffleAgain = (readyPicker?.count(includeWatched) ?: 0) > 1
                val canPlayManually = onPlayManually != null && availability.canStream(meta.type, preview.id)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NuvioPrimaryButton(
                        text = stringResource(when {
                            starting -> Res.string.shuffle_starting
                            !canPlay -> Res.string.playback_unavailable
                            resume -> Res.string.action_resume
                            else -> Res.string.shuffle_play_episode
                        }),
                        enabled = !starting && canPlay,
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { play(onPlay) },
                    )
                    if (canShuffleAgain || canPlayManually) {
                        DialogButtons(Modifier.padding(top = 0.dp)) {
                            if (canPlayManually) {
                                DialogButton(
                                    text = stringResource(Res.string.play_manually),
                                    onClick = { onPlayManually?.let { play(it) } },
                                    enabled = !starting,
                                )
                            }
                            if (canShuffleAgain) {
                                DialogButton(
                                    text = stringResource(Res.string.shuffle_again),
                                    onClick = { selected = readyPicker?.pick(includeWatched) },
                                    enabled = !starting,
                                )
                            }
                        }
                    }
                    if (resume) {
                        DialogButton(
                            text = stringResource(Res.string.cw_action_start_from_beginning),
                            onClick = { play(onStartFromBeginning) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !starting && canPlay,
                        )
                    }
                    if (!canShuffleAgain) {
                        Text(stringResource(Res.string.shuffle_only_episode), style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = tokens.colors.textMuted)
                    }
                }
            }
        }
    }
}

/** One of the two episode pools on the first step: an icon chip, the pool name and its size. */
@Composable
private fun ShufflePoolRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NuvioGroupRow(
        title = title,
        description = description,
        onClick = onClick,
        enabled = enabled,
        leading = { NuvioIconChip(icon) },
        trailing = {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
internal fun rememberShuffleSave(
    contentId: String,
    profileId: Int,
    current: EpisodeShuffleSettings,
): (EpisodeShuffleSettings) -> Boolean {
    val off = stringResource(Res.string.shuffle_disabled)
    val all = stringResource(Res.string.shuffle_enabled_all)
    val unwatched = stringResource(Res.string.shuffle_enabled_unwatched)
    val failed = stringResource(Res.string.shuffle_save_failed)
    return remember(contentId, profileId, current, off, all, unwatched, failed) {
        { settings ->
            val saved = EpisodeShuffleRepository.save(contentId, settings, profileId)
            val changed = current.enabled != settings.enabled ||
                (settings.enabled && current.includeWatched != settings.includeWatched)
            when {
                !saved -> NuvioToastController.show(failed)
                changed -> NuvioToastController.show(when {
                    !settings.enabled -> off
                    settings.includeWatched -> all
                    else -> unwatched
                })
            }
            saved
        }
    }
}
