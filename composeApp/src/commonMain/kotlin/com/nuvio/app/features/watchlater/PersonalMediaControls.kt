package com.nuvio.app.features.watchlater

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop

@Composable
internal fun PersonalMediaControls(item: MetaPreview, episode: MetaVideo? = null, showSaveEpisode: Boolean = true) {
    if (!isDesktop) return
    val entries by remember { PersonalMediaRepository.ensureLoaded(); PersonalMediaRepository.items }.collectAsState()
    val current = entries.firstOrNull { it.item.id == item.id && it.item.type == item.type }
    var statusMenu by remember(item.id, item.type) { mutableStateOf(false) }
    var ratingMenu by remember(item.id, item.type) { mutableStateOf(false) }
    fun persist(action: () -> Unit) { runCatching(action).onFailure { NuvioToastController.show("Couldn't save: ${it.message}") } }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { statusMenu = true }) { Text(current?.status?.label ?: "Set status") }
            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                PersonalMediaStatus.entries.forEach { status ->
                    DropdownMenuItem(text = { Text(status.label) }, onClick = {
                        statusMenu = false; persist { PersonalMediaRepository.setStatus(item, status) }
                    })
                }
                DropdownMenuItem(text = { Text("Clear status") }, onClick = {
                    statusMenu = false; persist { PersonalMediaRepository.setStatus(item, null) }
                })
            }
        }
        Box {
            OutlinedButton(onClick = { ratingMenu = true }) { Text(current?.rating?.let { "★ $it/10" } ?: "Rate") }
            DropdownMenu(expanded = ratingMenu, onDismissRequest = { ratingMenu = false }) {
                (10 downTo 1).forEach { rating ->
                    DropdownMenuItem(text = { Text("$rating / 10") }, onClick = {
                        ratingMenu = false; persist { PersonalMediaRepository.setRating(item, rating) }
                    })
                }
                DropdownMenuItem(text = { Text("Clear rating") }, onClick = {
                    ratingMenu = false; persist { PersonalMediaRepository.setRating(item, null) }
                })
            }
        }
        if (showSaveEpisode) {
            val savedItems by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
            val preview = item.copy(watchLaterEpisode = episode?.toWatchLaterEpisode() ?: item.watchLaterEpisode)
            val saved = savedItems.any { it.id == preview.id && it.type == preview.type &&
                it.watchLaterEpisode?.videoId == preview.watchLaterEpisode?.videoId }
            OutlinedButton(onClick = { WatchLaterRepository.toggle(preview) }) {
                Text(if (episode != null) { if (saved) "Remove episode" else "Save episode" }
                    else if (saved && item.type != "series") "Remove from Watch Later" else "Watch Later")
            }
        }
    }
}
