package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException

/**
 * Optional desktop navigation destination. Uses the user's own MAL connection and fetches
 * only the requested list page; opening an entry opens its canonical MAL details page.
 */
@Composable
internal actual fun MyAnimeListTabContent(topChromePadding: Dp?) {
    val connection = MyAnimeListRepository.state.collectAsState().value
    val profile = com.nuvio.app.features.profiles.ProfileRepository.state.collectAsState().value
    LaunchedEffect(profile.activeProfile) { MyAnimeListRepository.ensureLoaded() }

    val colors = MaterialTheme.nuvio.colors
    var selectedStatus by remember { mutableStateOf<MalStatus?>(null) }
    var refreshId by remember { mutableIntStateOf(0) }
    val dataKey = listOf(connection.profileId, connection.revision, selectedStatus, refreshId)
    val entries = remember(dataKey) { mutableStateListOf<MalAnimeListEntry>() }
    var requestedOffset by remember(dataKey) { mutableIntStateOf(0) }
    var nextOffset by remember(dataKey) { mutableStateOf<Int?>(null) }
    var loading by remember(dataKey) { mutableStateOf(false) }
    var error by remember(dataKey) { mutableStateOf<String?>(null) }
    var retryId by remember(dataKey) { mutableIntStateOf(0) }

    LaunchedEffect(connection.profileId, connection.revision, connection.connected, selectedStatus, refreshId, requestedOffset, retryId) {
        if (!connection.connected || connection.busy) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = MyAnimeListRepository.animeList(selectedStatus, requestedOffset)
            if (requestedOffset == 0) entries.clear()
            entries.addAll(page.entries)
            nextOffset = page.nextOffset
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load your MyAnimeList entries."
        } finally {
            loading = false
        }
    }

    val urlHandler = LocalUriHandler.current
    Box(
        Modifier.fillMaxSize().background(colors.background)
            .padding(top = topChromePadding ?: 0.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 1030.dp),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("MyAnimeList", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (connection.username.isBlank() || connection.username == "Connected")
                                "Your anime, synced from MyAnimeList"
                            else "Your anime list · ${connection.username}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                    OutlinedButton(onClick = { refreshId++ }, enabled = connection.connected && !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Refresh")
                    }
                }
            }
            item(key = "filters") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val filters = listOf<MalStatus?>(null) + MalStatus.entries
                    items(filters) { status ->
                        FilterChip(
                            selected = selectedStatus == status,
                            onClick = { selectedStatus = status },
                            label = { Text(status?.label ?: "All anime") },
                        )
                    }
                }
            }

            if (!connection.connected) {
                item(key = "disconnected") {
                    Text(
                        "Connect MyAnimeList in Settings → Tracking to browse your list.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 36.dp),
                    )
                }
            } else {
                items(entries, key = { "mal_${it.anime.id}" }) { entry ->
                    MalListRow(entry, onOpen = {
                        urlHandler.openUri("https://myanimelist.net/anime/${entry.anime.id}")
                    })
                }
                if (loading) {
                    item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 18.dp))
                    }
                } else if (error != null) {
                    item(key = "error") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retryId++ }) { Text("Try again") }
                        }
                    }
                } else if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No anime in this list yet.",
                            color = colors.textSecondary,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                } else if (nextOffset != null) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { requestedOffset = nextOffset ?: requestedOffset }, enabled = !loading) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MalListRow(entry: MalAnimeListEntry, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Open ${entry.anime.title} on MyAnimeList", onClick = onOpen),
        color = colors.surfaceCard,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(width = 64.dp, height = 90.dp)
                    .clip(RoundedCornerShape(8.dp)).background(colors.borderSubtle),
            ) {
                NuvioAsyncImage(
                    model = entry.imageUrl,
                    contentDescription = entry.anime.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.listStatus.status?.label ?: "On MyAnimeList",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    if (entry.listStatus.isRewatching) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text("Rewatching", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    buildString {
                        append("${entry.listStatus.watchedEpisodes}")
                        append(" / ")
                        append(if (entry.totalEpisodes > 0) entry.totalEpisodes.toString() else "—")
                        append(" episodes")
                        if (entry.listStatus.score > 0) append("   ·   ${entry.listStatus.score}/10")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Open on MyAnimeList",
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
