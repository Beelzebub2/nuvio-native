package com.nuvio.app.features.watchlater

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_later
import nuvio.composeapp.generated.resources.watch_later_remove_named
import nuvio.composeapp.generated.resources.watch_later_save_named
import org.jetbrains.compose.resources.stringResource

/** Labelled Watch Later toggle for the desktop details action bar. */
@Composable
internal fun WatchLaterHeroButton(
    item: MetaPreview,
    episode: MetaVideo? = null,
    modifier: Modifier = Modifier,
    size: NuvioActionSize = NuvioActionSize.Large,
) {
    if (!isDesktop) return
    val savedItems by remember { WatchLaterRepository.ensureLoaded(); WatchLaterRepository.items }.collectAsState()
    val preview = item.copy(watchLaterEpisode = episode?.toWatchLaterEpisode() ?: item.watchLaterEpisode)
    val saved = savedItems.any { it.matchesWatchLaterPreview(preview) }
    val name = episode?.title ?: item.name
    NuvioCompactActionButton(
        label = stringResource(Res.string.watch_later),
        icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        onClick = { WatchLaterRepository.toggle(preview) },
        modifier = modifier,
        tone = NuvioCompactActionTone.Secondary,
        accessibilityLabel = stringResource(
            if (saved) Res.string.watch_later_remove_named else Res.string.watch_later_save_named,
            name,
        ),
        size = size,
        selected = saved,
    )
}
