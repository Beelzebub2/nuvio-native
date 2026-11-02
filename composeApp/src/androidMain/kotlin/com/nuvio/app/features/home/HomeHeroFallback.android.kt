package com.nuvio.app.features.home

import com.nuvio.app.features.airing.AiringState
import com.nuvio.app.features.airing.NativeAiring
import com.nuvio.app.features.airing.filterAiringContent
import com.nuvio.app.features.airing.selectAiringEvents
import com.nuvio.app.features.airing.AiringWindow
import kotlinx.coroutines.withTimeoutOrNull

internal actual suspend fun platformHomeHeroFallbackItems(includeUpcoming: Boolean): List<MetaPreview> {
    val repository = NativeAiring.repository
    // Share the cache and coalescing logic with the Airing Now page. On a
    // first install, wait briefly for a classified schedule; do not block Home.
    val cached = homeAiringHeroItems(repository.state.value, includeUpcoming)
    if (cached.isNotEmpty()) return cached
    withTimeoutOrNull(18_000L) { repository.refresh() }
    return homeAiringHeroItems(repository.state.value, includeUpcoming)
}

internal fun homeAiringHeroItems(state: AiringState, includeUpcoming: Boolean = true): List<MetaPreview> {
    val latest = selectAiringEvents(state.snapshot.events, state.settings, state.now, AiringWindow.Now)
    val upcoming = filterAiringContent(state.snapshot.events, state.settings).filter { event ->
        !event.isBreak && event.airingAt > state.now.epochSecond &&
            event.airingAt < state.now.plusSeconds(7 * 86400L).epochSecond &&
            event.airType == state.settings.airType &&
            (state.settings.includeEstimated || !event.estimated) &&
            event.mediaId !in state.settings.hiddenMediaIds
    }.sortedBy { it.airingAt }
    return (latest + if (includeUpcoming) upcoming else emptyList()).asSequence()
        .filter { !it.banner.isNullOrBlank() || !it.poster.isNullOrBlank() }
        .distinctBy { it.mediaId }
        .take(8).map { it.preview() }.toList()
}
