package com.nuvio.app.features.watchlater

import com.nuvio.app.features.airing.NATIVE_AIRING_PREFIX
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.downloads.DownloadQuality
import com.nuvio.app.features.downloads.DownloadSourceSelection
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A saved bookmark represents one movie or one chosen/next episode, never an entire season. */
internal fun watchLaterDownloadTarget(
    item: LibraryItem,
    metadata: MetaDetails?,
    todayIsoDate: String,
): Pair<MetaDetails, MetaVideo>? {
    val video = when (item.type) {
        "movie" -> {
            val videoId = metadata?.defaultVideoId?.takeIf(String::isNotBlank)
                ?: metadata?.videos?.firstOrNull { it.id.isNotBlank() }?.id
                ?: metadata?.id?.takeIf { it.isNotBlank() && it != item.id }
                ?: metadata?.imdbId?.takeIf(String::isNotBlank)
                ?: item.id
            metadata?.videos?.firstOrNull { it.id == videoId } ?: MetaVideo(videoId, item.name)
        }
        "series" -> {
            val episode = item.watchLaterEpisode ?: return null
            // Do not guess that an episode is released when metadata cannot confirm it.
            // Missing/future episodes should not leave failed downloads that never auto-retry.
            val matched = metadata?.videos?.firstOrNull { video ->
                if (episode.seasonNumber != null && episode.episodeNumber != null)
                    video.season == episode.seasonNumber && video.episode == episode.episodeNumber
                else video.id == episode.videoId
            } ?: return null
            val videoId = matched.id
            // Native Airing IDs are placeholders until the metadata provider resolves an episode.
            if (videoId.startsWith(NATIVE_AIRING_PREFIX)) return null
            matched.copy(
                id = videoId,
                season = episode.seasonNumber ?: matched.season,
                episode = episode.episodeNumber ?: matched.episode,
                thumbnail = episode.thumbnail ?: matched.thumbnail,
            )
        }
        else -> return null
    }
    if (video.id.isBlank() || !video.isReleasedBy(todayIsoDate)) return null
    val details = (metadata ?: MetaDetails(item.id, item.type, item.name)).copy(
        id = item.id,
        type = item.type,
        name = item.name,
        poster = item.poster ?: metadata?.poster,
        logo = item.logo ?: metadata?.logo,
        background = item.banner ?: metadata?.background,
        videos = listOf(video),
    )
    return details to video
}

/** Check the saved bookmark and setting again after metadata lookup, before creating a durable task. */
internal enum class WatchLaterAutoQueueResult { Queued, AlreadyPresent, NotAvailable }

internal suspend fun queueWatchLaterDownload(
    item: LibraryItem,
    profileId: Int,
    stillEligible: () -> Boolean,
    refreshMetadata: Boolean = false,
): WatchLaterAutoQueueResult {
    if (!stillEligible()) return WatchLaterAutoQueueResult.NotAvailable
    val metadata = (if (refreshMetadata) null else MetaDetailsRepository.peek(item.type, item.id)) ?: try {
        withTimeoutOrNull(15_000L) {
            MetaDetailsRepository.fetch(item.type, item.id, forceRefresh = refreshMetadata)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null // Movies can still use their bookmark's media ID without metadata.
    }
    val (details, video) = watchLaterDownloadTarget(item, metadata, CurrentDateProvider.todayIsoDate())
        ?: return WatchLaterAutoQueueResult.NotAvailable
    if (!stillEligible()) return WatchLaterAutoQueueResult.NotAvailable
    if (DownloadsRepository.retryFailedAutomatic(details, video, profileId)) {
        return WatchLaterAutoQueueResult.Queued
    }
    val queued = DownloadsRepository.enqueueBatch(
        details,
        listOf(video),
        DownloadSourceSelection(quality = DownloadQuality.Highest),
        retryFailed = false,
        expectedProfileId = profileId,
    )
    return when {
        queued > 0 -> WatchLaterAutoQueueResult.Queued
        ProfileRepository.activeProfileId == profileId && stillEligible() -> WatchLaterAutoQueueResult.AlreadyPresent
        else -> WatchLaterAutoQueueResult.NotAvailable
    }
}
