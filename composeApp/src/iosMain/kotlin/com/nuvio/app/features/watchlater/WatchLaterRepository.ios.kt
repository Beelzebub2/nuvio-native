package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual object WatchLaterRepository {
    actual val items: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    actual fun ensureLoaded() = Unit
    actual fun clearLocalState() = Unit
    actual fun toggle(item: MetaPreview) = Unit
    actual fun remove(item: LibraryItem) = Unit
    actual fun removeCompletedEpisode(session: WatchProgressPlaybackSession) = Unit
    actual fun resolveEpisodes() = Unit
    actual fun exportLocalImportSnapshot(): String? = null
    actual fun importAnonymousItems(payload: String) = Unit
}
