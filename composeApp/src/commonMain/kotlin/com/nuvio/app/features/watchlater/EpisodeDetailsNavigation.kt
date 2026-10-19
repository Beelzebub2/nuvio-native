package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object EpisodeDetailsNavigation {
    private val events = MutableSharedFlow<MetaPreview>(extraBufferCapacity = 1)
    val requests = events.asSharedFlow()
    fun open(item: MetaPreview) { events.tryEmit(item.copy(watchLaterEpisode = null)) }
}
