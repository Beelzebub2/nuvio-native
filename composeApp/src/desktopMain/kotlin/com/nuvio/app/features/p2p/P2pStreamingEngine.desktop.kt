package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedP2pUnknownTorrentError
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

actual object P2pStreamingEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var readinessWatcher: Job? = null
    private val engine = DesktopP2pSession(
        backend = TorrServerBackend(),
        scope = scope,
        settings = {
            P2pSettingsRepository.ensureLoaded()
            P2pSettingsRepository.uiState.value
        },
    )
    actual val state: StateFlow<P2pStreamingState> = engine.state
    actual val cacheState: StateFlow<P2pCacheUiState> = engine.cacheState

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            readinessWatcher?.cancel()
            runCatching { runBlocking { engine.shutdown().join() } }
        }.apply { name = "nuvio-torrserver-shutdown" })
    }

    actual suspend fun startStream(request: P2pStreamRequest): String =
        withContext(Dispatchers.IO) { engine.startStream(request) }
    actual fun stopStream() { engine.stopStream() }
    actual fun shutdown() { readinessWatcher?.cancel(); engine.shutdown() }
    fun prepare() {
        P2pSettingsRepository.ensureLoaded()
        if (readinessWatcher != null) return
        readinessWatcher = scope.launch {
            P2pSettingsRepository.uiState.collectLatest { settings ->
                if (settings.p2pEnabled) engine.prepare().join() else engine.stopStream(closeEngine = true).join()
            }
        }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = engine.whileInactive {
        withContext(Dispatchers.IO) {
            val directory = DesktopStorage.cacheDir.resolve("torrents")
            var reclaimed = 0L
            if (Files.isDirectory(directory)) Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).filter { it != directory }.forEach { path ->
                    if (Files.isRegularFile(path)) reclaimed += Files.size(path)
                    Files.deleteIfExists(path)
                }
            }
            P2pCacheClearResult(reclaimedBytes = reclaimed, remainingBytes = 0L, protectedBytes = 0L)
        }
    }
}

/** One mutex owns every backend operation, including cleanup and automatic recovery. */
internal class DesktopP2pSession(
    private val backend: DesktopTorrentBackend,
    private val scope: CoroutineScope,
    private val settings: () -> P2pSettingsUiState,
    private val environment: () -> DesktopP2pEnvironment = ::desktopP2pEnvironment,
) {
    private val log = Logger.withTag("P2pStreamingEngine")
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState(hasMeasurement = true))
    val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()
    private val intentLock = Any()
    private val backendLock = Mutex()
    private var intent = 0L
    private var startJob: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var warmupJob: Job? = null
    private var activeHash: String? = null
    private var preparedEnvironment: DesktopP2pEnvironment? = null
    @Volatile private var closing = false

    fun prepare(): Job {
        warmupJob?.cancel()
        return scope.launch {
            backendLock.withLock {
                if (closing || _state.value !is P2pStreamingState.Idle) return@withLock
                synchronized(intentLock) { intent += 1 }
                try {
                    if (needsNetworkRecovery()) backend.restart(settings()) else backend.prepare(settings())
                    preparedEnvironment = environment()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { backend.stop() }
                    throw e
                } catch (e: Exception) {
                    backend.stop()
                    log.w(e) { "Background torrent engine preparation failed; playback will retry" }
                }
            }
        }.also { warmupJob = it }
    }

    fun shutdown(): Job {
        closing = true
        warmupJob?.cancel()
        return stopStream(closeEngine = true)
    }

    suspend fun startStream(request: P2pStreamRequest): String = coroutineScope {
        val job = currentCoroutineContext().job
        val currentIntent = synchronized(intentLock) {
            intent += 1
            startJob?.cancel()
            monitorJob?.cancel()
            monitorJob = null
            startJob = job
            _state.value = P2pStreamingState.Connecting()
            intent
        }
        try {
            backendLock.withLock {
                ensureCurrent(currentIntent)
                try {
                    releaseTorrent()
                    val session = connectWithRetry(request, currentIntent)
                    startMonitoring(session, currentIntent)
                    session.url
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { backend.stop() }
                    publish(currentIntent, P2pStreamingState.Idle)
                    throw e
                } catch (e: Exception) {
                    fail(currentIntent, e)
                    throw e
                }
            }
        } finally {
            synchronized(intentLock) { if (startJob === job) startJob = null }
        }
    }

    fun stopStream(closeEngine: Boolean = false): Job {
        if (closeEngine) warmupJob?.cancel()
        val stopIntent = synchronized(intentLock) {
            intent += 1
            startJob?.cancel()
            monitorJob?.cancel()
            startJob = null
            monitorJob = null
            _state.value = P2pStreamingState.Idle
            _cacheState.value = P2pCacheUiState(hasMeasurement = true)
            intent
        }
        return scope.launch {
            backendLock.withLock {
                // A delayed stop must never kill the process belonging to a newer source.
                if (isCurrent(stopIntent)) {
                    if (closeEngine || !settings().p2pEnabled) { backend.stop(); activeHash = null } else releaseTorrent()
                }
            }
        }
    }

    suspend fun <T> whileInactive(action: suspend () -> T): T = backendLock.withLock {
        synchronized(intentLock) {
            check(_state.value !is P2pStreamingState.Connecting && _state.value !is P2pStreamingState.Streaming) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
        }
        try {
            backend.stop()
            action()
        } finally {
            _cacheState.value = P2pCacheUiState(hasMeasurement = true)
        }
    }

    private data class Session(
        val request: P2pStreamRequest,
        val hash: String,
        val url: String,
        val signals: DesktopP2pRecoverySignals,
    )

    private suspend fun releaseTorrent() {
        val hash = activeHash ?: return
        activeHash = null
        try { backend.dropTorrent(hash) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { backend.stop(); log.w(e) { "Stopping an unresponsive torrent engine" } }
    }

    private suspend fun connectWithRetry(request: P2pStreamRequest, currentIntent: Long, recovering: Boolean = false): Session {
        repeat(2) { attempt ->
            ensureCurrent(currentIntent)
            try {
                val signals = DesktopP2pRecoverySignals(environment())
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "starting_engine", request = request))
                val stale = needsNetworkRecovery()
                if (recovering || attempt > 0 || stale) backend.restart(settings()) else backend.prepare(settings())
                activeHash = null
                preparedEnvironment = environment()
                ensureCurrent(currentIntent)
                val magnet = buildP2pMagnetUri(request.infoHash, (DEFAULT_TRACKERS + request.trackers).distinct())
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "add_magnet", request = request))
                val hash = backend.addTorrent(magnet)
                activeHash = hash
                ensureCurrent(currentIntent)
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "prepare_stream", request = request))
                val index = resolveFileIndex(hash, request.fileIdx, request.filename, currentIntent, signals)
                ensureCurrent(currentIntent)
                val url = backend.getStreamUrl(magnet, index)
                publish(currentIntent, P2pStreamingState.Streaming(url, 0L, 0L, 0, 0, 0f, 0f, request = request))
                return Session(request, hash, url, signals)
            } catch (e: DesktopTorrentEngineUnavailable) {
                if (attempt == 1) throw e
                log.w(e) { "Rebuilding the unresponsive torrent engine" }
                backend.stop()
                delay(1_000L)
            }
        }
        error("Unreachable")
    }

    private fun needsNetworkRecovery(): Boolean = preparedEnvironment?.let { previous ->
        DesktopP2pRecoverySignals(previous).apply { observe(environment()) }.recoveryPending
    } == true

    private fun startMonitoring(initial: Session, currentIntent: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var session = initial
            var failures = 0
            var healthySamples = 0
            var healthRecoveries = 0
            while (isActive && isCurrent(currentIntent)) {
                delay(2_000L)
                backendLock.withLock {
                    ensureCurrent(currentIntent)
                    var reason = session.signals.observe(environment())
                    if (reason == null) {
                        try {
                            val stats = backend.getTorrentStats(session.hash)
                            failures = 0
                            healthySamples += 1
                            if (healthySamples >= 10) healthRecoveries = 0
                            updateStats(currentIntent, stats)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.w(e) { "Torrent engine status unavailable" }
                            healthySamples = 0
                            failures += 1
                            if (failures >= 3 && !session.signals.waitingForNetwork) {
                                reason = DesktopP2pRecoveryReason.EngineUnavailable
                            }
                        }
                    }
                    if (reason != null) {
                        try {
                            if (reason == DesktopP2pRecoveryReason.EngineUnavailable && ++healthRecoveries > 2) {
                                throw DesktopTorrentEngineUnavailable("Torrent engine could not recover. Retry this source or choose another.")
                            }
                            log.i { "Recovering torrent session after $reason" }
                            session = connectWithRetry(session.request, currentIntent, recovering = true)
                            failures = 0
                            healthySamples = 0
                        } catch (e: CancellationException) {
                            // The mutex prevents this cleanup from reaching a newer process.
                            withContext(NonCancellable) { backend.stop() }
                            throw e
                        } catch (e: Exception) {
                            fail(currentIntent, e)
                            return@launch
                        }
                    }
                }
            }
        }
        synchronized(intentLock) {
            if (intent == currentIntent) {
                monitorJob = job
                job.start()
            } else {
                job.cancel()
            }
        }
    }

    private suspend fun fail(currentIntent: Long, error: Exception) {
        withContext(NonCancellable) { backend.stop() }
        activeHash = null
        publish(currentIntent, P2pStreamingState.Error(error.message ?: localizedP2pUnknownTorrentError()))
    }

    private fun updateStats(currentIntent: Long, stats: TorrServerStats) = synchronized(intentLock) {
        if (intent != currentIntent) return@synchronized
        val current = _state.value as? P2pStreamingState.Streaming ?: return@synchronized
        _state.value = current.copy(
            downloadSpeed = stats.downloadSpeed,
            uploadSpeed = stats.uploadSpeed,
            peers = stats.peers,
            seeds = stats.seeds,
            bufferProgress = stats.preloadedBytes.fractionOf(stats.torrentSize),
            totalProgress = stats.loadedSize.fractionOf(stats.torrentSize),
            downloadedBytes = stats.loadedSize,
            verifiedBytes = stats.loadedSize,
            deliveredBytes = stats.preloadedBytes,
        )
        _cacheState.value = P2pCacheUiState(usedBytes = stats.loadedSize, protectedBytes = stats.preloadedBytes, hasMeasurement = true)
    }

    private fun isCurrent(expected: Long): Boolean = synchronized(intentLock) { intent == expected }
    private fun ensureCurrent(expected: Long) {
        if (closing || !isCurrent(expected)) throw CancellationException("A newer P2P request replaced this session")
    }
    private fun publish(expected: Long, state: P2pStreamingState) = synchronized(intentLock) {
        if (intent == expected) _state.value = state
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?, intent: Long, signals: DesktopP2pRecoverySignals): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var files: List<TorrServerFile> = emptyList()

        while (true) {
            ensureCurrent(intent)
            if (signals.observe(environment()) != null) {
                throw DesktopTorrentEngineUnavailable("Network changed while fetching torrent metadata")
            }
            if (signals.recoveryPending) {
                publish(intent, P2pStreamingState.Connecting(phase = "waiting_for_network"))
                delay(1_000L)
                continue
            }
            if (System.nanoTime() >= deadline) break
            files = backend.getTorrentStats(hash).files
            if (files.isNotEmpty()) break
            log.d { "Waiting for torrent metadata..." }
            delay(1_000L)
        }

        if (files.isEmpty()) {
            throw P2pStreamingException("Torrent metadata timed out after 90 seconds. Check peers or choose another source.")
        }

        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            val exact = files.firstOrNull { file ->
                file.path.substringAfterLast('/').equals(name, ignoreCase = true)
            }
            if (exact != null) {
                log.d { "File resolved by exact filename match: ${exact.path} -> id=${exact.id}" }
                return exact.id
            }

            val contains = files.firstOrNull { file ->
                file.path.contains(name, ignoreCase = true)
            }
            if (contains != null) {
                log.d { "File resolved by filename contains match: ${contains.path} -> id=${contains.id}" }
                return contains.id
            }
        }

        if (requestedIdx != null) {
            val torrServerIndex = requestedIdx + 1
            if (files.any { it.id == torrServerIndex }) {
                log.d { "File resolved by ID offset: id=$torrServerIndex" }
                return torrServerIndex
            }
        }

        if (requestedIdx != null && requestedIdx in files.indices) {
            val positionalFile = files[requestedIdx]
            log.d { "File resolved by positional index: [$requestedIdx] -> ${positionalFile.path} (id=${positionalFile.id})" }
            return positionalFile.id
        }

        if (requestedIdx != null) {
            throw P2pStreamingException("The requested torrent file is missing. Choose another source.")
        }

        val videoFile = files
            .filter { file ->
                val ext = file.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        val result = videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
        log.d { "File resolved by largest video fallback: id=$result" }
        return result
    }


    private fun Long.fractionOf(total: Long): Float =
        if (total > 0L) (toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    private companion object {
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )
    }
}
