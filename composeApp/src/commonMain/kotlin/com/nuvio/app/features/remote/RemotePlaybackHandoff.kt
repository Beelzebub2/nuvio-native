package com.nuvio.app.features.remote

import kotlinx.serialization.Serializable

/**
 * Metadata-only request. The phone NEVER sends a media URL, signed URL,
 * headers, torrent, file path, DRM material or account credentials.
 * The desktop obtains and selects a source with its existing app flow.
 */
@Serializable
internal data class RemotePlaybackHandoff(
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val title: String,
    val profileId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    val resumePositionMs: Long = 0L,
)

private val safeMediaIdentifier = Regex("[A-Za-z0-9:_-]{1,150}")

internal fun RemotePlaybackHandoff.isAllowed(): Boolean {
    if (contentType !in setOf("movie", "series")) return false
    if (!videoId.matches(safeMediaIdentifier) || !parentMetaId.matches(safeMediaIdentifier)) return false
    if (title.length !in 1..160 || title.isBlank() || title.any { it < ' ' || it == '\u007f' }) return false
    if (profileId !in 0..10000 || resumePositionMs !in 0..(12L * 60 * 60 * 1000)) return false
    if ((season == null) != (episode == null)) return false
    if (season != null && season !in 0..999) return false
    if (episode != null && episode !in 0..9999) return false
    return true
}

/** Never accept a media handoff from a different profile or playback generation. */
internal fun RemoteCommand.acceptableHandoff(
    currentProfileId: Int,
    currentPlaybackId: String,
): RemotePlaybackHandoff? {
    if (type != "handoffRequest" || id.isNotEmpty() || value != 0.0 ||
        playbackId != currentPlaybackId ||
        profileId != currentProfileId ||
        handoff == null ||
        handoff.profileId != currentProfileId ||
        !handoff.isAllowed()) return null
    return handoff
}
