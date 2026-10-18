package com.nuvio.app.features.watchlater

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_got_it
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_resume
import nuvio.composeapp.generated.resources.detail_action_watched
import nuvio.composeapp.generated.resources.detail_minutes_left
import nuvio.composeapp.generated.resources.library_title
import nuvio.composeapp.generated.resources.watch_later
import nuvio.composeapp.generated.resources.watch_later_action_choose
import nuvio.composeapp.generated.resources.watch_later_choose_episode
import nuvio.composeapp.generated.resources.watch_later_empty_body
import nuvio.composeapp.generated.resources.watch_later_empty_title
import nuvio.composeapp.generated.resources.watch_later_in_progress
import nuvio.composeapp.generated.resources.watch_later_movie
import nuvio.composeapp.generated.resources.watch_later_remove_failed
import nuvio.composeapp.generated.resources.watch_later_remove_named
import nuvio.composeapp.generated.resources.watch_later_series
import nuvio.composeapp.generated.resources.watch_later_tip
import org.jetbrains.compose.resources.stringResource

/** The two desktop Library views. */
internal enum class LibraryView { Library, WatchLater }

/** Library | Watch Later switch shown under the Library title on desktop. */
@Composable
internal fun LibraryViewSwitcher(selected: LibraryView, onSelect: (LibraryView) -> Unit, modifier: Modifier = Modifier) {
    val saved by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    NuvioSegmentedControl(
        segments = listOf(
            NuvioSegment(LibraryView.Library, stringResource(Res.string.library_title)),
            NuvioSegment(LibraryView.WatchLater, stringResource(Res.string.watch_later), count = saved.size.takeIf { it > 0 }?.toString()),
        ),
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
internal fun WatchLaterScreen(
    onPosterClick: (MetaPreview) -> Unit,
    onPlay: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onShowLibrary: () -> Unit = {},
) {
    val saved by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val progress by remember { WatchProgressRepository.ensureLoaded(); WatchProgressRepository.uiState }.collectAsState()
    val profile by ProfileRepository.state.collectAsState()
    val grid = rememberLazyGridState()
    var tipDismissed by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(profile.activeProfile) { WatchLaterRepository.resolveEpisodes() }
    val removeFailed = stringResource(Res.string.watch_later_remove_failed, "%s")
    Column(modifier.fillMaxSize()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.library_title),
            modifier = Modifier.padding(horizontal = 16.dp),
            topPadding = topChromePadding,
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LibraryViewSwitcher(
                selected = LibraryView.WatchLater,
                onSelect = { if (it == LibraryView.Library) onShowLibrary() },
            )
            if (!tipDismissed && saved.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(NuvioTokens.Radius.lg))
                        .border(NuvioTokens.Border.thin, colors.borderSubtle, RoundedCornerShape(NuvioTokens.Radius.lg))
                        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(Res.string.watch_later_tip),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.action_got_it),
                        icon = null,
                        onClick = { tipDismissed = true },
                        tone = NuvioCompactActionTone.Secondary,
                        size = NuvioActionSize.Small,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (saved.isEmpty()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp).widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.watch_later_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    stringResource(Res.string.watch_later_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(290.dp),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(saved, key = { it.watchLaterKey() }) { item ->
                    val playback = item.watchLaterPlayback(progress)
                    val preview = item.toMetaPreview()
                    val unresolved = item.type == "series" && item.watchLaterEpisode == null
                    val completed = item.watchLaterProgress(progress)?.isEffectivelyCompleted == true
                    val inProgress = !completed && (playback.progressFraction > 0f || playback.resumePositionMs > 0L)
                    val open = { if (unresolved) onPosterClick(preview) else onPlay(playback) }
                    val episode = item.watchLaterEpisode
                    val code = localizedSeasonEpisodeCode(episode?.seasonNumber, episode?.episodeNumber)
                        ?: stringResource(if (item.type == "series") Res.string.watch_later_series else Res.string.watch_later_movie)
                    EpisodeSynopsis(preview) {
                        Column(
                            modifier = Modifier.watchLaterMiddleClick(preview),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
                                    .background(colors.surfaceCard)
                                    .posterCardClickable(onClick = open, onLongClick = null),
                            ) {
                                NuvioAsyncImage(
                                    model = playback.imageUrl,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    text = code.uppercase(),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(10.dp)
                                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(NuvioTokens.Radius.xs))
                                        .padding(horizontal = 7.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = Color.White,
                                    maxLines = 1,
                                )
                                if (inProgress) {
                                    NuvioProgressBar(
                                        progress = playback.progressFraction,
                                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                                        height = 4.dp,
                                        trackColor = Color.White.copy(alpha = 0.20f),
                                        fillColor = colors.accent,
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    when {
                                        unresolved -> stringResource(Res.string.watch_later_choose_episode)
                                        episode != null -> episode.title
                                        else -> item.name
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val status = when {
                                    completed -> stringResource(Res.string.detail_action_watched)
                                    inProgress && playback.durationMs > 0L -> stringResource(
                                        Res.string.detail_minutes_left,
                                        ((playback.durationMs - playback.resumePositionMs) / 60_000L).toInt().coerceAtLeast(1),
                                    )
                                    inProgress -> stringResource(Res.string.watch_later_in_progress)
                                    else -> null
                                }
                                status?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NuvioCompactActionButton(
                                    label = stringResource(
                                        when {
                                            unresolved -> Res.string.watch_later_action_choose
                                            inProgress -> Res.string.action_resume
                                            else -> Res.string.action_play
                                        },
                                    ),
                                    icon = if (unresolved) Icons.Default.PlaylistPlay else Icons.Default.PlayArrow,
                                    onClick = open,
                                    tone = NuvioCompactActionTone.Primary,
                                    size = NuvioActionSize.Small,
                                )
                                Spacer(Modifier.weight(1f))
                                NuvioIconActionButton(
                                    icon = Icons.Default.BookmarkRemove,
                                    contentDescription = stringResource(Res.string.watch_later_remove_named, item.name),
                                    onClick = {
                                        runCatching { WatchLaterRepository.remove(item) }
                                            .onFailure { NuvioToastController.show(removeFailed.replace("%s", it.message.orEmpty())) }
                                    },
                                    tone = NuvioCompactActionTone.Ghost,
                                    size = NuvioActionSize.Small,
                                )
                            }
                        }
                    }
                }
            }
            NuvioDesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
