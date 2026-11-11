package com.nuvio.app.features.myanimelist

internal const val DefaultMalPlaybackUpdateThresholdPercent = 55
internal const val MinimumMalPlaybackUpdateThresholdPercent = 55
internal const val MaximumMalPlaybackUpdateThresholdPercent = 95

internal fun normalizeMalPlaybackUpdateThresholdPercent(percent: Int): Int =
    ((percent.coerceIn(MinimumMalPlaybackUpdateThresholdPercent, MaximumMalPlaybackUpdateThresholdPercent) + 2) / 5) * 5

/** A short start, unknown duration, or a paused/loading stream must not update MAL. */
internal fun shouldUpdateMalAfterPlayback(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    thresholdPercent: Int,
): Boolean {
    if (!isPlaying || isLoading || positionMs <= 0L || durationMs <= 0L) return false
    val playbackPercent = positionMs.coerceAtMost(durationMs).toDouble() * 100.0 / durationMs.toDouble()
    return playbackPercent >= normalizeMalPlaybackUpdateThresholdPercent(thresholdPercent)
}
