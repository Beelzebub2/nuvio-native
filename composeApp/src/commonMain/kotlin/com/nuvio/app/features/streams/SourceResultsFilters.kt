package com.nuvio.app.features.streams

/** Local-only controls for a visible list; they never affect the resolver or auto-play policy. */
internal data class SourceResultsFilters(
    val search: String = "",
    val quality: StreamQuality? = null,
    val minSeeders: Int? = null,
    val hdrOnly: Boolean = false,
    val audio: SourceAudioFilter? = null,
    val maxSizeBytes: Long? = null,
) {
    val isActive: Boolean
        get() = search.isNotBlank() || quality != null || minSeeders != null ||
            hdrOnly || audio != null || maxSizeBytes != null
}

internal enum class SourceAudioFilter { ATMOS, SURROUND }
internal const val SOURCE_GIB: Long = 1024L * 1024L * 1024L

internal data class SourceFilterMetadata(
    val searchFields: List<String>,
    val quality: StreamQuality,
    val seeders: Int? = null,
    val hdr: Boolean = false,
    val atmos: Boolean = false,
    val surround: Boolean = false,
    val sizeBytes: Long? = null,
) {
    fun matches(filters: SourceResultsFilters): Boolean {
        if (filters.quality != null && quality != filters.quality) return false
        if (filters.minSeeders != null && (seeders ?: return false) < filters.minSeeders) return false
        if (filters.hdrOnly && !hdr) return false
        if (filters.audio == SourceAudioFilter.ATMOS && !atmos) return false
        if (filters.audio == SourceAudioFilter.SURROUND && !surround) return false
        if (filters.maxSizeBytes != null && (sizeBytes ?: return false) > filters.maxSizeBytes) return false
        val tokens = normalizeSourceSearch(filters.search).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val searchable = normalizeSourceSearch(searchFields.joinToString(" "))
        val compact = searchable.replace(" ", "")
        return tokens.all { searchable.contains(it) || compact.contains(it) }
    }
}

internal data class SourceFilterAvailability(
    val qualityCounts: Map<StreamQuality, Int> = emptyMap(),
    val maxSeeders: Int? = null,
    val hasHdr: Boolean = false,
    val hasAtmos: Boolean = false,
    val hasSurround: Boolean = false,
    val hasSize: Boolean = false,
) {
    val total: Int get() = qualityCounts.values.sum()
}

private val sourceWordBreaks = Regex("""[^\p{L}\p{N}]+""")
private val hdrPattern = Regex("""(?i)\b(?:hdr10\+?|hdr|dolby[ ._-]?vision|dovi|dv|hlg)\b""")
private val atmosPattern = Regex("""(?i)\batmos\b""")
private val surroundPattern = Regex("""(?i)\b(?:surround|atmos|truehd|dts|5[ .]1|7[ .]1)\b""")
private val seederPattern = Regex("""(?i)\b(?:seeders?|seeds?)\s*[:=]?\s*([0-9][0-9,]*)\b""")
private val seederEmojiPattern = Regex("""(?:👤|👥|🌱)\s*([0-9][0-9,]*)\b""")
private val sourceLinkPattern = Regex("""(?i)(?:https?://|magnet:|torrent://)\S+""")

/** Treat punctuation and case consistently for provider names, releases and codec tokens. */
internal fun normalizeSourceSearch(value: String): String =
    value.lowercase().replace(sourceWordBreaks, " ").trim()

internal fun sourceFilterMetadata(
    searchFields: List<String>,
    quality: StreamQuality,
    seeders: Int? = null,
    hdrText: String = "",
    audioText: String = "",
    sizeBytes: Long? = null,
): SourceFilterMetadata {
    val audioMatches = audioText.ifBlank { searchFields.joinToString(" ") }
    return SourceFilterMetadata(
        searchFields = searchFields,
        quality = quality,
        seeders = seeders?.takeIf { it in 0..999_999 },
        hdr = hdrPattern.containsMatchIn(hdrText.ifBlank { searchFields.joinToString(" ") }),
        atmos = atmosPattern.containsMatchIn(audioMatches),
        surround = surroundPattern.containsMatchIn(audioMatches),
        sizeBytes = sizeBytes?.takeIf { it > 0 },
    )
}

/** Only provider-reported seeders or explicitly labelled torrent counts are accepted. */
internal fun StreamItem.reportedSeeders(): Int? {
    seeders?.takeIf { it in 0..999_999 }?.let { return it }
    if (!isTorrentStream) return null
    val description = listOfNotNull(name, description).joinToString(" ")
        .replace(sourceLinkPattern, " ").take(1200)
    val match = seederPattern.find(description) ?: seederEmojiPattern.find(description) ?: return null
    return match.groupValues[1].replace(",", "").toIntOrNull()?.takeIf { it in 0..999_999 }
}

internal fun StreamItem.sourceFilterMetadata(): SourceFilterMetadata {
    val raw = clientResolve?.stream?.raw
    val parsed = raw?.parsed
    val hdrFields = parsed?.hdr.orEmpty() + listOfNotNull(name, title, description, behaviorHints.filename)
    val audioFields = parsed?.audio.orEmpty() + parsed?.channels.orEmpty() +
        listOfNotNull(name, title, description, behaviorHints.filename)
    return sourceFilterMetadata(
        searchFields = listOfNotNull(
            addonName, sourceName, name, title, description, behaviorHints.filename,
            clientResolve?.torrentName, clientResolve?.filename, raw?.torrentName,
            raw?.filename, parsed?.rawTitle, parsed?.parsedTitle, parsed?.group,
            parsed?.codec, parsed?.resolution, parsed?.quality,
        ) + hdrFields + audioFields,
        quality = quality(),
        seeders = reportedSeeders(),
        hdrText = hdrFields.joinToString(" "),
        audioText = audioFields.joinToString(" "),
        sizeBytes = sequenceOf(behaviorHints.videoSize, raw?.size, debridCacheStatus?.cachedSize)
            .filterNotNull().firstOrNull { it > 0 },
    )
}

internal fun sourceFilterAvailability(metadata: List<SourceFilterMetadata>): SourceFilterAvailability =
    SourceFilterAvailability(
        qualityCounts = metadata.groupingBy { it.quality }.eachCount(),
        maxSeeders = metadata.mapNotNull { it.seeders }.maxOrNull(),
        hasHdr = metadata.any { it.hdr },
        hasAtmos = metadata.any { it.atmos },
        hasSurround = metadata.any { it.surround },
        hasSize = metadata.any { it.sizeBytes != null },
    )

/** Preserve original group order, loading/errors and stream references for selection callbacks. */
internal fun filterStreamGroups(
    groups: List<AddonStreamGroup>,
    filters: SourceResultsFilters,
): List<AddonStreamGroup> {
    if (!filters.isActive) return groups
    return groups.map { group ->
        group.copy(streams = group.streams.filter { it.sourceFilterMetadata().matches(filters) })
    }
}
