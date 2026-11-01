package com.nuvio.app.features.p2p

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.EpisodeStreamsPanelState
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.activePlaybackKey
import com.nuvio.app.features.player.applyP2pRecoveryState
import com.nuvio.app.features.player.currentP2pStreamRequest
import com.nuvio.app.features.player.handlePlayerControlsEvent
import com.nuvio.app.features.player.testPlayerScreenArgs
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        var peers = 0
        var healthChecks = 0
        var statusDelayMs = 0L
        var statusHttpCode: Int? = null
        var healthGate: CompletableDeferred<Unit>? = null
        var startupFailures = 0
        var metadata = true
        var files = listOf(TorrServerFile(1, "sample.mp4", 100))
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
            return TorrServerStats(0, 0, peers, 0, 10, 20, 100,
                if (metadata) files else emptyList())
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

    @Test fun selectingAnEpisodeSourceFromTheSamePackChangesTheActualTorrentFile() = runTest {
        P2pSettingsRepository.ensureLoaded()
        val wasEnabled = P2pSettingsRepository.uiState.value.p2pEnabled
        P2pSettingsRepository.setP2pEnabled(false)
        val backend = Backend().apply {
            files = listOf(
                TorrServerFile(1, "Series.S01E01.mkv", 900),
                TorrServerFile(2, "Series.S01E02.mkv", 500),
            )
        }
        val engine = engine(backend)
        try {
            val runtime = PlayerScreenRuntime(testPlayerScreenArgs().copy(
                contentType = "series", parentMetaType = "series", videoId = "tt1234567:1:1",
                seasonNumber = 1, episodeNumber = 1, torrentInfoHash = request.infoHash,
            )).apply { scope = backgroundScope }
            val previousKey = runtime.activePlaybackKey
            val previousRequest = assertNotNull(runtime.currentP2pStreamRequest())
            val firstUrl = engine.startStream(previousRequest)
            runtime.p2pResolvedSourceUrl = firstUrl
            val episode = MetaVideo(id = "tt1234567:1:2", title = "Episode two", season = 1, episode = 2)
            val stream = StreamItem(infoHash = request.infoHash, addonName = "Pack", addonId = "pack")
            runtime.episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
            runtime.episodeStreamsRepoState = StreamsUiState(groups = listOf(AddonStreamGroup("Pack", "pack", listOf(stream))))

            assertTrue(runtime.handlePlayerControlsEvent("selectEpisodeStream", 0.0))
            assertEquals(episode, runtime.playerControlsPendingP2pSwitch?.episode)
            assertEquals("tt1234567:1:1", runtime.activeVideoId)
            assertTrue(runtime.handlePlayerControlsEvent("enableP2pForPlayerControls", 0.0))

            val nextRequest = assertNotNull(runtime.currentP2pStreamRequest())
            assertEquals(1, nextRequest.season)
            assertEquals(2, nextRequest.episode)
            assertEquals(episode.id, runtime.activeVideoId)
            assertNotEquals(previousKey, runtime.activePlaybackKey)
            assertTrue(runtime.p2pRequestGeneration > 0L)
            assertNull(runtime.p2pResolvedSourceUrl)
            assertNull(runtime.playerControlsPendingP2pSwitch)
            assertFalse(runtime.episodeStreamsPanelState.showStreams)

            runtime.applyP2pRecoveryState(P2pStreamingState.Streaming(
                firstUrl, 0, 0, 0, 0, 0f, 0f, request = previousRequest))
            assertNull(runtime.p2pResolvedSourceUrl)

            val nextUrl = engine.startStream(nextRequest)
            assertTrue(firstUrl.endsWith("index=1"))
            assertTrue(nextUrl.endsWith("index=2"))
            assertNotEquals(firstUrl, nextUrl)
            runtime.applyP2pRecoveryState(assertIs<P2pStreamingState.Streaming>(engine.state.value))
            assertEquals(nextUrl, runtime.p2pResolvedSourceUrl)
        } finally {
            PluginRepository.setLocalPluginSearchPaused(false)
            engine.stopStream().join()
            P2pSettingsRepository.setP2pEnabled(wasEnabled)
        }
    }

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

    @Test fun episodeFileMatchingAcceptsUniqueSeasonAndAnimeNames() = runTest {
        for (path in listOf(
            "Series.S02E02.mkv", "Series.2x02.mkv", "Anime - 02.mkv",
            "[Group] Anime - 02 [1080p].mkv", "Anime.EP02.mkv", "Season 2/Anime - 02.mkv",
            "Season 2\\Anime - 02.mkv",
            "Series.S02E02.1920x1080.mkv", "Series.2x02.640x480.mkv",
        )) {
            val backend = Backend().apply {
                files = listOf(TorrServerFile(1, "Series.S02E01.mkv", 900), TorrServerFile(2, path, 500))
            }
            val engine = engine(backend)
            try {
                assertTrue(engine.startStream(request.copy(filename = null, season = 2, episode = 2)).endsWith("index=2"), path)
            } finally { engine.stopStream().join() }
        }
    }

    @Test fun episodeFileMatchingRejectsMissingAmbiguousAndConflictingEpisodes() = runTest {
        for (paths in listOf(
            listOf("Series.S02E01.mkv", "Series.S02E03.mkv"),
            listOf("Series.S02E02.720p.mkv", "Series.S02E02.1080p.mkv"),
            listOf("Season 1/Anime - 02.mkv", "Season 2/Anime - 01.mkv"),
            listOf("Anime - 02.720p.mkv", "Anime - 02.1080p.mkv"),
            listOf("Series.S02E20.mkv", "Series.S02E21.mkv"),
            listOf("Series.S01E02.mkv"),
        )) {
            val backend = Backend().apply { files = paths.mapIndexed { index, path -> TorrServerFile(index + 1, path, 500) } }
            val engine = engine(backend)
            try {
                val failure = assertFailsWith<P2pStreamingException>(paths.toString()) {
                    engine.startStream(request.copy(filename = null, season = 2, episode = 2))
                }
                assertTrue(failure.message.orEmpty().contains("episode"))
            } finally { engine.stopStream().join() }
        }
    }

    @Test fun explicitFileHintsAndMovieAndSingleVideoFallbacksKeepTheirSelection() = runTest {
        val files = listOf(TorrServerFile(1, "Series.S01E01.mkv", 900), TorrServerFile(2, "Series.S01E02.mkv", 500))
        val target = request.copy(filename = null, season = 1, episode = 2)
        for ((selected, index) in listOf(
            target.copy(filename = "Series.S01E01.mkv") to 1,
            target.copy(fileIdx = 0) to 2,
        )) {
            val backend = Backend().apply { this.files = files }
            val engine = engine(backend)
            try { assertTrue(engine.startStream(selected).endsWith("index=$index")) }
            finally { engine.stopStream().join() }
        }
        val ambiguous = engine(Backend().apply { this.files = files })
        try {
            assertFailsWith<P2pStreamingException> { ambiguous.startStream(request.copy(filename = null)) }
        } finally { ambiguous.stopStream().join() }
        for (path in listOf("sample.mp4", "Series.1920x1080.mkv")) {
            val engine = engine(Backend().apply { this.files = listOf(TorrServerFile(1, path, 100)) })
            try { assertTrue(engine.startStream(target).endsWith("index=1"), path) }
            finally { engine.stopStream().join() }
        }
    }

    @Test fun ambiguousFilenameHintsRequireAnIdentifiableEpisode() = runTest {
        val pack = listOf(
            TorrServerFile(1, "Series/Series.S02E01.mkv", 900),
            TorrServerFile(2, "Series/Series.S02E02.mkv", 500),
        )
        val duplicateNames = listOf(
            TorrServerFile(1, "Season 1/Episode02.mkv", 900),
            TorrServerFile(2, "Season 2/Episode02.mkv", 500),
        )
        for ((files, selected) in listOf(
            pack to request.copy(filename = "Series", season = 2, episode = 2),
            duplicateNames to request.copy(filename = "Episode02.mkv", season = 2, episode = 2),
        )) {
            val engine = engine(Backend().apply { this.files = files })
            try { assertTrue(engine.startStream(selected).endsWith("index=2"), selected.toString()) }
            finally { engine.stopStream().join() }
        }
        val ambiguous = engine(Backend().apply { files = pack })
        try {
            assertFailsWith<P2pStreamingException> {
                ambiguous.startStream(request.copy(filename = "Series", fileIdx = 1))
            }
        } finally { ambiguous.stopStream().join() }
    }

    @Test fun providerFileIndexDoesNotSilentlySelectAPathSortedTorrentFile() = runTest {
        val backend = Backend().apply {
            files = listOf(
                TorrServerFile(1, "Show.S01E10.mkv", 400),
                TorrServerFile(2, "Show.S01E02.mkv", 500),
            )
        }
        val engine = engine(backend)
        try {
            assertFailsWith<P2pStreamingException> {
                engine.startStream(request.copy(filename = null, fileIdx = 1))
            }
            assertTrue(engine.startStream(request.copy(
                filename = null, fileIdx = 1, season = 1, episode = 2,
            )).endsWith("index=2"))
        } finally { engine.stopStream().join() }
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
        assertTrue(backend.dropped.isEmpty(), "Quick episode switches should retain peers")
        engine.startStream(request.copy(infoHash = "b".repeat(40)))
        assertEquals(1, backend.starts)
        assertEquals(listOf(request.infoHash), backend.dropped)
        engine.shutdown().join()
        assertFalse(backend.running)
    }

    @Test fun repeatedEpisodeUsesWarmTorrentAndInactiveTorrentIsEventuallyDropped() = runTest {
        val backend = Backend().apply {
            files = listOf(TorrServerFile(1, "Show.S01E01.mkv", 400), TorrServerFile(2, "Show.S01E02.mkv", 500))
        }
        val engine = engine(backend)
        val episode1 = request.copy(filename = "Show.S01E01.mkv")
        val episode2 = request.copy(filename = "Show.S01E02.mkv")
        engine.startStream(episode1)
        engine.stopStream().join()
        advanceTimeBy(30_000L)
        engine.startStream(episode2)
        assertEquals(1, backend.starts)
        assertTrue(backend.dropped.isEmpty())
        assertEquals(2, backend.requests.size)
        engine.stopStream().join()
        advanceTimeBy(90_001L)
        assertEquals(listOf(request.infoHash), backend.dropped)
        engine.shutdown().join()
    }

    @Test fun torrentWideCacheOccupancyIsNotMisreportedAsDeliveredVideo() = runTest {
        val engine = engine(Backend())
        try {
            engine.startStream(request)
            advanceTimeBy(2_001L)
            runCurrent()
            val stats = assertIs<P2pStreamingState.Streaming>(engine.state.value)
            assertEquals(0.2f, stats.totalProgress)
            assertEquals(0f, stats.bufferProgress)
            assertEquals(20L, stats.downloadedBytes)
            assertEquals(0L, stats.verifiedBytes)
            assertEquals(0L, stats.deliveredBytes)
            val cache = engine.cacheState.value
            assertEquals(10L, cache.usedBytes)
            assertEquals(0L, cache.protectedBytes)
        } finally {
            engine.shutdown().join()
        }
    }

    @Test fun aTemporaryMetadataStatusFailureDoesNotDropPeerDiscovery() = runTest {
        val backend = Backend().apply { failures = 1 }
        val engine = engine(backend)
        assertTrue(engine.startStream(request).endsWith("index=1"))
        assertEquals(1, backend.starts)
        assertTrue(backend.dropped.isEmpty())
        engine.shutdown().join()
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

    @Test fun virtualInterfaceChangesDoNotInterruptHealthyConnectedPeers() = runTest {
        val backend = Backend().apply { peers = 4 }
        val environment = Environment()
        val engine = engine(backend, environment)
        val initialUrl = engine.startStream(request)
        environment.network = setOf("wifi:192.0.2.1", "vpn:192.0.2.22")
        advanceTimeBy(10_001L)
        assertEquals(1, backend.starts, "An unrelated interface must not destroy a connected swarm")
        assertEquals(initialUrl, assertIs<P2pStreamingState.Streaming>(engine.state.value).localUrl)
        engine.shutdown().join()
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
