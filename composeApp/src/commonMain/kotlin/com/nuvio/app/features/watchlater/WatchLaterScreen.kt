package com.nuvio.app.features.watchlater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository

@Composable
internal fun WatchLaterScreen(
    personalList: Boolean = false,
    onPosterClick: (MetaPreview) -> Unit,
    onPlay: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val tracked by remember { PersonalMediaRepository.ensureLoaded(); PersonalMediaRepository.items }.collectAsState()
    val progress by remember { WatchProgressRepository.ensureLoaded(); WatchProgressRepository.uiState }.collectAsState()
    val profile by ProfileRepository.state.collectAsState()
    var statusFilter by remember { mutableStateOf<PersonalMediaStatus?>(null) }
    var filterMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(profile.activeProfile, personalList) { if (!personalList) WatchLaterRepository.resolveEpisodes() }
    val visible = if (personalList) tracked.filter { statusFilter == null || it.status == statusFilter }.map { it.item } else saved
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (personalList) "My List" else "Watch Later", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (personalList) "Your statuses and ratings" else "Middle-click any episode to save it. Catalog posters save your next episode.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (personalList) Box {
                    TextButton(onClick = { filterMenu = true }) { Text(statusFilter?.label ?: "All statuses") }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        DropdownMenuItem(text = { Text("All statuses") }, onClick = { statusFilter = null; filterMenu = false })
                        PersonalMediaStatus.entries.forEach { status ->
                            DropdownMenuItem(text = { Text(status.label) }, onClick = { statusFilter = status; filterMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (visible.isEmpty()) Text(if (personalList) "Set a status or rating on a title's details page to add it here." else "No saved episodes yet.")
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visible, key = { if (personalList) "${it.type}|${it.id}" else it.watchLaterKey() }) { item ->
                        val playback = item.watchLaterPlayback(progress)
                        val unresolved = item.type == "series" && item.watchLaterEpisode == null
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                .watchLaterMiddleClick(item.toMetaPreview())
                                .posterCardClickable(onClick = {
                                    if (personalList || unresolved) onPosterClick(item.toMetaPreview()) else onPlay(playback)
                                }, onLongClick = null)) {
                                NuvioAsyncImage(model = playback.imageUrl, contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (personalList) {
                                    PersonalMediaControls(item.toMetaPreview(), showSaveEpisode = false)
                                } else {
                                    Text(if (unresolved) "Choose an episode" else playback.subtitle.ifBlank { "Movie" },
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    val completed = item.watchLaterProgress(progress)?.isEffectivelyCompleted == true
                                    if (completed) Text("Watched", color = MaterialTheme.colorScheme.primary)
                                    else if (playback.progressFraction > 0f || playback.resumePositionMs > 0L) {
                                        LinearProgressIndicator(progress = { playback.progressFraction }, modifier = Modifier.fillMaxWidth())
                                        Text(if (playback.resumePositionMs > 0L) "${(playback.progressFraction * 100).toInt()}% · ${playback.resumePositionMs / 60_000} min watched"
                                            else "${(playback.progressFraction * 100).toInt()}% watched",
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { if (unresolved) onPosterClick(item.toMetaPreview()) else onPlay(playback) }) {
                                            Text(if (unresolved) "Choose episode" else if (playback.resumePositionMs > 0L || playback.progressFraction > 0f) "Resume" else "Play")
                                        }
                                        TextButton(onClick = {
                                            runCatching { WatchLaterRepository.remove(item) }.onFailure { NuvioToastController.show("Couldn't remove: ${it.message}") }
                                        }) { Text("Remove") }
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
