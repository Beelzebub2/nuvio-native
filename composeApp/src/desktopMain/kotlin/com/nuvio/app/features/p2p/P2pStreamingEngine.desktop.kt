package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedP2pUnknownTorrentError
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.debrid.buildEpisodePattern
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
    private var readinessMonitorJob: Job? = null
    @Volatile private var warmupJob: Job? = null
    private var warmRetentionJob: Job? = null
    private var warmRetentionGeneration = 0L
    private var activeHash: String? = null
    private var preparedSignals: DesktopP2pRecoverySignals? = null
    @Volatile private var closing = false

    fun prepare(): Job {
        warmupJob?.cancel()
        return scope.launch {
            backendLock.withLock {
                if (closing || _state.value !is P2pStreamingState.Idle) return@withLock
                synchronized(intentLock) { intent += 1 }
                try {
                    if (needsNetworkRecovery()) {
                        backend.restart(settings())
                        activeHash = null
                    } else backend.prepare(settings())
                    preparedSignals = DesktopP2pRecoverySignals(environment())
                    startReadinessMonitoring()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { backend.stop() }
                    activeHash = null
                    throw e
                } catch (e: Exception) {
                    backend.stop()
                    activeHash = null
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
                    cancelWarmRetention()
                    // Preserve piece cache and peer connections on a same-hash episode switch.
                    if (!activeHash.equals(request.infoHash, ignoreCase = true)) releaseTorrent()
                    val session = connectWithRetry(request, currentIntent)
                    startMonitoring(session, currentIntent)
                    session.url
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { backend.stop() }
                    activeHash = null
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
        if (closeEngine) { warmupJob?.cancel(); readinessMonitorJob?.cancel() }
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
                    cancelWarmRetention()
                    if (closeEngine || !settings().p2pEnabled) {
                        readinessMonitorJob?.cancel()
                        backend.stop()
                        activeHash = null
                        preparedSignals = null
                    } else if (activeHash != null) {
                        retainTorrentBriefly()
                    }
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
            cancelWarmRetention()
            backend.stop()
            activeHash = null
            preparedSignals = null
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

    /** Keep peers warm for the next episode, then release them after inactivity. */
    private fun retainTorrentBriefly() {
        val generation = ++warmRetentionGeneration
        warmRetentionJob = scope.launch {
            delay(WARM_TORRENT_RETENTION_MS)
            backendLock.withLock {
                if (warmRetentionGeneration == generation && _state.value is P2pStreamingState.Idle && !closing) {
                    releaseTorrent()
                }
            }
        }
    }

    private fun cancelWarmRetention() {
        warmRetentionGeneration += 1
        warmRetentionJob?.cancel()
        warmRetentionJob = null
    }

    private suspend fun connectWithRetry(request: P2pStreamRequest, currentIntent: Long, recovering: Boolean = false): Session {
        repeat(2) { attempt ->
            ensureCurrent(currentIntent)
            try {
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "starting_engine", request = request))
                val stale = needsNetworkRecovery()
                if (recovering || attempt > 0 || stale) {
                    backend.restart(settings())
                    activeHash = null
                } else backend.prepare(settings())
                val signals = DesktopP2pRecoverySignals(environment())
                preparedSignals = signals
                startReadinessMonitoring()
                ensureCurrent(currentIntent)
                val magnet = buildP2pMagnetUri(request.infoHash, (DEFAULT_TRACKERS + request.trackers).distinct())
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "add_magnet", request = request))
                val hash = backend.addTorrent(magnet)
                activeHash = hash
                ensureCurrent(currentIntent)
                publish(currentIntent, P2pStreamingState.Connecting(if (recovering) "recovering_engine" else "prepare_stream", request = request))
                val index = resolveFileIndex(hash, request, currentIntent, signals)
                ensureCurrent(currentIntent)
                val url = backend.getStreamUrl(magnet, index)
                publish(currentIntent, P2pStreamingState.Streaming(url, 0L, 0L, 0, 0, 0f, 0f, request = request))
                return Session(request, hash, url, signals)
            } catch (e: DesktopTorrentEngineUnavailable) {
                if (attempt == 1) throw e
                log.w(e) { "Rebuilding the unresponsive torrent engine" }
                backend.stop()
                activeHash = null
                delay(1_000L)
            }
        }
        error("Unreachable")
    }

    private fun needsNetworkRecovery(): Boolean = preparedSignals?.let { signals ->
        signals.observe(environment()) != null || signals.recoveryPending
    } == true

    private fun startReadinessMonitoring() {
        if (readinessMonitorJob?.isActive == true) return
        readinessMonitorJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                backendLock.withLock {
                    if (closing || !settings().p2pEnabled || _state.value !is P2pStreamingState.Idle) return@withLock
                    val reason = preparedSignals?.observe(environment()) ?: return@withLock
                    try {
                        backend.restart(settings())
                        activeHash = null
                        preparedSignals = DesktopP2pRecoverySignals(environment())
                        log.i { "Prepared torrent engine after $reason" }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { backend.stop() }
                        throw e
                    } catch (e: Exception) {
                        backend.stop()
                        activeHash = null
                        preparedSignals = null
                        log.w(e) { "Idle torrent engine recovery failed; playback will retry" }
                    }
                }
            }
        }
    }

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
                                // Status/serialization failures do not prove the media reader is broken.
                                // A missing active hash does: /echo can still succeed in that case.
                                val torrentMissing = e is DesktopTorrentEngineUnavailable && e.httpStatusCode == 404
                                if (torrentMissing || !backend.isHealthy()) {
                                    reason = DesktopP2pRecoveryReason.EngineUnavailable
                                } else {
                                    failures = 0
                                }
                            }
                        }
                    }
                    if (reason == DesktopP2pRecoveryReason.NetworkChange) {
                        // VPN/virtual adapters can change without changing the swarm's
                        // actual route. A peer count alone is not evidence of a usable
                        // connection: dead peers can remain listed until timeout. Only
                        // preserve the HTTP reader if payload is actively arriving.
                        val swarmStillDownloading = try {
                            if (!backend.isHealthy()) false else {
                                val stats = backend.getTorrentStats(session.hash)
                                stats.peers > 0 && stats.downloadSpeed > 0
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (swarmStillDownloading) {
                            log.i { "Network interfaces changed but torrent traffic continues; keeping playback" }
                            reason = null
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
            // TorrServer's preloaded_bytes is cache occupancy for the whole torrent, not
            // continuous playable bytes ahead of this episode. The player measures its
            // actual demuxer buffer separately; do not report a false file buffer here.
            bufferProgress = 0f,
            totalProgress = stats.loadedSize.fractionOf(stats.torrentSize),
            downloadedBytes = stats.loadedSize,
            // The API does not expose selected-file verified/delivered byte counters.
            verifiedBytes = 0L,
            deliveredBytes = 0L,
        )
        // loaded_size counts all completed torrent pieces, which can vastly exceed the
        // cache occupancy. TorrServer exposes cache Filled but not a protected subset.
        _cacheState.value = P2pCacheUiState(
            usedBytes = stats.preloadedBytes.coerceAtLeast(0L),
            protectedBytes = 0L,
            hasMeasurement = true,
        )
    }

    private fun isCurrent(expected: Long): Boolean = synchronized(intentLock) { intent == expected }
    private fun ensureCurrent(expected: Long) {
        if (closing || !isCurrent(expected)) throw CancellationException("A newer P2P request replaced this session")
    }
    private fun publish(expected: Long, state: P2pStreamingState) = synchronized(intentLock) {
        if (intent == expected) _state.value = state
    }

    private suspend fun resolveFileIndex(hash: String, request: P2pStreamRequest, intent: Long, signals: DesktopP2pRecoverySignals): Int {
        val requestedIdx = request.fileIdx
        val filename = request.filename
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var files: List<TorrServerFile> = emptyList()

        while (true) {
            ensureCurrent(intent)
            if (System.nanoTime() >= deadline) break
            if (signals.observe(environment()) != null) {
                throw DesktopTorrentEngineUnavailable("Network changed while fetching torrent metadata")
            }
            if (signals.recoveryPending) {
                publish(intent, P2pStreamingState.Connecting(phase = "waiting_for_network", request = request))
                delay(1_000L)
                continue
            }
            files = try {
                backend.getTorrentStats(hash).files
            } catch (e: DesktopTorrentEngineUnavailable) {
                // Retain peer discovery across intermittent local status failures.
                if (e.httpStatusCode == 404 || !backend.isHealthy()) throw e
                log.w(e) { "Transient torrent metadata status failure; retrying" }
                delay(1_000L)
                continue
            }
            if (files.isNotEmpty()) break
            log.d { "Waiting for torrent metadata..." }
            delay(1_000L)
        }

        if (files.isEmpty()) {
            throw P2pStreamingException("Torrent metadata timed out after 90 seconds. Check peers or choose another source.")
        }

        val videos = files.filter {
            it.length > 0L && it.path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        }
        val hint = filename?.trim()?.replace('\\', '/')?.takeIf { it.isNotBlank() }
        val matchingName = if (hint != null) {
            require(hint.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Invalid torrent filename hint"
            }
            videos.filter { file ->
                val path = file.path.replace('\\', '/')
                if ('/' in hint) path.equals(hint, ignoreCase = true) || path.endsWith("/$hint", ignoreCase = true)
                else path.substringAfterLast('/').equals(hint, ignoreCase = true)
            }
        } else emptyList()
        if (matchingName.size == 1) return matchingName.single().id
        if (hint != null && matchingName.isEmpty() &&
            hint.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        ) {
            throw P2pStreamingException("The requested filename is missing in this torrent. Choose another source.")
        }

        if (request.season != null && request.episode != null) {
            val candidates = (matchingName.ifEmpty { videos }).map {
                it to matchesEpisodeFile(it.path, request.season, request.episode)
            }
            val matching = candidates.filter { it.second == true }
            if (matching.size == 1) return matching.single().first.id
            if (candidates.size == 1 && candidates.single().second == null) return candidates.single().first.id
            throw P2pStreamingException("The requested episode is missing or ambiguous in this torrent. Choose another source.")
        }

        if (hint != null) throw P2pStreamingException("The requested filename is missing or ambiguous in this torrent. Choose another source.")
        if (videos.size == 1) return videos.single().id
        if (requestedIdx != null || videos.size > 1) {
            // The provider's index refers to raw torrent metadata order. TorrServer sorts
            // paths before assigning IDs; index+1 or files[index] may select another episode.
            throw P2pStreamingException("This torrent has multiple files. Choose a source with an exact filename or episode hint.")
        }
        if (files.size == 1 && files.single().length > 0L) return files.single().id
        throw P2pStreamingException("No unambiguous playable file was found in the torrent.")
    }


    private fun Long.fractionOf(total: Long): Float =
        if (total > 0L) (toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    private companion object {
        const val WARM_TORRENT_RETENTION_MS = 90_000L
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )
    }
}

// null means a standalone video has no episode hints to contradict its source.
private fun matchesEpisodeFile(path: String, season: Int, episode: Int): Boolean? {
    val normalizedPath = path.replace('\\', '/')
    val seasons = TORRENT_SEASON_PATTERN.findAll(normalizedPath)
    if (seasons.any { match -> match.groupValues.drop(1).first { it.isNotBlank() }.toIntOrNull() != season }) {
        return false
    }
    val name = normalizedPath.substringAfterLast('/')
    if (TORRENT_SEASON_EPISODE_PATTERN.containsMatchIn(name)) {
        return buildEpisodePattern(season, episode)?.containsMatchIn(name) == true
    }
    val numbers = TORRENT_EPISODE_NUMBER_PATTERN.findAll(name).map { it.groupValues[1].toIntOrNull() }.toList()
    return if (numbers.isEmpty()) null else numbers.singleOrNull() == episode
}

private val TORRENT_SEASON_PATTERN = Regex(
    "(?<![a-z0-9])(?:season[ ._-]*(\\d+)|s(\\d+)(?=e\\d|[^a-z0-9]|$)|(\\d{1,2})x\\d+)",
    RegexOption.IGNORE_CASE,
)
private val TORRENT_SEASON_EPISODE_PATTERN = Regex("(?<![a-z0-9])(?:s\\d+e\\d+|\\d{1,2}x\\d+)(?![0-9])", RegexOption.IGNORE_CASE)
private val TORRENT_EPISODE_NUMBER_PATTERN = Regex(
    "(?<![a-z0-9])(?:(?:ep(?:isode)?|e)[ ._-]*|-[ ._-]+)(\\d+)(?![a-z0-9])",
    RegexOption.IGNORE_CASE,
)
