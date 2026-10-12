package com.nuvio.app.features.streams

import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Metadata enriches an already playable episode ID; it must never block source selection. */
internal suspend fun resolveEpisodeVideoId(launch: StreamLaunch, fetch: suspend (String, String) -> MetaDetails?): String {
    if (launch.parentMetaId == null || launch.seasonNumber == null || launch.episodeNumber == null) return launch.videoId
    return try {
        withTimeoutOrNull(8_000L) {
            fetch(launch.parentMetaType ?: launch.type, launch.parentMetaId)?.videos
                ?.firstOrNull { it.season == launch.seasonNumber && it.episode == launch.episodeNumber }
                ?.id?.takeIf { it.isNotBlank() }
        } ?: launch.videoId
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { launch.videoId }
}
