package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticsTest {
    @Test
    fun playbackMetricsUseOnlyActualSnapshotValues() {
        val snapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 800_000L,
            positionMs = 10_450L,
            bufferedPositionMs = 20_750L,
            playbackSpeed = 1.25f,
            videoWidth = 1920,
            videoHeight = 1080,
        )
        val data = snapshot.safePlaybackMetrics()
        assertEquals(SafePlaybackPhase.Playing, data.phase)
        assertEquals("1920 × 1080", data.resolution)
        assertEquals(10L, data.positionSeconds)
        assertEquals(800L, data.durationSeconds)
        assertEquals(10L, data.bufferAheadSeconds)
        assertEquals(1.25f, data.playbackSpeed)
    }

    @Test
    fun metricsStayConservativeForMissingInvalidOrLoadingFields() {
        val snapshot = PlayerPlaybackSnapshot(
            isLoading = true, videoWidth = -100, videoHeight = 0,
            positionMs = -90, bufferedPositionMs = -10,
            durationMs = 0, playbackSpeed = Float.NaN,
        )
        val data = snapshot.safePlaybackMetrics()
        assertEquals(SafePlaybackPhase.Buffering, data.phase)
        assertEquals("Unavailable", data.resolution)
        assertEquals(0L, data.positionSeconds)
        assertEquals(0L, data.bufferAheadSeconds)
        assertEquals(null, data.durationSeconds)
        assertEquals(1f, data.playbackSpeed)
        val text = data.toString()
        assertFalse(text.contains("http"))
        assertFalse(text.contains("Authorization"))
        assertTrue(text.length < 300)
    }

    @Test
    fun copiedDiagnosticsNeverContainUrlsTokensOrLocalPaths() {
        val metrics = PlayerPlaybackSnapshot(
            durationMs = 900_000,
            positionMs = 1_500,
            bufferedPositionMs = 2_500,
            videoWidth = 1280,
            videoHeight = 720,
        ).safePlaybackMetrics()
        val report = safeDiagnosticsReport(metrics, NetworkCondition.NoInternet)
        assertTrue(report.contains("1280 × 720"))
        assertTrue(report.contains("NoInternet"))
        listOf("https://", "file://", "Authorization", "Bearer ", "password",
            "sourceUrl", "accessToken", "Bitwarden", "cookies", "\\Users\\").forEach {
            assertFalse(report.contains(it, ignoreCase = true), it)
        }
    }
}
