package com.nuvio.app.features.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

internal data class AndroidPlaybackBufferPolicy(
    val startMs: Int,
    val afterRebufferMs: Int,
)

/**
 * Loopback HTTP video can block while a torrent fetches its next piece, so it needs a
 * longer playable cushion than a normal HTTP/CDN or completed local file.
 *
 * Media3's existing 50-second max buffer and byte budget still bound memory use.
 * These thresholds change only when playback starts or recovers from an underrun.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun androidPlaybackBufferPolicy(isLoopbackSource: Boolean): AndroidPlaybackBufferPolicy =
    if (isLoopbackSource) {
        AndroidPlaybackBufferPolicy(startMs = 8_000, afterRebufferMs = 12_000)
    } else {
        AndroidPlaybackBufferPolicy(
            startMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            afterRebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
