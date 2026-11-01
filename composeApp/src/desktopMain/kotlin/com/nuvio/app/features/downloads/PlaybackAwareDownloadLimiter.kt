package com.nuvio.app.features.downloads

/**
 * Cooperative aggregate bandwidth budget across *all* desktop background downloads.
 * A high-bitrate torrent stream must take priority over offline transfers on a shared
 * connection. When playback is idle, transfers remain completely unlimited.
 *
 * Reservation is synchronous and tiny; callers sleep outside the lock so independent
 * transfers do not serialize their disk/network work.
 */
internal class PlaybackAwareDownloadLimiter(
    private val bytesPerSecondDuringPlayback: Long = 1024L * 1024L,
) {
    init { require(bytesPerSecondDuringPlayback > 0) }

    // nanoTime has an arbitrary origin; null also handles a clock value below zero.
    private var nextSlotNanos: Long? = null

    @Synchronized
    fun reserveDelayNanos(bytes: Int, playbackActive: Boolean, nowNanos: Long): Long {
        if (!playbackActive) {
            // A paused/finished stream must never carry a throttling backlog into a later job.
            nextSlotNanos = null
            return 0L
        }
        if (bytes <= 0) return 0L
        val waitNanos = nextSlotNanos?.let { (it - nowNanos).coerceAtLeast(0L) } ?: 0L
        val timeBudgetNanos = bytes.toLong() * 1_000_000_000L / bytesPerSecondDuringPlayback
        nextSlotNanos = nowNanos + waitNanos + timeBudgetNanos
        return waitNanos
    }
}
