package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackAwareDownloadLimiterTest {
    @Test
    fun aggregateAllowanceIsSharedAcrossIndependentDownloads() {
        val limiter = PlaybackAwareDownloadLimiter(bytesPerSecondDuringPlayback = 1024L)
        assertEquals(0L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 1_000_000_000L))
        assertEquals(500_000_000L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 1_000_000_000L))
        assertEquals(900_000_000L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 1_100_000_000L))
    }

    @Test
    fun idlePlaybackUnthrottlesImmediatelyAndClearsReservations() {
        val limiter = PlaybackAwareDownloadLimiter(bytesPerSecondDuringPlayback = 1024L)
        limiter.reserveDelayNanos(1024, playbackActive = true, nowNanos = 1_000_000_000L)
        assertEquals(0L, limiter.reserveDelayNanos(1024, playbackActive = false, nowNanos = 1_100_000_000L))
        assertEquals(0L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 1_100_000_000L))
    }

    @Test
    fun slowNetworkNeedsNoExtraDelayAndZeroBytesDoNotReserveTime() {
        val limiter = PlaybackAwareDownloadLimiter(bytesPerSecondDuringPlayback = 1024L)
        assertEquals(0L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 1_000_000_000L))
        assertEquals(0L, limiter.reserveDelayNanos(0, playbackActive = true, nowNanos = 1_000_000_000L))
        assertEquals(0L, limiter.reserveDelayNanos(512, playbackActive = true, nowNanos = 2_000_000_000L))
    }
}
