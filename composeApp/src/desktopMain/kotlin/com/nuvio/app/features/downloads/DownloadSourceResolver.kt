package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.plugins.*
import com.nuvio.app.features.streams.*
import kotlinx.coroutines.*

/** Independent of both source-screen repositories: downloading cannot cancel playback searches. */
internal suspend fun resolveDownloadSource(item: DownloadItem): StreamItem {
    delay(750L) // Bound repeated unavailable-episode lookups as well as successful transfers.
    val selection = checkNotNull(item.sourceSelection)
    suspend fun select(streams: List<StreamItem>): StreamItem? {
        for (stream in streams) {
            if (selection.providerId != null && selection.providerId != stream.addonId) continue
            val resolved = if (!stream.isTorrentStream && DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                (DirectDebridPlaybackResolver.resolveToPlayableStream(stream, item.seasonNumber, item.episodeNumber) as? DirectDebridPlayableResult.Success)?.stream ?: continue
            } else stream
            if (matchesDownloadSelection(resolved, selection)) return resolved
        }
        return null
    }
    // Embedded streams belong to whichever title is open now; only trust them for this task's title.
    if (MetaDetailsRepository.uiState.value.meta?.id == item.parentMetaId) {
        select(MetaDetailsRepository.findEmbeddedStreams(item.videoId))?.let { return it }
    }
    for (addon in AddonRepository.uiState.value.addons.enabledAddons()) {
        val manifest = addon.manifest ?: continue
        val id = addon.streamAddonInstanceId(manifest.id)
        if (selection.providerId != null && selection.providerId != id) continue
        if (!manifest.supportsStream(item.contentType, item.videoId)) continue
        val streams = withTimeoutOrNull(20_000L) {
            runCatchingUnlessCancelled {
                StreamParser.parse(fetchAddonResponseText(buildAddonResourceUrl(
                    manifestUrl = manifest.transportUrl, resource = "stream", type = item.contentType, id = item.videoId)),
                    addon.displayTitle.ifBlank { manifest.name }, id, manifest.logoUrl)
            }.getOrDefault(emptyList())
        }.orEmpty()
        select(streams)?.let { return it }
    }
    for (scraper in PluginRepository.getEnabledScrapersForType(item.contentType)) {
        if (selection.providerId != null && selection.providerId != "plugin:${scraper.id}") continue
        val streams = withTimeoutOrNull(25_000L) {
            PluginRepository.executeScraper(scraper = scraper,
                tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber),
                mediaType = item.contentType, season = item.seasonNumber, episode = item.episodeNumber, respectSearchPause = false)
                .getOrNull()?.map { it.toStreamItem(scraper) }
        }.orEmpty()
        select(streams)?.let { return it }
    }
    error("No supported file source matches your provider / quality preferences. Retry or choose a source manually.")
}

internal fun matchesDownloadSelection(stream: StreamItem, selection: DownloadSourceSelection): Boolean {
    val direct = stream.playableDirectUrl
    val torrent = direct == null && stream.p2pInfoHash != null
    if (selection.transport == "Direct files" && torrent) return false
    if (selection.transport == "Torrents" && !torrent) return false
    if (selection.quality != "Any" && !Regex("(?i)(?<![0-9])" + Regex.escape(selection.quality) + "(?![0-9])")
            .containsMatchIn(listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename).joinToString(" "))) return false
    return torrent || direct?.let(::supportedDesktopDownloadUrl) == true
}

internal fun supportedDesktopDownloadUrl(url: String): Boolean {
    val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    return (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
        !listOf(".m3u8", ".m3u", ".mpd", ".torrent", ".html", ".htm").any(path::endsWith)
}
