package com.nuvio.app.features.watchlater

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.runtime.*
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop

@Composable
internal fun WatchLaterControls(item: MetaPreview, episode: MetaVideo? = null) {
    if (!isDesktop) return
    val savedItems by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val preview = item.copy(watchLaterEpisode = episode?.toWatchLaterEpisode() ?: item.watchLaterEpisode)
    val saved = savedItems.any { it.matchesWatchLaterPreview(preview) }
    // A series may have another episode queued; only a specific episode can be called removed.
    val removing = saved && (episode != null || item.type != "series")
    NuvioCompactActionButton(
        label = if (episode != null) { if (saved) "Remove episode" else "Save episode" }
            else if (removing) "Remove from Watch Later" else "Watch Later",
        accessibilityLabel = if (episode != null) {
            if (removing) "Remove episode ${episode.title} from Watch Later" else "Save episode ${episode.title} to Watch Later"
        } else if (removing) "Remove ${item.name} from Watch Later" else "Save ${item.name} to Watch Later",
        icon = if (removing) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
        tone = if (episode != null && removing) NuvioCompactActionTone.Danger
            else if (removing) NuvioCompactActionTone.Quiet else NuvioCompactActionTone.Tonal,
        onClick = { WatchLaterRepository.toggle(preview) },
    )
}
