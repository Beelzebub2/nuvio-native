package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.plugins.*
import com.nuvio.app.features.streams.*
import kotlinx.coroutines.*

internal object DownloadQuality {
    /** Compares every source by resolution, remux status and episode file size. */
    const val Highest = "Highest"
    const val Any = "Any"
    val fixed = listOf("2160p", "1080p", "720p", "480p")

    fun height(quality: String): Int? = quality.removeSuffix("p").toIntOrNull()
}

/** Independent of both source-screen repositories: downloading cannot cancel playback searches. */
internal suspend fun resolveDownloadSource(item: DownloadItem): StreamItem {
    delay(750L) // Bound repeated unavailable-episode lookups as well as successful transfers.
    val selection = checkNotNull(item.sourceSelection)
    val sources = buildList<suspend () -> List<StreamItem>> {
        // Embedded streams belong to whichever title is open now; only trust them for this task's title.
        if (MetaDetailsRepository.uiState.value.meta?.id == item.parentMetaId) {
            add { MetaDetailsRepository.findEmbeddedStreams(item.videoId) }
        }
        for (addon in AddonRepository.uiState.value.addons.enabledAddons()) {
            val manifest = addon.manifest ?: continue
            val id = addon.streamAddonInstanceId(manifest.id)
            if (selection.providerId != null && selection.providerId != id) continue
            if (!manifest.supportsStream(item.contentType, item.videoId)) continue
            add {
                withTimeoutOrNull(20_000L) {
                    runCatchingUnlessCancelled {
                        StreamParser.parse(fetchAddonResponseText(buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl, resource = "stream", type = item.contentType, id = item.videoId)),
                            addon.displayTitle.ifBlank { manifest.name }, id, manifest.logoUrl)
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }
        for (scraper in PluginRepository.getEnabledScrapersForType(item.contentType)) {
            if (selection.providerId != null && selection.providerId != "plugin:${scraper.id}") continue
            add {
                withTimeoutOrNull(25_000L) {
                    PluginRepository.executeScraper(scraper = scraper,
                        tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber),
                        mediaType = item.contentType, season = item.seasonNumber, episode = item.episodeNumber, respectSearchPause = false)
                        .getOrNull()?.map { it.toStreamItem(scraper) }
                }.orEmpty()
            }
        }
    }
    return selectDownloadSource(selection, sources) { stream ->
        if (!stream.isTorrentStream && DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            (DirectDebridPlaybackResolver.resolveToPlayableStream(stream, item.seasonNumber, item.episodeNumber) as? DirectDebridPlayableResult.Success)?.stream
        } else stream
    }
}

internal suspend fun selectDownloadSource(
    selection: DownloadSourceSelection,
    sources: List<suspend () -> List<StreamItem>>,
    resolve: suspend (StreamItem) -> StreamItem? = { it },
): StreamItem {
    val highest = selection.quality == DownloadQuality.Highest
    val wanted = DownloadQuality.height(selection.quality)
    val candidates = mutableListOf<StreamItem>()
    val tried = mutableSetOf<StreamItem>()
    suspend fun firstUsable(streams: List<StreamItem>): StreamItem? {
        for (stream in streams) {
            if (!tried.add(stream)) continue
            val resolved = resolve(stream) ?: continue
            // Quality was read from the listed stream; a resolved debrid link may not repeat it.
            if (matchesDownloadSelection(resolved, selection.copy(quality = DownloadQuality.Any))) return resolved
        }
        return null
    }
    for (source in sources) {
        val eligible = source().filter { stream ->
            (selection.providerId == null || selection.providerId == stream.addonId) &&
                (wanted == null || downloadStreamQuality(stream) == wanted)
        }
        if (highest) {
            // A later provider can have a less compressed file, even after finding 4K.
            candidates += eligible
        } else {
            firstUsable(eligible)?.let { return it }
        }
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
    val parsed = stream.clientResolve?.stream?.raw?.parsed?.resolution
    parsed?.trim()?.removeSuffix("p")?.toIntOrNull()?.takeIf { height -> labelledQualities.any { it.second == height } }?.let { return it }
    labelledQualities.firstOrNull { (pattern, _) -> parsed != null && pattern.containsMatchIn(parsed) }?.let { return it.second }
    val text = downloadReleaseText(stream)
    return labelledQualities.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
}

private fun downloadReleaseText(stream: StreamItem): String {
    val raw = stream.clientResolve?.stream?.raw
    return listOfNotNull(
        stream.name, stream.title, stream.description, stream.behaviorHints.filename,
        stream.clientResolve?.filename, stream.clientResolve?.torrentName,
        raw?.filename, raw?.torrentName, raw?.parsed?.rawTitle, raw?.parsed?.quality,
    ).joinToString(" ")
}

private val remuxPattern = Regex("(?i)(?<![a-z0-9])(bd|uhd)?remux(?![a-z0-9])")
private val downloadSizePattern = Regex("(?i)(?<![\\w.,+-])(\\d+(?:[.,]\\d+)?)\\s*([kmgt]i?b)(?![a-z]|/)")
private val packSizePattern = Regex("(?i)\\b(pack|season|folder|total|complete|collection)\\b|📦")

/** Only the selected file's size is useful here; folderSize/cachedSize may describe a season pack. */
private fun downloadFileSize(stream: StreamItem): Long? {
    stream.behaviorHints.videoSize?.takeIf { it > 0 }?.let { return it }
    stream.clientResolve?.stream?.raw?.size?.takeIf { it > 0 }?.let { return it }
    for (text in listOfNotNull(stream.description, stream.title, stream.name)) {
        for (part in text.split('\n', '|', '•', '·')) {
            if (packSizePattern.containsMatchIn(part)) continue
            for (match in downloadSizePattern.findAll(part)) {
                val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val unit = match.groupValues[2].lowercase()
                val base = if ('i' in unit) 1024.0 else 1000.0
                val multiplier = when (unit.first()) {
                    'k' -> base
                    'm' -> base * base
                    'g' -> base * base * base
                    else -> base * base * base * base
                }
                val bytes = amount * multiplier
                if (bytes.isFinite() && bytes >= 1 && bytes < Long.MAX_VALUE.toDouble()) return bytes.toLong()
            }
        }
    }
    return null
}

private data class DownloadFidelity(val stream: StreamItem, val resolution: Int, val remux: Boolean, val bytes: Long)

/** At each resolution prefer remuxes, then larger files as a compression signal for the same episode.
 * Codec alone does not tell us the bitrate. Unknown sizes sort last; true ties retain provider order.
 */
internal fun rankByDownloadQuality(streams: List<StreamItem>): List<StreamItem> =
    streams.map { stream ->
        DownloadFidelity(stream, downloadStreamQuality(stream) ?: 0,
            remuxPattern.containsMatchIn(downloadReleaseText(stream)), downloadFileSize(stream) ?: 0)
    }.sortedWith(compareByDescending<DownloadFidelity> { it.resolution }
        .thenByDescending { it.remux }
        .thenByDescending { it.bytes })
        .map { it.stream }

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
