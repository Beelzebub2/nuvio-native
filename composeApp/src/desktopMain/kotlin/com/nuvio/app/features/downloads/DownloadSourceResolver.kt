package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.plugins.*
import com.nuvio.app.features.streams.*
import kotlinx.coroutines.*

internal object DownloadQuality {
    /** Asks every source, then downloads the best resolution any of them offers. */
    const val Highest = "Highest"
    const val Any = "Any"
    val fixed = listOf("2160p", "1080p", "720p", "480p")
    private const val Best = 2160

    fun height(quality: String): Int? = quality.removeSuffix("p").toIntOrNull()
    fun isBest(stream: StreamItem): Boolean = downloadStreamQuality(stream) == Best
}

/** Independent of both source-screen repositories: downloading cannot cancel playback searches. */
internal suspend fun resolveDownloadSource(item: DownloadItem): StreamItem {
    delay(750L) // Bound repeated unavailable-episode lookups as well as successful transfers.
    val selection = checkNotNull(item.sourceSelection)
    val highest = selection.quality == DownloadQuality.Highest
    val wanted = DownloadQuality.height(selection.quality)
    val candidates = mutableListOf<StreamItem>()
    val tried = mutableSetOf<StreamItem>()
    suspend fun firstUsable(streams: List<StreamItem>): StreamItem? {
        for (stream in streams) {
            if (!tried.add(stream)) continue
            val resolved = if (!stream.isTorrentStream && DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                (DirectDebridPlaybackResolver.resolveToPlayableStream(stream, item.seasonNumber, item.episodeNumber) as? DirectDebridPlayableResult.Success)?.stream ?: continue
            } else stream
            // Quality was read from the listed stream; a resolved debrid link may not repeat it.
            if (matchesDownloadSelection(resolved, selection.copy(quality = DownloadQuality.Any))) return resolved
        }
        return null
    }
    suspend fun offer(streams: List<StreamItem>): StreamItem? {
        val eligible = streams.filter { stream ->
            (selection.providerId == null || selection.providerId == stream.addonId) &&
                (wanted == null || downloadStreamQuality(stream) == wanted)
        }
        if (!highest) return firstUsable(eligible)
        candidates += eligible
        // Nothing beats the best resolution, so later sources need not be asked.
        return firstUsable(eligible.filter(DownloadQuality::isBest))
    }
    // Embedded streams belong to whichever title is open now; only trust them for this task's title.
    if (MetaDetailsRepository.uiState.value.meta?.id == item.parentMetaId) {
        offer(MetaDetailsRepository.findEmbeddedStreams(item.videoId))?.let { return it }
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
        offer(streams)?.let { return it }
    }
    for (scraper in PluginRepository.getEnabledScrapersForType(item.contentType)) {
        if (selection.providerId != null && selection.providerId != "plugin:${scraper.id}") continue
        val streams = withTimeoutOrNull(25_000L) {
            PluginRepository.executeScraper(scraper = scraper,
                tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber),
                mediaType = item.contentType, season = item.seasonNumber, episode = item.episodeNumber, respectSearchPause = false)
                .getOrNull()?.map { it.toStreamItem(scraper) }
        }.orEmpty()
        offer(streams)?.let { return it }
    }
    if (highest) firstUsable(rankByDownloadQuality(candidates))?.let { return it }
    error("No supported file source matches your provider / quality preferences. Retry or choose a source manually.")
}

private val labelledQualities = listOf(
    Regex("(?i)(?<![a-z0-9])(2160[pi]|4k|uhd)(?![a-z0-9])") to 2160,
    Regex("(?i)(?<![a-z0-9])(1440[pi]|2k|qhd)(?![a-z0-9])") to 1440,
    Regex("(?i)(?<![a-z0-9])(1080[pi]|fhd|full ?hd)(?![a-z0-9])") to 1080,
    Regex("(?i)(?<![a-z0-9])720[pi](?![a-z0-9])") to 720,
    Regex("(?i)(?<![a-z0-9])576[pi](?![a-z0-9])") to 576,
    Regex("(?i)(?<![a-z0-9])480[pi](?![a-z0-9])") to 480,
    Regex("(?i)(?<![a-z0-9])360[pi](?![a-z0-9])") to 360,
)

/** The vertical resolution a stream is labelled with, or null when its labels don't say. */
internal fun downloadStreamQuality(stream: StreamItem): Int? {
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename).joinToString(" ")
    return labelledQualities.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
}

/** Best resolution first; unlabelled streams last. Ties keep the sources' own order. */
internal fun rankByDownloadQuality(streams: List<StreamItem>): List<StreamItem> =
    streams.sortedByDescending { downloadStreamQuality(it) ?: 0 }

internal fun matchesDownloadSelection(stream: StreamItem, selection: DownloadSourceSelection): Boolean {
    val direct = stream.playableDirectUrl
    val torrent = direct == null && stream.p2pInfoHash != null
    if (selection.transport == "Direct files" && torrent) return false
    if (selection.transport == "Torrents" && !torrent) return false
    val wanted = DownloadQuality.height(selection.quality)
    if (wanted != null && downloadStreamQuality(stream) != wanted) return false
    return torrent || direct?.let(::supportedDesktopDownloadUrl) == true
}

internal fun supportedDesktopDownloadUrl(url: String): Boolean {
    val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    return (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
        !listOf(".m3u8", ".m3u", ".mpd", ".torrent", ".html", ".htm").any(path::endsWith)
}
