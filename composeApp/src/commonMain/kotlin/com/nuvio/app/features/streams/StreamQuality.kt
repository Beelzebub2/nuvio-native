package com.nuvio.app.features.streams

/** Resolution bucket used to group and filter sources on the desktop episode page. */
internal enum class StreamQuality(val rank: Int) {
    UHD(4),
    FHD(3),
    HD(2),
    OTHER(0),
}

private val uhdPattern = Regex("""\b(2160p|4k|uhd)\b""")
private val fhdPattern = Regex("""\b1080[pi]?\b""")
private val hdPattern = Regex("""\b720p?\b""")

internal fun StreamQuality.label(otherLabel: String): String = when (this) {
    StreamQuality.UHD -> "4K"
    StreamQuality.FHD -> "1080p"
    StreamQuality.HD -> "720p"
    StreamQuality.OTHER -> otherLabel
}

internal fun StreamItem.quality(): StreamQuality {
    val text = listOfNotNull(
        clientResolve?.stream?.raw?.parsed?.resolution,
        name,
        title,
        description,
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.torrentName,
    ).joinToString(" ").lowercase()
    return when {
        uhdPattern.containsMatchIn(text) -> StreamQuality.UHD
        fhdPattern.containsMatchIn(text) -> StreamQuality.FHD
        hdPattern.containsMatchIn(text) -> StreamQuality.HD
        else -> StreamQuality.OTHER
    }
}

/**
 * Regroups add-on results by resolution (best first). Each bucket becomes a pseudo add-on
 * group so the regular stream list renders it with its own header.
 */
internal fun qualityGroups(
    groups: List<AddonStreamGroup>,
    filter: StreamQuality?,
    labelFor: (StreamQuality) -> String,
): List<AddonStreamGroup> {
    val byQuality = groups.flatMap { it.streams }.groupBy { it.quality() }
    return StreamQuality.entries
        .sortedByDescending { it.rank }
        .filter { filter == null || it == filter }
        .mapNotNull { quality ->
            val streams = byQuality[quality].orEmpty()
            if (streams.isEmpty()) {
                null
            } else {
                AddonStreamGroup(
                    addonName = "${labelFor(quality)} · ${streams.size}",
                    addonId = "quality:${quality.name}",
                    streams = streams,
                )
            }
        }
}

internal enum class BestMatchReason { AutoPlayRules, ReadyToPlay, HighestQuality }

internal data class BestMatch(val stream: StreamItem, val reason: BestMatchReason)

/**
 * The stream auto-play would choose when it has picked one; otherwise the highest-quality
 * playable stream, preferring ones cached on a debrid service.
 */
internal fun bestMatch(uiState: StreamsUiState, debridEnabled: Boolean): BestMatch? {
    uiState.autoPlayStream?.let { return BestMatch(it, BestMatchReason.AutoPlayRules) }
    val playable = uiState.allStreams.filter { it.isSelectableForPlayback(debridEnabled) }
    if (playable.isEmpty()) return null
    val cached = playable.filter {
        it.debridCacheStatus?.state == StreamDebridCacheState.CACHED || it.clientResolve?.isCached == true
    }
    val pool = cached.ifEmpty { playable }
    val pick = pool.maxByOrNull { it.quality().rank } ?: return null
    return BestMatch(pick, if (cached.isNotEmpty()) BestMatchReason.ReadyToPlay else BestMatchReason.HighestQuality)
}
