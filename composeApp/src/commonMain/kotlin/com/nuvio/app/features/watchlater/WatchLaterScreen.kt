package com.nuvio.app.features.watchlater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository

@Composable
internal fun WatchLaterScreen(onPosterClick: (MetaPreview) -> Unit, onPlay: (ContinueWatchingItem) -> Unit, modifier: Modifier = Modifier) {
    val saved by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val progress by remember { WatchProgressRepository.ensureLoaded(); WatchProgressRepository.uiState }.collectAsState()
    val profile by ProfileRepository.state.collectAsState()
    val grid = rememberLazyGridState()
    LaunchedEffect(profile.activeProfile) { WatchLaterRepository.resolveEpisodes() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("Watch Later", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Middle-click to save an episode. Right-click for title details; hover for its synopsis.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            if (saved.isEmpty()) Text("No saved episodes yet.")
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(saved, key = { it.watchLaterKey() }) { item ->
                        val playback = item.watchLaterPlayback(progress)
                        val preview = item.toMetaPreview()
                        val unresolved = item.type == "series" && item.watchLaterEpisode == null
                        EpisodeSynopsis(preview) {
                            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .watchLaterMiddleClick(preview)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).posterCardClickable(onClick = {
                                    if (unresolved) onPosterClick(preview) else onPlay(playback)
                                }, onLongClick = null)) {
                                    NuvioAsyncImage(model = playback.imageUrl, contentDescription = item.name,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (unresolved) "Choose an episode" else playback.subtitle.ifBlank { "Movie" }, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    val completed = item.watchLaterProgress(progress)?.isEffectivelyCompleted == true
                                    if (completed) Text("Watched", color = MaterialTheme.colorScheme.primary)
                                    else if (playback.progressFraction > 0f || playback.resumePositionMs > 0L) {
                                        LinearProgressIndicator(progress = { playback.progressFraction }, modifier = Modifier.fillMaxWidth())
                                        Text(if (playback.resumePositionMs > 0L) "${(playback.progressFraction * 100).toInt()}% · ${playback.resumePositionMs / 60_000} min watched"
                                            else "${(playback.progressFraction * 100).toInt()}% watched", style = MaterialTheme.typography.labelMedium)
                                    }
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NuvioCompactActionButton(
                                            label = if (unresolved) "Choose episode" else if (playback.resumePositionMs > 0L || playback.progressFraction > 0f) "Resume" else "Play",
                                            icon = if (unresolved) Icons.Default.PlaylistPlay else Icons.Default.PlayArrow,
                                            tone = NuvioCompactActionTone.Emphasized,
                                            onClick = { if (unresolved) onPosterClick(preview) else onPlay(playback) },
                                        )
                                        NuvioCompactActionButton(
                                            label = "Remove",
                                            accessibilityLabel = "Remove ${item.name} from Watch Later",
                                            icon = Icons.Default.DeleteOutline,
                                            tone = NuvioCompactActionTone.Danger,
                                            onClick = { runCatching { WatchLaterRepository.remove(item) }
                                                .onFailure { NuvioToastController.show("Couldn't remove: ${it.message}") } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                NuvioDesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}
