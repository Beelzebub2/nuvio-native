package com.nuvio.app.features.remote

/** Only commands for the current playback and choices advertised by that player may reach its callbacks. */
internal fun RemoteCommand.isAllowedFor(playback: RemotePlayback): Boolean {
    if (playback.playbackId.isEmpty() || playbackId != playback.playbackId || !value.isFinite()) return false
    return when (type) {
        "play", "pause", "mute", "next", "previous", "fetchSubtitles" -> id.isEmpty() && value == 0.0
        "seek" -> id.isEmpty() && playback.duration > 0 && value in 0.0..playback.duration.toDouble()
        "seekBy" -> id.isEmpty() && value in -60_000.0..60_000.0
        "volume" -> id.isEmpty() && value in 0.0..1.0
        "skipInterval" -> playback.skip && id.isEmpty() && value == 0.0
        "episode" -> value == 0.0 && playback.episodes.any { it.id == id }
        "subtitle" -> value == 0.0 && playback.subtitles.any { it.id == id }
        "source" -> value == 0.0 && playback.sources.any { it.id == id }
        else -> false
    }
}
