package com.nuvio.app.features.remote

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.BestMatchReason
import com.nuvio.app.features.streams.BestMatch
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamQuality
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.bestMatch
import com.nuvio.app.features.streams.quality
import com.nuvio.app.features.streams.SourceFilterMetadata
import com.nuvio.app.features.streams.SourceResultsFilters
import com.nuvio.app.features.streams.SourceFilterAvailability
import com.nuvio.app.features.streams.sourceFilterMetadata
import com.nuvio.app.features.streams.sourceFilterAvailability
import com.nuvio.app.features.streams.reportedSeeders

/** Never advertise external open actions, file URIs or disabled P2P sources over the remote. */
internal fun StreamItem.isEligibleForRemotePlayback(p2pEnabled: Boolean, debridEnabled: Boolean): Boolean {
    if (shouldOpenExternally) return false
    val direct = playableDirectUrl?.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }
    return direct != null ||
        (debridEnabled && isAddonDebridCandidate) ||
        (p2pEnabled && needsLocalDebridResolve && p2pInfoHash != null)
}

private val privateSourceLink = Regex("(?i)(https?://|magnet:|torrent://)\\S+")
private val privateSourceCredential = Regex("(?i)(bearer|token|api[-_]?key|authorization)\\s*[=:]\\s*\\S+")
private fun safeSourceDescription(input: String, maxLength: Int = 65): String = input
    .replace(privateSourceLink, "[link]")
    .replace(privateSourceCredential, "[redacted]")
    .map { if (it < ' ' || it == '\u007f') ' ' else it }
    .joinToString("")
    .trim()
    .take(maxLength)

/** Addon titles and names are untrusted. Never leak media links or credentials in a display label. */
internal fun StreamItem.remoteSourceLabel(): String {
    val provider = safeSourceDescription(addonName).ifBlank { "Source" }
    val source = safeSourceDescription(streamLabel).ifBlank { "Stream" }
    val size = behaviorHints.videoSize?.takeIf { it > 0 }?.let { bytes ->
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 0.1) " · " + ((gib * 10).toLong() / 10.0).toString() + " GiB" else ""
    }.orEmpty()
    return "$provider · $source$size".take(150)
}

/** Show an actual provider seeder count, or one explicitly labelled in a torrent source. */
internal fun StreamItem.remoteSeederCount(): Int? {
    return reportedSeeders()
}

/** Metadata-only source choice. The PC retains all links and credentials. */
internal fun StreamItem.remoteSourceChoice(handle: String, recommendation: BestMatchReason? = null): RemoteChoice {
    val resolution = quality()
    val metadata = sourceFilterMetadata()
    val parsed = clientResolve?.stream?.raw?.parsed
    val parsedAudio = parsed?.audio.orEmpty() + parsed?.channels.orEmpty()
    return RemoteChoice(
        id = handle,
        label = remoteSourceLabel(),
        quality = when (resolution) {
            StreamQuality.UHD -> "4K"
            StreamQuality.FHD -> "1080p"
            StreamQuality.HD -> "720p"
            StreamQuality.OTHER -> null
        },
        // A resolution indicator, NOT a viewer rating or reliability measurement.
        qualityStars = when (resolution) {
            StreamQuality.UHD -> 5
            StreamQuality.FHD -> 4
            StreamQuality.HD -> 3
            StreamQuality.OTHER -> null
        },
        seeders = remoteSeederCount(),
        peers = peers?.takeIf { it in 0..999_999 },
        cached = debridCacheStatus?.state == StreamDebridCacheState.CACHED || clientResolve?.isCached == true,
        recommended = recommendation != null,
        recommendationReason = recommendation?.name,
        provider = safeSourceDescription(addonName).ifBlank { null },
        release = parsed?.group?.let { safeSourceDescription(it) }?.ifBlank { null },
        codec = parsed?.codec?.takeIf { it.matches(Regex("[A-Za-z0-9+._-]{1,30}")) },
        audio = parsedAudio.joinToString(" ").takeIf { it.isNotBlank() }
            ?.let { safeSourceDescription(it) }?.ifBlank { null },
        hdr = metadata.hdr,
        sizeBytes = metadata.sizeBytes,
    )
}

/** Optional display metadata can be absent when the PC runs an older version. */
internal fun RemoteChoice.sourceFilterMetadata(): SourceFilterMetadata = sourceFilterMetadata(
    searchFields = listOfNotNull(label, provider, release, codec, audio, quality),
    quality = when (quality?.lowercase()) {
        "4k", "2160p" -> StreamQuality.UHD
        "1080p" -> StreamQuality.FHD
        "720p" -> StreamQuality.HD
        else -> StreamQuality.OTHER
    },
    seeders = seeders,
    hdrText = (if (hdr) "HDR " else "") + listOfNotNull(label, release).joinToString(" "),
    audioText = listOfNotNull(label, release, audio).joinToString(" "),
    sizeBytes = sizeBytes,
)

internal fun remoteSourceFilterAvailability(sources: List<RemoteChoice>): SourceFilterAvailability =
    sourceFilterAvailability(sources.map { it.sourceFilterMetadata() })

/** This changes only visible choices; source handles and recommended flags stay intact. */
internal fun filterRemoteSourceChoices(
    sources: List<RemoteChoice>,
    filters: SourceResultsFilters,
): List<RemoteChoice> = if (!filters.isActive) sources
else sources.filter { it.sourceFilterMetadata().matches(filters) }

internal data class RemoteSourceSelection(
    val streams: List<StreamItem>,
    val best: BestMatch?,
)

/**
 * Mirror the desktop best-match decision on the PC's eligible sources only.
 * If a result limit applies, the recommended handle must remain selectable.
 */
internal fun recommendedRemoteSources(
    state: StreamsUiState,
    eligible: List<StreamItem>,
    debridEnabled: Boolean,
    limit: Int = 36,
): RemoteSourceSelection {
    if (eligible.isEmpty() || limit <= 0) return RemoteSourceSelection(emptyList(), null)
    val best = bestMatch(
        state.copy(
            groups = listOf(AddonStreamGroup("Remote", "remote", eligible)),
            selectedFilter = null,
            autoPlayStream = state.autoPlayStream?.takeIf { it in eligible },
        ),
        debridEnabled = debridEnabled,
    )
    val ordered = listOfNotNull(best?.stream) + eligible.filterNot { it == best?.stream }
    return RemoteSourceSelection(ordered.take(limit), best)
}
