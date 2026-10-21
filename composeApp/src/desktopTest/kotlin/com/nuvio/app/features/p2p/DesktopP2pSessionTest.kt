package com.nuvio.app.features.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopP2pSessionTest {
    private val request = P2pStreamRequest("a".repeat(40), null, filename = "sample.mp4")

    private class Backend : DesktopTorrentBackend {
        var starts = 0
        var stops = 0
        var running = false
        var failures = 0
        var failAfterMetadata = false
        var healthy = true
        var healthChecks = 0
        var statusDelayMs = 0L
        var statusHttpCode: Int? = null
        var healthGate: CompletableDeferred<Unit>? = null
        var startupFailures = 0
        var metadata = true
        var startGate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<String>()
        private var callsSinceStart = 0
        val dropped = mutableListOf<String>()
        private var preparedSettings: P2pSettingsUiState? = null
        override suspend fun prepare(settings: P2pSettingsUiState) { if (!running || preparedSettings != settings) restart(settings) }
        override suspend fun dropTorrent(hash: String) { dropped += hash }

        override suspend fun restart(settings: P2pSettingsUiState) {
            stops += 1
            running = false
            starts += 1
            callsSinceStart = 0
            healthy = true
            if (startupFailures > 0) {
                startupFailures -= 1
                throw DesktopTorrentEngineUnavailable("Transient startup failure")
            }
            startGate?.await()
            running = true
            preparedSettings = settings
        }
        override suspend fun stop() { stops += 1; running = false }
        override suspend fun addTorrent(magnetLink: String): String {
            requests += magnetLink
            return magnetLink.substringAfter("btih:").substringBefore('&')
        }
        override suspend fun getTorrentStats(hash: String): TorrServerStats {
            callsSinceStart += 1
            if (failures > 0 || (failAfterMetadata && callsSinceStart > 1)) {
                kotlinx.coroutines.delay(statusDelayMs)
                failures = (failures - 1).coerceAtLeast(0)
                throw DesktopTorrentEngineUnavailable("Status unavailable", httpStatusCode = statusHttpCode)
            }
            return TorrServerStats(0, 0, 0, 0, 10, 20, 100,
                if (metadata) listOf(TorrServerFile(1, "sample.mp4", 100)) else emptyList())
        }
        override suspend fun isHealthy(): Boolean {
            healthChecks++
            healthGate?.await()
            return running && healthy && !failAfterMetadata
        }
        override fun getStreamUrl(magnetLink: String, fileIdx: Int): String = "http://127.0.0.1:${8000 + starts}/stream?index=$fileIdx"
    }

    private class Environment {
        var network: Set<String>? = setOf("wifi:192.0.2.1")
        var sleepMs = 0L
        fun read(time: Long) = DesktopP2pEnvironment(time + sleepMs, time, network)
    }

    private fun TestScope.engine(backend: Backend, environment: Environment = Environment()) = DesktopP2pSession(
        backend, backgroundScope, { P2pSettingsUiState(p2pEnabled = true) }, { environment.read(testScheduler.currentTime) },
    )

    @Test fun sleepRebuildsTheEngineAndPublishesANewPlaybackUrl() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        val first = engine.startStream(request)
        environment.sleepMs += 120_000L
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        val state = assertIs<P2pStreamingState.Streaming>(engine.state.value)
        assertTrue(state.localUrl != first)
        assertEquals(request, state.request)
        assertEquals(backend.requests.first(), backend.requests.last())
        engine.stopStream().join()
    }

    @Test fun startupPreparationAndSourceChangesReuseTheReadyEngine() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        engine.prepare().join()
        assertEquals(1, backend.starts)
        assertIs<P2pStreamingState.Idle>(engine.state.value)
        engine.startStream(request)
        engine.stopStream().join()
        assertTrue(backend.running)
        assertEquals(listOf(request.infoHash), backend.dropped)
        engine.startStream(request.copy(infoHash = "b".repeat(40)))
        assertEquals(1, backend.starts)
        engine.shutdown().join()
        assertFalse(backend.running)
    }

    @Test fun networkChangeWhileIdleRebuildsTheWarmedEngine() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.prepare().join()
        environment.network = setOf("vpn:192.0.2.8")
        engine.startStream(request)
        assertEquals(2, backend.starts)
        engine.shutdown().join()
    }

    @Test fun ordinaryIdleTimeAndLongPlaybackDoNotRebuildTheWarmedEngine() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        engine.prepare().join()
        advanceTimeBy(120_001L)
        assertEquals(1, backend.starts)
        engine.startStream(request)
        assertEquals(1, backend.starts)
        advanceTimeBy(120_001L)
        engine.stopStream().join()
        advanceTimeBy(60_001L)
        engine.startStream(request.copy(infoHash = "b".repeat(40)))
        assertEquals(1, backend.starts)
        engine.shutdown().join()
    }

    @Test fun sleepWhileIdleRebuildsBeforeTheNextPlayback() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.prepare().join()
        environment.sleepMs += 120_000L
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        assertIs<P2pStreamingState.Idle>(engine.state.value)
        engine.startStream(request)
        assertEquals(2, backend.starts)
        engine.shutdown().join()
    }

    @Test fun idleRecoveryWaitsForAUsableStableNetworkAndStopsOnShutdown() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.prepare().join()
        environment.network = emptySet()
        advanceTimeBy(30_001L)
        assertEquals(1, backend.starts)
        environment.network = setOf("ethernet:192.0.2.2")
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        engine.shutdown().join()
        environment.sleepMs += 120_000L
        environment.network = setOf("wifi:192.0.2.8")
        advanceTimeBy(30_001L)
        assertEquals(2, backend.starts)
        assertFalse(backend.running)
    }

    @Test fun shutdownCancelsAnIdleRecoveryThatIsStarting() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.prepare().join()
        backend.startGate = CompletableDeferred()
        environment.sleepMs += 120_000L
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        engine.shutdown().join()
        backend.startGate!!.complete(Unit)
        advanceTimeBy(30_001L)
        assertFalse(backend.running)
        assertEquals(2, backend.starts)
    }

    @Test fun changedProfileSettingsReprepareEvenWhenP2pRemainsEnabled() = runTest {
        val backend = Backend()
        var settings = P2pSettingsUiState(p2pEnabled = true, enableUpload = false)
        val engine = DesktopP2pSession(backend, backgroundScope, { settings })
        engine.prepare().join()
        settings = settings.copy(cacheSize = P2pCacheSize.NONE, torrentProfile = P2pTorrentProfile.FAST)
        engine.prepare().join()
        assertEquals(2, backend.starts)
        engine.shutdown().join()
    }

    @Test fun networkLossWaitsUntilTheNewInterfaceIsStable() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.startStream(request)
        environment.network = emptySet()
        advanceTimeBy(10_001L)
        assertEquals(1, backend.starts)
        environment.network = setOf("ethernet:192.0.2.2")
        advanceTimeBy(2_000L)
        assertEquals(1, backend.starts)
        advanceTimeBy(4_001L)
        assertEquals(2, backend.starts)
        advanceTimeBy(8_000L)
        assertEquals(2, backend.starts)
        engine.stopStream().join()
    }

    @Test fun repeatedLocalApiFailuresRecoverAnUnhealthyEngineButZeroPeersDoNot() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        engine.startStream(request)
        advanceTimeBy(30_001L)
        assertEquals(1, backend.starts)
        backend.failures = 3
        backend.healthy = false
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        assertIs<P2pStreamingState.Streaming>(engine.state.value)
        engine.stopStream().join()
    }

    @Test fun threeSlowStatusFailuresDoNotInterruptAHealthyStream() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        val url = engine.startStream(request)
        backend.failures = 3
        backend.statusDelayMs = 8_000L
        advanceTimeBy(32_001L)
        assertEquals(1, backend.starts)
        assertEquals(1, backend.healthChecks)
        assertEquals(url, assertIs<P2pStreamingState.Streaming>(engine.state.value).localUrl)
        engine.stopStream().join()
    }

    @Test fun missingActiveTorrentRecoversEvenWhileTheProcessIsHealthy() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        val url = engine.startStream(request)
        backend.failures = 3
        backend.statusHttpCode = 404
        advanceTimeBy(6_001L)
        assertEquals(2, backend.starts)
        assertTrue(assertIs<P2pStreamingState.Streaming>(engine.state.value).localUrl != url)
        engine.stopStream().join()
    }

    @Test fun cancellingDuringAHealthProbeDoesNotRestartOrResurrectPlayback() = runTest {
        val backend = Backend()
        val engine = engine(backend)
        engine.startStream(request)
        backend.failures = 3
        backend.healthGate = CompletableDeferred()
        advanceTimeBy(6_001L)
        assertEquals(1, backend.healthChecks)
        engine.stopStream().join()
        backend.healthGate!!.complete(Unit)
        advanceTimeBy(10_001L)
        assertEquals(1, backend.starts)
        assertIs<P2pStreamingState.Idle>(engine.state.value)
    }

    @Test fun aPersistentlyBrokenBackendStopsAfterBoundedRecovery() = runTest {
        val backend = Backend().apply { failAfterMetadata = true }
        val engine = engine(backend)
        engine.startStream(request)
        advanceTimeBy(30_001L)
        assertEquals(3, backend.starts)
        assertIs<P2pStreamingState.Error>(engine.state.value)
        assertFalse(backend.running)
    }

    @Test fun stopWhileRecoveryIsStartingDoesNotResurrectTheSession() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.startStream(request)
        backend.startGate = CompletableDeferred()
        environment.sleepMs += 60_000L
        advanceTimeBy(6_001L)
        assertIs<P2pStreamingState.Connecting>(engine.state.value)
        val stop = engine.stopStream()
        runCurrent()
        stop.join()
        backend.startGate!!.complete(Unit)
        advanceTimeBy(10_001L)
        assertIs<P2pStreamingState.Idle>(engine.state.value)
        assertFalse(backend.running)
        assertEquals(2, backend.starts)
    }

    @Test fun aNewSourceCancelsOldMetadataAndQueuedCleanupCannotKillIt() = runTest {
        val backend = Backend().apply { metadata = false }
        val engine = engine(backend)
        val first = async { engine.startStream(request) }
        runCurrent()
        engine.stopStream()
        backend.metadata = true
        val second = async { engine.startStream(request.copy(infoHash = "b".repeat(40))) }
        runCurrent()
        second.await()
        assertTrue(first.isCancelled)
        assertTrue(backend.running)
        assertIs<P2pStreamingState.Streaming>(engine.state.value)
        assertEquals("b".repeat(40), (engine.state.value as P2pStreamingState.Streaming).request?.infoHash)
        engine.stopStream().join()
    }

    @Test fun cancellingAStartCleansUpItsOwnedProcess() = runTest {
        val backend = Backend().apply { metadata = false }
        val engine = engine(backend)
        val start = async { engine.startStream(request) }
        runCurrent()
        assertTrue(backend.running)
        start.cancel()
        runCurrent()
        start.join()
        assertFalse(backend.running)
        assertIs<P2pStreamingState.Idle>(engine.state.value)
    }

    @Test fun aWakeUpDuringMetadataLoadingRebuildsBeforeContinuing() = runTest {
        val backend = Backend().apply { metadata = false }
        val environment = Environment()
        val engine = engine(backend, environment)
        val start = async { engine.startStream(request) }
        advanceTimeBy(1_001L)
        environment.sleepMs += 120_000L
        advanceTimeBy(3_001L)
        assertEquals(2, backend.starts)
        backend.metadata = true
        advanceTimeBy(2_001L)
        assertTrue(start.await().contains("8002"))
        assertIs<P2pStreamingState.Streaming>(engine.state.value)
        engine.stopStream().join()
    }

    @Test fun aTransientStartupFailureDuringRecoveryGetsOneRetry() = runTest {
        val backend = Backend()
        val environment = Environment()
        val engine = engine(backend, environment)
        engine.startStream(request)
        backend.startupFailures = 1
        environment.sleepMs += 60_000L
        advanceTimeBy(8_001L)
        assertEquals(3, backend.starts)
        assertTrue(backend.running)
        assertIs<P2pStreamingState.Streaming>(engine.state.value)
        engine.stopStream().join()
    }
}
