package com.nuvio.app.features.watchlater

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop

@Composable
internal fun WatchLaterControls(item: MetaPreview, episode: MetaVideo? = null) {
    if (!isDesktop) return
    val savedItems by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val preview = item.copy(watchLaterEpisode = episode?.toWatchLaterEpisode() ?: item.watchLaterEpisode)
    val saved = savedItems.any { it.matchesWatchLaterPreview(preview) }
    OutlinedButton(onClick = { WatchLaterRepository.toggle(preview) }) {
        Text(if (episode != null) { if (saved) "Remove episode" else "Save episode" }
            else if (saved && item.type != "series") "Remove from Watch Later" else "Watch Later")
    }
}
