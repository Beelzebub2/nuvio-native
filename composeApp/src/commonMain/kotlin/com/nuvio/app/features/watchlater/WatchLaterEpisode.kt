package com.nuvio.app.features.watchlater

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.watchProgressEntryFreshnessComparator
import kotlinx.serialization.Serializable

@Serializable
data class WatchLaterEpisode(
    val videoId: String,
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
)

internal fun MetaVideo.toWatchLaterEpisode() = WatchLaterEpisode(id, title, season, episode, thumbnail, overview)

internal fun MetaVideo.matchesWatchLaterEpisode(episode: WatchLaterEpisode): Boolean =
    if (episode.seasonNumber != null && episode.episodeNumber != null) {
        season == episode.seasonNumber && this.episode == episode.episodeNumber
    } else {
        id == episode.videoId
    }
internal fun MetaDetails.toWatchLaterPreview(video: MetaVideo? = null) = MetaPreview(
    id, type, name, poster = poster, banner = background, logo = logo, description = description,
    watchLaterEpisode = video?.toWatchLaterEpisode(),
)
internal fun LibraryItem.watchLaterKey(): String {
    val episode = watchLaterEpisode
    val identity = if (episode?.seasonNumber != null && episode.episodeNumber != null)
        "episode:${episode.seasonNumber}:${episode.episodeNumber}" else episode?.videoId.orEmpty()
    return "$type|$id|$identity"
}
internal fun LibraryItem.matchesWatchLaterPreview(preview: MetaPreview): Boolean =
    id == preview.id && type == preview.type &&
        (preview.watchLaterEpisode == null || watchLaterKey() == preview.toLibraryItem(0L).watchLaterKey())
internal fun LibraryItem.watchLaterProgress(progress: WatchProgressUiState): WatchProgressEntry? {
    val episode = watchLaterEpisode
    progress.progressForVideo(episode?.videoId ?: id, id, episode?.seasonNumber, episode?.episodeNumber)?.let { return it }
    if (episode?.seasonNumber == null || episode.episodeNumber == null) return null
    // Addons can use a different video ID for the same logical episode recorded by the player.
    return progress.entries.filter { it.parentMetaId == id && it.parentMetaType == type &&
        it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber }
        .maxWithOrNull(watchProgressEntryFreshnessComparator)
}
internal fun LibraryItem.watchLaterPlayback(progress: WatchProgressUiState): ContinueWatchingItem {
    val episode = watchLaterEpisode
    val videoId = episode?.videoId ?: id
    val entry = watchLaterProgress(progress)?.takeUnless { it.isEffectivelyCompleted }
    return ContinueWatchingItem(
        parentMetaId = id, parentMetaType = type, videoId = videoId, title = name,
        subtitle = episode?.let { "S${it.seasonNumber ?: 1} · E${it.episodeNumber ?: "?"} · ${it.title}" }.orEmpty(),
        imageUrl = episode?.thumbnail ?: banner ?: poster, poster = poster, background = banner, logo = logo,
        seasonNumber = episode?.seasonNumber, episodeNumber = episode?.episodeNumber,
        episodeTitle = episode?.title, episodeThumbnail = episode?.thumbnail,
        resumePositionMs = entry?.lastPositionMs ?: 0L, resumeProgressFraction = entry?.normalizedProgressPercent?.div(100f),
        durationMs = entry?.durationMs ?: 0L, progressFraction = entry?.progressFraction ?: 0f,
    )
}
