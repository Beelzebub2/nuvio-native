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
private val sourceSeederText = Regex("""(?i)\b(?:seeders?|seeds?)\s*[:=]?\s*([0-9][0-9,]*)\b""")
private val sourceSeederEmoji = Regex("""(?:👤|👥|🌱)\s*([0-9][0-9,]*)\b""")

/** Addon titles and names are untrusted. Never leak media links or credentials in a display label. */
internal fun StreamItem.remoteSourceLabel(): String {
    fun safe(input: String) = input
        .replace(privateSourceLink, "[link]")
        .replace(privateSourceCredential, "[redacted]")
        .map { if (it < ' ' || it == '\u007f') ' ' else it }
        .joinToString("")
        .trim()
        .take(65)
    val provider = safe(addonName).ifBlank { "Source" }
    val source = safe(streamLabel).ifBlank { "Stream" }
    val size = behaviorHints.videoSize?.takeIf { it > 0 }?.let { bytes ->
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 0.1) " · " + ((gib * 10).toLong() / 10.0).toString() + " GiB" else ""
    }.orEmpty()
    return "$provider · $source$size".take(150)
}

/** Show an actual provider seeder count, or one explicitly labelled in a torrent source. */
internal fun StreamItem.remoteSeederCount(): Int? {
    seeders?.takeIf { it in 0..999_999 }?.let { return it }
    if (!isTorrentStream) return null
    val description = listOfNotNull(name, description)
        .joinToString(" ")
        .replace(privateSourceLink, " ")
        .take(1200)
    val match = sourceSeederText.find(description) ?: sourceSeederEmoji.find(description) ?: return null
    return match.groupValues[1].replace(",", "").toIntOrNull()?.takeIf { it in 0..999_999 }
}

/** Metadata-only source choice. The PC retains all links and credentials. */
internal fun StreamItem.remoteSourceChoice(handle: String, recommendation: BestMatchReason? = null): RemoteChoice {
    val resolution = quality()
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
    )
}

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
