package com.nuvio.app.features.player.desktop

import java.net.URI
import java.util.Locale

internal const val LinuxMprisObjectPath = "/org/mpris/MediaPlayer2"
internal const val LinuxMprisBusName = "org.mpris.MediaPlayer2.starlit"
internal const val LinuxMprisNoTrack = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

/**
 * Immutable projection of the currently attached desktop player. This never contains
 * the playback URL, add-on request headers, tokens, or local streaming endpoints.
 */
internal data class LinuxMprisState(
    val trackId: String = LinuxMprisNoTrack,
    val title: String = "",
    val episodeLabel: String = "",
    val artworkUrl: String = "",
    val attached: Boolean = false,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val rate: Double = 1.0,
    val volume: Double = 1.0,
    val nextEpisodePlayable: Boolean = false,
) {
    val playbackStatus: String
        get() = when {
            !attached || isLoading || isEnded -> "Stopped"
            isPlaying -> "Playing"
            else -> "Paused"
        }

    val canPlay: Boolean get() = attached && !isLoading
    val canPause: Boolean get() = attached && !isLoading && !isEnded
    val canSeek: Boolean get() = attached && !isLoading && !isEnded && durationMs > 0L
    val canGoNext: Boolean get() = attached && nextEpisodePlayable
    val positionUs: Long get() = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).millisecondsToMicroseconds()
}

internal fun Long.millisecondsToMicroseconds(): Long =
    coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L

/**
 * MPRIS uses signed microseconds. Absolute seek requests from an old track must
 * never apply to a new title or episode.
 */
internal fun linuxMprisSeekTargetMs(
    state: LinuxMprisState,
    microseconds: Long,
    relative: Boolean,
    trackId: String? = null,
): Long? {
    if (!state.canSeek || (trackId != null && trackId != state.trackId)) return null
    val duration = state.durationMs.coerceAtLeast(0L)
    val requested = microseconds / 1_000L
    return if (relative) {
        // Saturating addition avoids overflow from malformed D-Bus requests.
        val current = state.positionMs.coerceIn(0L, duration)
        when {
            requested > 0 && current > Long.MAX_VALUE - requested -> duration
            requested < 0 && current < Long.MIN_VALUE - requested -> 0L
            else -> (current + requested).coerceIn(0L, duration)
        }
    } else if (microseconds < 0L || requested > duration) {
        // SetPosition must ignore absolute values outside the current track.
        null
    } else requested
}

private val PublicArtworkHosts = setOf(
    "image.tmdb.org",
    "cdn.myanimelist.net",
    "s4.anilist.co",
    "media.kitsu.app",
)

/**
 * Never put arbitrary add-on artwork URLs or signed URLs on the session bus.
 * Only trusted public poster CDNs without credentials or query parameters qualify.
 */
internal fun linuxMprisPublicArtworkUrl(raw: String?): String? {
    val url = raw?.trim()?.takeIf { it.length in 1..2_048 } ?: return null
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("https", ignoreCase = true) ||
        parsed.host?.lowercase(Locale.ROOT) !in PublicArtworkHosts ||
        parsed.rawUserInfo != null ||
        parsed.rawQuery != null ||
        parsed.rawFragment != null ||
        parsed.port != -1 ||
        parsed.rawPath.isNullOrBlank() ||
        !parsed.rawPath.startsWith('/')
    ) return null
    return parsed.toASCIIString()
}

internal fun linuxMprisSessionBusAddress(
    sessionBusAddress: String?,
    runtimeDirectory: String?,
): String? {
    if (!sessionBusAddress.isNullOrBlank()) return sessionBusAddress
    val runtime = runtimeDirectory?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
    if (!runtime.startsWith('/')) return null
    return "unix:path=$runtime/bus"
}

internal fun linuxMprisChangedStateFields(old: LinuxMprisState, next: LinuxMprisState): Set<String> =
    buildSet {
        if (old.playbackStatus != next.playbackStatus) add("PlaybackStatus")
        if (old.trackId != next.trackId ||
            old.title != next.title ||
            old.episodeLabel != next.episodeLabel ||
            old.artworkUrl != next.artworkUrl ||
            old.durationMs != next.durationMs
        ) add("Metadata")
        if (old.rate != next.rate) add("Rate")
        if (old.volume != next.volume) add("Volume")
        if (old.canPlay != next.canPlay) add("CanPlay")
        if (old.canPause != next.canPause) add("CanPause")
        if (old.canSeek != next.canSeek) add("CanSeek")
        if (old.canGoNext != next.canGoNext) add("CanGoNext")
        // MPRIS forbids PropertiesChanged for ordinary position updates.
    }
