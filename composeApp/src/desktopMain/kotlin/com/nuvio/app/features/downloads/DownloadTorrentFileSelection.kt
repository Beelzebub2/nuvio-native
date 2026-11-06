package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.TorrServerFile

/** A source's file hint cannot identify a safe playable file in the torrent's actual metadata. */
internal class DownloadTorrentFileSelectionException(message: String) : IllegalStateException(message)

private val downloadableVideoExtensions = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "m2ts", "wmv", "flv")
private val seasonEpisodeInFile = Regex(
    """(?i)(?<![a-z0-9])(?:s0*(\d{1,2})[ ._-]*e0*(\d{1,3})|0*(\d{1,2})x0*(\d{1,3}))(?![0-9])""",
)
private val seasonInPath = Regex(
    """(?i)(?<![a-z0-9])(?:season[ ._-]*0*(\d{1,2})|s0*(\d{1,2})(?![a-z0-9]))""",
)
private val episodeInFile = Regex(
    """(?i)(?<![a-z0-9])(?:ep(?:isode)?|e)[ ._-]*0*(\d{1,3})(?![a-z0-9])|\s+-\s*0*(\d{1,3})(?![a-z0-9])""",
)
private val numberedFile = Regex("""^\[?0*(\d{1,3})\]?$""")
private val numberedEpisodeTitle = Regex("""^\s*(\d{1,4})\.\s+""")
private val auxiliaryVideoName = Regex(
    """(?i)(?:^|[^a-z0-9])(?:sample|trailer|preview|opening|ending|intro|outro|op|ed)(?![a-z0-9])""",
)
private val multiEpisodeFile = Regex(
    """(?i)(?:s\d{1,2}[ ._-]*e\d{1,3}|\d{1,2}x\d{1,3}|(?:ep(?:isode)?|e)\d{1,3}|-\s*\d{1,3})(?:\s*[-~&,._]\s*e?\d{1,3}|e\d{1,3})(?![a-z0-9])""",
)

/** Some anime catalogues prefix titles with the absolute episode number (e.g. "25. Episode title"). */
internal fun downloadAbsoluteEpisode(title: String?): Int? =
    title?.let { numberedEpisodeTitle.find(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun torrentPath(path: String) = path.replace('\\', '/').trimStart('/')

/** True: identified episode; false: explicit conflicting season/episode; null: no useful episode label. */
internal fun matchesDownloadEpisode(path: String, season: Int, episode: Int, absolute: Int? = null,
    requireSeasonEvidence: Boolean = true): Boolean? {
    val normalized = torrentPath(path)
    val name = normalized.substringAfterLast('/')
    if (multiEpisodeFile.containsMatchIn(name)) return false
    val seasons = seasonInPath.findAll(normalized).map { match ->
        match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
    }.toList()
    if (seasons.any { it != season }) return false

    val explicit = seasonEpisodeInFile.findAll(name).map { match ->
        val groups = match.groupValues
        if (groups[1].isNotEmpty()) groups[1].toInt() to groups[2].toInt()
        else groups[3].toInt() to groups[4].toInt()
    }.toList()
    if (explicit.isNotEmpty()) return explicit.size == 1 && explicit.single() == (season to episode)

    val stem = name.substringBeforeLast('.', name)
    val numbers = episodeInFile.findAll(stem).map { match ->
        match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
    }.toList().ifEmpty { numberedFile.matchEntire(stem)?.groupValues?.get(1)?.toIntOrNull()?.let(::listOf).orEmpty() }
    if (numbers.isEmpty()) return null
    // Two episode labels in one file are not a reliable one-episode mapping.
    if (numbers.size != 1) return false
    if (numbers.single() != episode && (absolute == null || numbers.single() != absolute)) return false
    // E02 by itself could belong to ANY season. Do not infer from it when a
    // provider's filename is missing/stale; an exact filename hint can corroborate it.
    return if (requireSeasonEvidence && seasons.isEmpty()) null else true
}

/**
 * TorrServer sorts paths before assigning its native IDs. Add-on fileIdx refers to torrent
 * metadata order and is deliberately never used as a TorrServer ID or list position.
 * A unique exact filename wins, then an episode-verified file repairs a stale or ambiguous hint.
 */
@Suppress("UNUSED_PARAMETER") // The provider index is not a TorrServer-native ID.
internal fun selectDownloadTorrentFile(
    files: List<TorrServerFile>, index: Int?, filename: String?,
    season: Int? = null, episode: Int? = null, absoluteEpisode: Int? = null,
    alternateFilenames: List<String> = emptyList(),
    requireStrongEpisodeEvidence: Boolean = false,
): TorrServerFile {
    val videos = files.filter {
        it.length > 0 && torrentPath(it.path).substringAfterLast('.', "").lowercase() in downloadableVideoExtensions
    }
    val hints = (listOfNotNull(filename) + alternateFilenames)
        .map { it.trim().replace('\\', '/').trimStart('/') }.filter { it.isNotBlank() }.distinct()
    if (hints.any { hint -> hint.split('/').any { it.isBlank() || it == "." || it == ".." } }) {
        throw DownloadTorrentFileSelectionException("Invalid torrent filename. Choose another episode source.")
    }
    fun matches(hint: String): List<TorrServerFile> {
        val full = videos.filter { torrentPath(it.path) == hint }
        if (full.isNotEmpty()) return full
        val folded = videos.filter { torrentPath(it.path).equals(hint, ignoreCase = true) }
        if (folded.isNotEmpty()) return folded
        if ('/' in hint) {
            val suffix = videos.filter { torrentPath(it.path).endsWith("/$hint") }
            if (suffix.isNotEmpty()) return suffix
            return videos.filter { torrentPath(it.path).endsWith("/$hint", ignoreCase = true) }
        }
        val basename = videos.filter { torrentPath(it.path).substringAfterLast('/') == hint }
        if (basename.isNotEmpty()) return basename
        return videos.filter { torrentPath(it.path).substringAfterLast('/').equals(hint, ignoreCase = true) }
    }
    fun episodeMatch(file: TorrServerFile, requireSeasonEvidence: Boolean = true): Boolean? =
        if (season != null && episode != null) matchesDownloadEpisode(file.path, season, episode,
            absoluteEpisode, requireSeasonEvidence)
        else null

    val provenEpisodes = if (season != null && episode != null) videos.filter { episodeMatch(it) == true }
        else emptyList()
    for (hint in hints) {
        val hinted = matches(hint)
        if (hinted.size == 1) {
            val file = hinted.single()
            val evidence = episodeMatch(file, requireSeasonEvidence = false)
            if (evidence == false) continue
            // An explicitly identified S03E02 is stronger evidence than an older
            // hint naming an unlabeled sample/intro or a seasonless E02.
            if (episodeMatch(file) != true && provenEpisodes.isNotEmpty()) continue
            if (requireStrongEpisodeEvidence && season != null && episode != null &&
                episodeMatch(file) != true) continue
            if (season != null && episode != null && evidence == null && videos.size > 1 &&
                auxiliaryVideoName.containsMatchIn(torrentPath(file.path).substringAfterLast('/'))) continue
            return file
        }
        // A repeated basename can still identify one episode through its season folder.
        if (hinted.size > 1) hinted.filter { episodeMatch(it) == true }.singleOrNull()?.let { return it }
    }

    if (requireStrongEpisodeEvidence && season != null && episode != null) {
        // Never assume the provider's index is the metadata order exposed by TorrServer.
        provenEpisodes.singleOrNull()?.let { return it }
    }
    if (hints.isEmpty() && videos.size == 1 &&
        (if (requireStrongEpisodeEvidence && season != null && episode != null) episodeMatch(videos.single()) == true
         else episodeMatch(videos.single(), requireSeasonEvidence = false) != false))
        return videos.single()

    val detail = if (season != null && episode != null) "S${season} E${episode}" else "the requested filename"
    throw DownloadTorrentFileSelectionException(
        "This torrent has no uniquely identifiable video for $detail. Choose another episode source.",
    )
}
