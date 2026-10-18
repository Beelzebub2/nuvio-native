package com.nuvio.app.features.watchlater

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_later_saved
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.middleClick
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop

internal fun Modifier.watchLaterMiddleClick(item: MetaPreview): Modifier {
    if (!isDesktop) return this
    return middleClick {
        WatchLaterRepository.toggle(item)
    }.secondaryClick(if (item.watchLaterEpisode != null) ({ EpisodeDetailsNavigation.open(item) }) else null)
}

@Composable
internal fun WatchLaterPoster(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val items by remember {
        WatchLaterRepository.ensureLoaded()
        WatchLaterRepository.items
    }.collectAsState()
    Box(modifier) {
        // Keep the handler on the card's own layer so its hovered, scaled edges stay clickable.
        EpisodeSynopsis(item) { content(Modifier.watchLaterMiddleClick(item)) }
        if (isDesktop && items.any { it.matchesWatchLaterPreview(item) }) {
            Icon(Icons.Filled.Bookmark, contentDescription = stringResource(Res.string.watch_later_saved),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}
