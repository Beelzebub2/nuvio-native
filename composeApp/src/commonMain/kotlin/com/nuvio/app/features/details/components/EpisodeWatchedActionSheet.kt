package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.ui.geometry.Offset
import com.nuvio.app.core.ui.NuvioContextMenu
import com.nuvio.app.core.ui.NuvioContextMenuEntry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAddCheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeWatchedActionSheet(
    episode: MetaVideo,
    seasonLabel: String,
    isEpisodeWatched: Boolean,
    canMarkPreviousEpisodes: Boolean,
    arePreviousEpisodesWatched: Boolean,
    isSeasonWatched: Boolean,
    onDismiss: () -> Unit,
    onToggleWatched: () -> Unit,
    onTogglePreviousWatched: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    showPlayManually: Boolean = false,
    onPlayManually: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            EpisodeActionSheetHeader(
                episode = episode,
                seasonLabel = seasonLabel,
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.CheckCircle,
                title = if (isEpisodeWatched) {
                    stringResource(Res.string.episode_mark_unwatched)
                } else {
                    stringResource(Res.string.episode_mark_watched)
                },
                onClick = {
                    onToggleWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (canMarkPreviousEpisodes) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.DoneAll,
                    title = if (arePreviousEpisodesWatched) {
                        stringResource(Res.string.episode_mark_previous_unwatched)
                    } else {
                        stringResource(Res.string.episode_mark_previous_watched)
                    },
                    onClick = {
                        onTogglePreviousWatched()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            NuvioBottomSheetActionRow(
                icon = Icons.Default.PlaylistAddCheckCircle,
                title = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onClick = {
                    onToggleSeasonWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (showPlayManually && onPlayManually != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(Res.string.play_manually),
                    onClick = {
                        onPlayManually()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeasonWatchedActionSheet(
    seasonLabel: String,
    isSeasonWatched: Boolean,
    canMarkPreviousSeasons: Boolean,
    onDismiss: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    onMarkPreviousSeasonsWatched: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = seasonLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.PlaylistAddCheckCircle,
                title = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onClick = {
                    onToggleSeasonWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (canMarkPreviousSeasons) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.DoneAll,
                    title = stringResource(Res.string.episode_mark_previous_seasons_watched),
                    onClick = {
                        onMarkPreviousSeasonsWatched()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeActionSheetHeader(
    episode: MetaVideo,
    seasonLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                localizedSeasonEpisodeCode(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                )?.let {
                    append(it)
                    append(" • ")
                }
                append(seasonLabel)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Desktop counterpart of [EpisodeWatchedActionSheet], opened at the pointer or under ⋯. */
@Composable
fun EpisodeContextMenu(
    episode: MetaVideo,
    anchorInRoot: Offset,
    seasonLabel: String,
    isEpisodeWatched: Boolean,
    canMarkPreviousEpisodes: Boolean,
    arePreviousEpisodesWatched: Boolean,
    isSeasonWatched: Boolean,
    isSavedForLater: Boolean?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onTogglePreviousWatched: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onPlayManually: (() -> Unit)? = null,
) {
    val entries = buildList {
        add(NuvioContextMenuEntry.Action(stringResource(Res.string.action_play), onPlay, Icons.Filled.PlayArrow))
        onPlayManually?.let {
            add(NuvioContextMenuEntry.Action(stringResource(Res.string.play_manually), it, Icons.Filled.Layers))
        }
        add(NuvioContextMenuEntry.Divider)
        add(
            NuvioContextMenuEntry.Action(
                label = stringResource(if (isEpisodeWatched) Res.string.episode_mark_unwatched else Res.string.episode_mark_watched),
                onClick = onToggleWatched,
                icon = Icons.Filled.CheckCircle,
            ),
        )
        if (canMarkPreviousEpisodes) {
            add(
                NuvioContextMenuEntry.Action(
                    label = stringResource(
                        if (arePreviousEpisodesWatched) Res.string.episode_mark_previous_unwatched else Res.string.episode_mark_previous_watched,
                    ),
                    onClick = onTogglePreviousWatched,
                    icon = Icons.Filled.DoneAll,
                ),
            )
        }
        add(
            NuvioContextMenuEntry.Action(
                label = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onClick = onToggleSeasonWatched,
                icon = Icons.Filled.PlaylistAddCheckCircle,
            ),
        )
        isSavedForLater?.let { saved ->
            add(NuvioContextMenuEntry.Divider)
            add(
                NuvioContextMenuEntry.Action(
                    label = stringResource(if (saved) Res.string.watch_later_remove else Res.string.watch_later_save),
                    onClick = onToggleWatchLater,
                    icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                ),
            )
        }
    }
    NuvioContextMenu(
        anchorInRoot = anchorInRoot,
        entries = entries,
        onDismiss = onDismiss,
        headerCode = localizedSeasonEpisodeCode(seasonNumber = episode.season, episodeNumber = episode.episode),
        headerTitle = episode.title,
    )
}
