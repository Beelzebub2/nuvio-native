package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.flow.StateFlow

internal expect object WatchLaterRepository {
    val items: StateFlow<List<LibraryItem>>
    fun ensureLoaded()
    fun clearLocalState()
    fun toggle(item: MetaPreview)
    fun remove(item: LibraryItem)
    fun resolveEpisodes()
}
