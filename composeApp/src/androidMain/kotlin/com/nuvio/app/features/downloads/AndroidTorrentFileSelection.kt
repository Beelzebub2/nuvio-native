package com.nuvio.app.features.downloads

import com.nuvio.engine.NuvioTorrentFile

/** A torrent can only be saved when its metadata identifies the requested video unambiguously. */
internal class AndroidTorrentFileSelectionException(message: String) : IllegalArgumentException(message)

private val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "m2ts", "wmv", "flv")
private val seasonEpisodeLabel = Regex(
    """(?i)(?<![a-z0-9])(?:s0*(\d{1,2})[ ._-]*e0*(\d{1,3})|0*(\d{1,2})x0*(\d{1,3}))(?![0-9])""",
)
private val seasonFolderLabel = Regex("""(?i)(?<![a-z0-9])(?:season[ ._-]*0*(\d{1,2})|s0*(\d{1,2})(?![a-z0-9]))""")
private val episodeLabel = Regex("""(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*0*(\d{1,3})(?![0-9])|\s+-\s*0*(\d{1,3})(?![0-9])""")
private val absoluteEpisodeTitle = Regex("""^\s*(\d{1,4})\.\s+""")
private val numberOnly = Regex("""^\[?0*(\d{1,3})\]?$""")
private val multiEpisode = Regex(
    """(?i)(?:s\d{1,2}[ ._-]*e\d{1,3}|\d{1,2}x\d{1,3}|(?:episode|ep|e)\d{1,3})(?:\s*[-~&,._]\s*e?\d{1,3}|e\d{1,3})(?![a-z0-9])""",
)
private val auxiliaryClip = Regex("""(?i)(?:^|[^a-z0-9])(?:sample|trailer|preview|opening|ending|intro|outro|op|ed)(?![a-z0-9])""")

private fun torrentFilePath(value: String) = value.trim().replace('\\', '/').trimStart('/')

internal fun isAndroidTorrentVideo(file: NuvioTorrentFile): Boolean =
    file.size > 0L && torrentFilePath(file.path).substringAfterLast('.', "").lowercase() in videoExtensions

/** True is strong S/E evidence; false contradicts the episode; null needs a trusted source hint. */
internal fun androidTorrentEpisodeEvidence(file: NuvioTorrentFile, item: DownloadItem): Boolean? {
    val season = item.seasonNumber ?: return null
    val episode = item.episodeNumber ?: return null
    if (file.pathTruncated) return null
    val path = torrentFilePath(file.path)
    val name = path.substringAfterLast('/')
    if (multiEpisode.containsMatchIn(name)) return false
    val explicit = seasonEpisodeLabel.findAll(name).map { match ->
        val parts = match.groupValues
        if (parts[1].isNotEmpty()) parts[1].toInt() to parts[2].toInt()
        else parts[3].toInt() to parts[4].toInt()
    }.toList()
    if (explicit.isNotEmpty()) return explicit.size == 1 && explicit.single() == (season to episode)

    val folders = seasonFolderLabel.findAll(path.substringBeforeLast('/', "")).map { match ->
        match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
    }.toList()
    if (folders.any { it != season }) return false

    val stem = name.substringBeforeLast('.', name)
    val numbers = episodeLabel.findAll(stem).map { match ->
        match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
    }.toList().ifEmpty { numberOnly.matchEntire(stem)?.groupValues?.get(1)?.toIntOrNull()?.let(::listOf).orEmpty() }
    if (numbers.size > 1) return false
    if (numbers.isEmpty()) return null
    val absolute = absoluteEpisodeTitle.find(item.episodeTitle.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (numbers.single() != episode && (absolute == null || numbers.single() != absolute)) return false
    return if (folders.isNotEmpty()) true else null
}

/**
 * Add-on fileIdx can be stale or point at a different episode in a season pack.
 * Resolve from actual native torrent metadata, never a list position or a largest-file guess.
 */
internal fun selectAndroidTorrentFile(files: List<NuvioTorrentFile>, item: DownloadItem): NuvioTorrentFile {
    val videos = files.filter(::isAndroidTorrentVideo)
    if (videos.isEmpty()) throw AndroidTorrentFileSelectionException("The torrent contains no playable video files.")
    val hints = (listOfNotNull(item.torrentFilename) + item.torrentFilenameAlternates)
        .map(::torrentFilePath).filter(String::isNotEmpty).distinct()
    if (hints.any { hint -> hint.split('/').any { it == "." || it == ".." || it.isBlank() } }) {
        throw AndroidTorrentFileSelectionException("Invalid torrent filename in the episode source.")
    }
    val identified = videos.filter { androidTorrentEpisodeEvidence(it, item) == true }
    if (item.isEpisode && identified.size > 1) {
        // A uniquely matching filename can safely distinguish different encodes.
        // Otherwise choosing the first matching encode is silently surprising.
        return matchNativeTorrentHint(identified, hints)?.singleOrNull()
            ?: throw AndroidTorrentFileSelectionException("Multiple files match this episode. Choose a specific file source.")
    }
    if (item.isEpisode && identified.size == 1) return identified.single()
    if (item.isEpisode && videos.any { androidTorrentEpisodeEvidence(it, item) == true }) {
        throw AndroidTorrentFileSelectionException("The torrent episode is ambiguous.")
    }
    val matched = matchNativeTorrentHint(videos, hints)
    if (matched?.size == 1) {
        val selected = matched.single()
        if (androidTorrentEpisodeEvidence(selected, item) == false) {
            throw AndroidTorrentFileSelectionException("The torrent file belongs to another episode.")
        }
        if (item.isEpisode && videos.size > 1 &&
            (androidTorrentEpisodeEvidence(selected, item) != true ||
                auxiliaryClip.containsMatchIn(torrentFilePath(selected.path).substringAfterLast('/')))) {
            throw AndroidTorrentFileSelectionException("This season pack does not identify the requested episode safely.")
        }
        return selected
    }
    if (videos.size == 1) {
        val only = videos.single()
        if (androidTorrentEpisodeEvidence(only, item) != false && !only.pathTruncated &&
            !auxiliaryClip.containsMatchIn(torrentFilePath(only.path).substringAfterLast('/'))) return only
    }
    throw AndroidTorrentFileSelectionException(
        "No unique file matches this episode in the torrent. Choose another source.",
    )
}

private fun matchNativeTorrentHint(files: List<NuvioTorrentFile>, hints: List<String>): List<NuvioTorrentFile>? {
    for (hint in hints) {
        val candidates = files.filterNot { it.pathTruncated }
        val exact = candidates.filter { torrentFilePath(it.path).equals(hint, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        val matching = candidates.filter { file ->
            val path = torrentFilePath(file.path)
            if ('/' in hint) path.endsWith("/$hint", ignoreCase = true)
            else path.substringAfterLast('/').equals(hint, ignoreCase = true)
        }
        if (matching.isNotEmpty()) return matching
    }
    return null
}

internal fun androidTorrentSourceIdentity(hash: String, file: NuvioTorrentFile): String =
    "${hash.lowercase()}:${file.index}:${file.size}:${torrentFilePath(file.path)}"
