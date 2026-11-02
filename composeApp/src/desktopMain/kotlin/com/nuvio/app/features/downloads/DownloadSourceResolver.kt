package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.plugins.*
import com.nuvio.app.features.p2p.P2pSettingsRepository
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
internal suspend fun resolveDownloadSource(item: DownloadItem, excluded: Set<String> = emptySet(),
    cachedSources: MutableMap<String, List<StreamItem>> = mutableMapOf()): StreamItem {
    delay(750L) // Bound repeated unavailable-episode lookups as well as successful transfers.
    val selection = checkNotNull(item.sourceSelection)
    P2pSettingsRepository.ensureLoaded()
    val allowTorrents = selection.transport == "Torrents" || P2pSettingsRepository.uiState.value.p2pEnabled
    suspend fun cached(key: String, fetch: suspend () -> List<StreamItem>): List<StreamItem> =
        cachedSources[key] ?: fetch().also { cachedSources[key] = it }
    val sources = buildList<suspend () -> List<StreamItem>> {
        // Each embedded video owns its streams; never flatten another episode's
        // streams when a provider uses the parent title ID as the episode ID.
        val meta = MetaDetailsRepository.uiState.value.meta
        if (meta?.id == item.parentMetaId) {
            val embedded = embeddedDownloadStreams(meta, item)
            if (embedded.isNotEmpty()) add { embedded }
        }
        for (addon in AddonRepository.uiState.value.addons.enabledAddons()) {
            val manifest = addon.manifest ?: continue
            val id = addon.streamAddonInstanceId(manifest.id)
            if (selection.providerId != null && selection.providerId != id) continue
            if (!manifest.supportsStream(item.contentType, item.videoId)) continue
            add {
                cached("addon:$id") { withTimeoutOrNull(20_000L) {
                    runCatchingUnlessCancelled {
                        StreamParser.parse(fetchAddonResponseText(buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl, resource = "stream", type = item.contentType, id = item.videoId)),
                            addon.displayTitle.ifBlank { manifest.name }, id, manifest.logoUrl)
                    }.getOrDefault(emptyList())
                }.orEmpty() }
            }
        }
        for (scraper in PluginRepository.getEnabledScrapersForType(item.contentType)) {
            if (selection.providerId != null && selection.providerId != "plugin:${scraper.id}") continue
            add {
                cached("plugin:${scraper.id}") { withTimeoutOrNull(25_000L) {
                    PluginRepository.executeScraper(scraper = scraper,
                        tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber),
                        mediaType = item.contentType, season = item.seasonNumber, episode = item.episodeNumber, respectSearchPause = false)
                        .getOrNull()?.map { it.toStreamItem(scraper) }
                }.orEmpty() }
            }
        }
    }
    return selectDownloadSource(selection, sources,
        resolve = { stream ->
            if (!stream.isTorrentStream && DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                (DirectDebridPlaybackResolver.resolveToPlayableStream(stream, item.seasonNumber, item.episodeNumber) as? DirectDebridPlayableResult.Success)?.stream
            } else stream
        },
        include = { stream -> downloadSourceIdentity(stream) !in excluded &&
            (allowTorrents || stream.playableDirectUrl != null || !stream.isTorrentStream) },
    )
}

internal fun embeddedDownloadStreams(meta: MetaDetails, item: DownloadItem): List<StreamItem> {
    val withStreams = meta.videos.filter { it.streams.isNotEmpty() }
    if (item.seasonNumber != null && item.episodeNumber != null) {
        withStreams.filter { it.season == item.seasonNumber && it.episode == item.episodeNumber }.singleOrNull()
            ?.let { return it.streams }
    }
    withStreams.filter { it.id == item.videoId }.singleOrNull()?.let { return it.streams }
    withStreams.filter { it.id.startsWith("${item.videoId}:") }.singleOrNull()?.let { return it.streams }
    if (item.videoId == meta.id && withStreams.size == 1) return withStreams.single().streams
    return emptyList()
}

/** Stable across independent add-on queries, so a rejected torrent is not chosen again. */
internal fun downloadSourceIdentity(stream: StreamItem): String = listOf(
    stream.addonId, stream.p2pInfoHash.orEmpty().lowercase(), stream.playableDirectUrl.orEmpty(),
    stream.p2pFileIdx?.toString().orEmpty(), stream.downloadTorrentFilenameHints().joinToString("|"),
).joinToString("\u0000")

/** A bad file hint is a source-specific error. Retry other eligible sources before failing the task. */
internal suspend fun <T> downloadUsingAlternativeSources(
    choose: suspend (Set<String>) -> StreamItem,
    download: suspend (StreamItem) -> T,
    maxCandidates: Int = 4,
): T {
    require(maxCandidates > 0)
    val excluded = linkedSetOf<String>()
    var lastMismatch: DownloadTorrentFileSelectionException? = null
    repeat(maxCandidates) {
        val stream = try {
            choose(excluded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            if (lastMismatch != null) throw DownloadTorrentFileSelectionException(
                "Available torrent sources do not identify this episode. Choose a different provider or source.",
            )
            throw error
        }
        try {
            return download(stream)
        } catch (mismatch: DownloadTorrentFileSelectionException) {
            if (!excluded.add(downloadSourceIdentity(stream))) throw mismatch
            lastMismatch = mismatch
        }
    }
    throw DownloadTorrentFileSelectionException(
        "Tried $maxCandidates torrent sources without a unique episode file. Choose another provider or source.",
    )
}

internal suspend fun selectDownloadSource(
    selection: DownloadSourceSelection,
    sources: List<suspend () -> List<StreamItem>>,
    include: (StreamItem) -> Boolean = { true },
    resolve: suspend (StreamItem) -> StreamItem? = { it },
): StreamItem {
    val highest = selection.quality == DownloadQuality.Highest
    val wanted = DownloadQuality.height(selection.quality)
    val candidates = mutableListOf<StreamItem>()
    val tried = mutableSetOf<StreamItem>()
    suspend fun firstUsable(streams: List<StreamItem>): StreamItem? {
        for (stream in streams) {
            if (!include(stream) || !tried.add(stream)) continue
            val resolved = resolve(stream) ?: continue
            // Quality was read from the listed stream; a resolved debrid link may not repeat it.
            if (matchesDownloadSelection(resolved, selection.copy(quality = DownloadQuality.Any))) return resolved
        }
        return null
    }
    for (source in sources) {
        val eligible = source().filter { stream ->
            include(stream) && (selection.providerId == null || selection.providerId == stream.addonId) &&
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
