@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.p2p

import cnames.structs.nuvio_engine
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_DISK_CACHE_RECLAIMED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_STREAM_PREPARED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_STREAM_STOPPED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_TORRENT_ERROR
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_TORRENT_METADATA_READY
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_STATUS_NO_EVENT
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_STATUS_OK
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_BALANCED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_FAST
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_SOFT
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_UPLOAD_DISABLED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_UPLOAD_UNLIMITED
import com.nuvio.app.features.p2p.native.nuvio_engine_add_torrent
import com.nuvio.app.features.p2p.native.nuvio_engine_config
import com.nuvio.app.features.p2p.native.nuvio_engine_config_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_create
import com.nuvio.app.features.p2p.native.nuvio_engine_destroy
import com.nuvio.app.features.p2p.native.nuvio_engine_event
import com.nuvio.app.features.p2p.native.nuvio_engine_event_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_get_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_get_stream_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_poll_event
import com.nuvio.app.features.p2p.native.nuvio_engine_prepare_stream
import com.nuvio.app.features.p2p.native.nuvio_engine_reclaim_disk_cache
import com.nuvio.app.features.p2p.native.nuvio_engine_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_stats_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_status_message
import com.nuvio.app.features.p2p.native.nuvio_engine_stop_stream
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_request
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_request_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_stats_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_torrent_request
import com.nuvio.app.features.p2p.native.nuvio_engine_torrent_request_init_sized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLog

private const val StatsPollIntervalMs = 250L
private const val StartupStatsPollIntervalMs = 1_000L
private const val MemoryCacheCapacityBytes = 64L * 1024L * 1024L
private const val EventPollIntervalMs = 20L
private const val MetadataEventTimeoutMs = 120_000L
private const val PrepareEventTimeoutMs = 60_000L
private const val StopEventTimeoutMs = 15_000L
private const val ReclaimEventTimeoutMs = 30_000L

actual object P2pStreamingEngine {
    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
    )

    private data class NativeEvent(
        val type: UInt,
        val requestId: ULong,
        val torrentId: String?,
        val message: String?,
        val fileIndex: UInt?,
        val fileSize: ULong,
        val streamId: String?,
        val streamUrl: String?,
    )

    private data class PendingEvent(
        val requestId: ULong,
        val expectedType: UInt,
        val result: CompletableDeferred<NativeEvent>,
    )

    private data class NativeStream(
        val id: String,
        val url: String,
        val torrentId: String,
        val fileIndex: Int,
        val fileSize: Long,
    )

    private data class AggregateStats(
        val peers: Int,
        val seeds: Int,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val payloadDownloaded: Long,
        val diskUsed: Long,
        val diskProtected: Long,
    )

    private data class StreamStats(
        val fileSize: Long,
        val contiguousReady: Long,
        val verified: Long,
        val delivered: Long,
    )

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    // The lifecycle mutex covers start/stop sequencing. A separate short native
    // gate protects each C call and its handle against polling and destruction.
    private val nativeCallMutex = Mutex()
    private val controlLock = SynchronizedObject()
    private var engine: CPointer<nuvio_engine>? = null
    private var engineSerial = 0L
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private var currentTorrentId: String? = null
    private var startingTorrentId: String? = null
    private var currentStream: NativeStream? = null
    // A terminal event either already stopped this route or handed its stop
    // to a serialized cleanup job. Startup must not issue a second stop.
    private var terminalHandledStreamId: String? = null
    private var statsJob: Job? = null
    private var eventPumpJob: Job? = null
    // Access only while holding nativeCallMutex: one request at a time, and
    // register its waiter before the event pump can consume the reply.
    private var pendingEvent: PendingEvent? = null
    private var activeStartJob: Job? = null
    private var pendingStopJob: Job? = null
    private var stopEpoch = 0L
    private var generation = 0L
    private val knownTorrentIds = mutableMapOf<String, String>()

    actual suspend fun startStream(request: P2pStreamRequest): String {
        val (acceptedStopEpoch, previousStop) = synchronized(controlLock) {
            stopEpoch to pendingStopJob
        }
        previousStop?.join()
        return lifecycleMutex.withLock {
            currentCoroutineContext().ensureActive()
            val job = currentCoroutineContext()[Job]
            synchronized(controlLock) {
                if (acceptedStopEpoch != stopEpoch) {
                    throw CancellationException("P2P stream start superseded by stop")
                }
                activeStartJob = job
            }
            try {
                startStreamLocked(request)
            } finally {
                synchronized(controlLock) {
                    if (activeStartJob === job) activeStartJob = null
                }
            }
        }
    }

    private suspend fun startStreamLocked(request: P2pStreamRequest): String {
        stopLocked(shutdownEngine = false)
        val streamGeneration = synchronized(controlLock) { ++generation }
        _state.value = P2pStreamingState.Connecting()
        var phase = "build_magnet"
        var startupStatsJob: Job? = null
        var preparedStream: NativeStream? = null
        return try {
            val magnet = buildP2pMagnetUri(
                request.infoHash,
                (DefaultTrackers + request.trackers).distinct(),
            )
            phase = "ensure_engine"
            val activeEngine = ensureEngine()
            val baseline = readAggregateStats(activeEngine).payloadDownloaded
            startupStatsJob = startStartupStatsPolling(activeEngine, streamGeneration) { phase }

            phase = "add_magnet"
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            synchronized(controlLock) { startingTorrentId = canonicalHash }
            val torrentId = knownTorrentIds[canonicalHash]
                ?: addMagnet(activeEngine, magnet).also { knownTorrentIds[canonicalHash] = it }
            currentCoroutineContext().ensureActive()
            synchronized(controlLock) { startingTorrentId = torrentId }

            phase = "prepare_stream"
            val stream = prepareStream(
                activeEngine,
                torrentId,
                request.fileIdx,
                request.filename,
            )
            preparedStream = stream
            currentCoroutineContext().ensureActive()
            synchronized(controlLock) {
                if (generation != streamGeneration) {
                    throw CancellationException("P2P stream start superseded")
                }
                currentTorrentId = torrentId
                currentStream = stream
            }
            phase = "attach_route"

            val aggregate = readAggregateStats(activeEngine)
            publishStreaming(streamGeneration, stream, aggregate, baseline, null)
            startStatsPolling(activeEngine, stream, streamGeneration, baseline)
            currentCoroutineContext().ensureActive()
            synchronized(controlLock) {
                if (generation != streamGeneration) {
                    throw P2pStreamingException("Torrent stream stopped during startup")
                }
            }
            log("stream ready fileIndex=${stream.fileIndex} fileBytes=${stream.fileSize}")
            stream.url
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                val delegated = synchronized(controlLock) {
                    preparedStream != null && terminalHandledStreamId == preparedStream.id
                }
                val stopped = if (delegated) null else preparedStream?.let {
                    runCatching { stopNativeStream(activeEngineOrNull(), it.id) }
                }
                if (preparedStream == null || stopped?.isFailure == true) closeEngine()
            }
            synchronized(controlLock) {
                if (generation == streamGeneration) {
                    currentTorrentId = null
                    currentStream = null
                    _state.value = P2pStreamingState.Idle
                } else if (currentStream?.id == preparedStream?.id) {
                    // A native terminal event already published Error. The
                    // canceled startup owns cleanup of this exact route.
                    currentStream = null
                    currentTorrentId = null
                }
            }
            throw cancellation
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val delegated = synchronized(controlLock) {
                    preparedStream != null && terminalHandledStreamId == preparedStream.id
                }
                val stopped = if (delegated) null else preparedStream?.let {
                    runCatching { stopNativeStream(activeEngineOrNull(), it.id) }
                }
                if (preparedStream == null || stopped?.isFailure == true) closeEngine()
            }
            val message = error.message ?: "Unable to start torrent stream"
            synchronized(controlLock) {
                if (generation == streamGeneration) {
                    currentTorrentId = null
                    currentStream = null
                    _state.value = P2pStreamingState.Error(message)
                } else if (currentStream?.id == preparedStream?.id) {
                    currentStream = null
                    currentTorrentId = null
                }
            }
            log("stream failed phase=$phase error=$message")
            throw P2pStreamingException(message)
        } finally {
            withContext(NonCancellable) { startupStatsJob?.cancelAndJoin() }
            synchronized(controlLock) {
                startingTorrentId = null
                if (terminalHandledStreamId == preparedStream?.id) terminalHandledStreamId = null
            }
        }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = lifecycleMutex.withLock {
        check(_state.value !is P2pStreamingState.Connecting &&
            _state.value !is P2pStreamingState.Streaming) {
            "Torrent cache cannot be cleared during active playback"
        }
        _cacheState.value = _cacheState.value.copy(isClearing = true)
        try {
            val activeEngine = ensureEngine()
            val before = readAggregateStats(activeEngine)
            reclaimDiskCache(activeEngine)
            val after = readAggregateStats(activeEngine)
            updateCacheState(after)
            P2pCacheClearResult(
                reclaimedBytes = (before.diskUsed - after.diskUsed).coerceAtLeast(0L),
                remainingBytes = after.diskUsed,
                protectedBytes = after.diskProtected,
            )
        } finally {
            _cacheState.value = _cacheState.value.copy(isClearing = false)
        }
    }

    actual fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    actual fun shutdown() {
        scheduleStop(shutdownEngine = true)
    }

    private fun scheduleStop(shutdownEngine: Boolean) {
        val (stop, startup) = synchronized(controlLock) {
            stopEpoch += 1
            val active = activeStartJob
            val previous = pendingStopJob
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.join()
                    lifecycleMutex.withLock { stopLocked(shutdownEngine) }
                } finally {
                    val thisJob = currentCoroutineContext()[Job]
                    synchronized(controlLock) {
                        if (pendingStopJob === thisJob) pendingStopJob = null
                    }
                }
            }.also { pendingStopJob = it } to active
        }
        startup?.cancel(CancellationException("P2P stream stopped"))
        stop.start()
    }

    private suspend fun stopLocked(shutdownEngine: Boolean) {
        val (stream, oldStats) = synchronized(controlLock) {
            generation += 1
            val detached = currentStream to statsJob
            currentStream = null
            currentTorrentId = null
            statsJob = null
            _state.value = P2pStreamingState.Idle
            detached
        }
        oldStats?.cancelAndJoin()
        val stopResult = stream?.let { runCatching { stopNativeStream(activeEngineOrNull(), it.id) } }
        if (stopResult?.isFailure == true) {
            log("native stream stop failed: ${stopResult?.exceptionOrNull()?.message}")
            // A route still running after a failed stop cannot be reused safely.
            closeEngine()
        }
        if (shutdownEngine) closeEngine()
    }

    private fun startStartupStatsPolling(
        activeEngine: CPointer<nuvio_engine>,
        streamGeneration: Long,
        phase: () -> String,
    ): Job = scope.launch {
        while (isActive && synchronized(controlLock) { generation == streamGeneration }) {
            runCatching { readAggregateStats(activeEngine) }.getOrNull()?.let { stats ->
                synchronized(controlLock) {
                    if (generation == streamGeneration && _state.value is P2pStreamingState.Connecting) {
                        _state.value = P2pStreamingState.Connecting(
                            phase = phase(),
                            downloadSpeed = stats.downloadSpeed,
                            uploadSpeed = stats.uploadSpeed,
                            peers = stats.peers,
                            seeds = stats.seeds,
                        )
                        updateCacheState(stats)
                    }
                }
            }
            delay(StartupStatsPollIntervalMs)
        }
    }

    private fun startStatsPolling(
        activeEngine: CPointer<nuvio_engine>,
        stream: NativeStream,
        streamGeneration: Long,
        payloadBaseline: Long,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && synchronized(controlLock) { generation == streamGeneration }) {
                try {
                    val aggregate = readAggregateStats(activeEngine)
                    val route = readStreamStats(activeEngine, stream.id)
                    updateCacheState(aggregate)
                    publishStreaming(streamGeneration, stream, aggregate, payloadBaseline, route)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    synchronized(controlLock) {
                        if (generation == streamGeneration) {
                            generation += 1
                            _state.value = P2pStreamingState.Error(
                                error.message ?: "Torrent stream stopped unexpectedly"
                            )
                        }
                    }
                    return@launch
                }
                delay(StatsPollIntervalMs)
            }
        }
        val previous = synchronized(controlLock) {
            statsJob.also { statsJob = job }
        }
        previous?.cancel()
        job.start()
    }

    private fun publishStreaming(
        streamGeneration: Long,
        stream: NativeStream,
        aggregate: AggregateStats,
        payloadBaseline: Long,
        route: StreamStats?,
    ) {
        synchronized(controlLock) {
            if (generation != streamGeneration || currentStream?.id != stream.id) return@synchronized
            _state.value = P2pStreamingState.Streaming(
                localUrl = stream.url,
                downloadSpeed = aggregate.downloadSpeed,
                uploadSpeed = aggregate.uploadSpeed,
                peers = aggregate.peers,
                seeds = aggregate.seeds,
                bufferProgress = ratio(route?.contiguousReady ?: 0L, route?.fileSize ?: stream.fileSize),
                totalProgress = ratio(route?.verified ?: 0L, route?.fileSize ?: stream.fileSize),
                downloadedBytes = (aggregate.payloadDownloaded - payloadBaseline).coerceAtLeast(0L),
                verifiedBytes = route?.verified ?: 0L,
                deliveredBytes = route?.delivered ?: 0L,
            )
        }
    }

    private suspend fun ensureEngine(): CPointer<nuvio_engine> {
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configuration = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        engine?.takeIf {
            engineConfigurationKey == configuration && eventPumpJob?.isActive == true
        }?.let { return it }
        closeEngine()
        currentCoroutineContext().ensureActive()

        val stateDirectory = "${NSHomeDirectory()}/Library/Application Support/NuvioEngine/state"
        val cacheDirectory = "${NSHomeDirectory()}/Library/Caches/NuvioEngine/payload"
        createDirectory(stateDirectory)
        createDirectory(cacheDirectory)
        currentCoroutineContext().ensureActive()
        val (created, serial) = nativeCallMutex.withLock {
            val created = memScoped {
                val config = alloc<nuvio_engine_config>()
                nuvio_engine_config_init_sized(config.ptr, sizeOf<nuvio_engine_config>().toUInt())
                config.data_directory = stateDirectory.cstr.getPointer(this)
                config.cache_directory = cacheDirectory.cstr.getPointer(this)
                config.memory_cache_capacity_bytes = MemoryCacheCapacityBytes.toULong()
                config.disk_cache_capacity_bytes = configuration.diskCacheCapacityBytes.toULong()
                config.upload_mode = if (configuration.uploadEnabled) {
                    NUVIO_ENGINE_UPLOAD_UNLIMITED
                } else {
                    NUVIO_ENGINE_UPLOAD_DISABLED
                }
                config.upload_limit_bytes_per_second = 0uL
                config.stream_inactivity_timeout_milliseconds = 0u
                config.warm_torrent_timeout_milliseconds = 60_000u
                config.tls_ca_bundle_path = null
                config.torrent_profile = when (configuration.torrentProfile) {
                    P2pTorrentProfile.SOFT -> NUVIO_ENGINE_TORRENT_PROFILE_SOFT
                    P2pTorrentProfile.BALANCED -> NUVIO_ENGINE_TORRENT_PROFILE_BALANCED
                    P2pTorrentProfile.FAST -> NUVIO_ENGINE_TORRENT_PROFILE_FAST
                }
                val output = alloc<CPointerVar<nuvio_engine>>()
                checkStatus(nuvio_engine_create(config.ptr, output.ptr))
                output.value ?: throw P2pStreamingException("Nuvio Engine returned an empty handle")
            }
            engine = created
            engineSerial += 1
            created to engineSerial
        }
        engineConfigurationKey = configuration
        startEventPump(created, serial)
        log("engine created configuration=$configuration")
        return created
    }

    private suspend fun closeEngine() {
        eventPumpJob?.cancelAndJoin()
        eventPumpJob = null
        val oldStats = synchronized(controlLock) {
            statsJob.also { statsJob = null }
        }
        oldStats?.cancelAndJoin()
        nativeCallMutex.withLock {
            pendingEvent?.result?.cancel(CancellationException("P2P engine closed"))
            pendingEvent = null
            engine?.let { activeEngine ->
                engine = null
                engineSerial += 1
                nuvio_engine_destroy(activeEngine)
            }
            engineConfigurationKey = null
            knownTorrentIds.clear()
        }
    }

    // Lifecycle operations hold lifecycleMutex; only the event pump and stat
    // readers run independently, and they never destroy an engine handle.
    private fun activeEngineOrNull(): CPointer<nuvio_engine>? = engine

    private fun checkActiveHandle(activeEngine: CPointer<nuvio_engine>) {
        if (engine != activeEngine) {
            throw CancellationException("P2P engine handle was closed")
        }
    }

    private suspend fun addMagnet(activeEngine: CPointer<nuvio_engine>, magnet: String): String {
        return awaitEvent(activeEngine, NUVIO_ENGINE_EVENT_TORRENT_METADATA_READY,
            MetadataEventTimeoutMs, "torrent metadata") {
            memScoped {
                val request = alloc<nuvio_engine_torrent_request>()
                nuvio_engine_torrent_request_init_sized(
                    request.ptr,
                    sizeOf<nuvio_engine_torrent_request>().toUInt(),
                )
                request.magnet_uri = magnet.cstr.getPointer(this)
                request.source_type = 0u
                val output = alloc<ULongVar>()
                checkStatus(nuvio_engine_add_torrent(activeEngine, request.ptr, output.ptr))
                output.value
            }
        }
            .torrentId
            ?: throw P2pStreamingException("Torrent metadata event omitted its identifier")
    }

    private suspend fun prepareStream(
        activeEngine: CPointer<nuvio_engine>,
        torrentId: String,
        fileIndex: Int?,
        filename: String?,
    ): NativeStream {
        val event = awaitEvent(activeEngine, NUVIO_ENGINE_EVENT_STREAM_PREPARED,
            PrepareEventTimeoutMs, "stream preparation") {
            memScoped {
                val request = alloc<nuvio_engine_stream_request>()
                nuvio_engine_stream_request_init_sized(
                    request.ptr,
                    sizeOf<nuvio_engine_stream_request>().toUInt(),
                )
                request.torrent_id = torrentId.cstr.getPointer(this)
                request.file_index = fileIndex?.toUInt() ?: UInt.MAX_VALUE
                request.filename_hint = filename?.cstr?.getPointer(this)
                val output = alloc<ULongVar>()
                checkStatus(nuvio_engine_prepare_stream(activeEngine, request.ptr, output.ptr))
                output.value
            }
        }
        return NativeStream(
            id = event.streamId
                ?: throw P2pStreamingException("Stream event omitted its identifier"),
            url = event.streamUrl
                ?: throw P2pStreamingException("Stream event omitted its URL"),
            torrentId = event.torrentId ?: torrentId,
            fileIndex = event.fileIndex?.toInt() ?: fileIndex ?: -1,
            fileSize = event.fileSize.toLong(),
        )
    }

    private suspend fun stopNativeStream(activeEngine: CPointer<nuvio_engine>?, streamId: String) {
        if (activeEngine == null) return
        awaitEvent(activeEngine, NUVIO_ENGINE_EVENT_STREAM_STOPPED,
            StopEventTimeoutMs, "stream stop") {
            memScoped {
                val output = alloc<ULongVar>()
                checkStatus(nuvio_engine_stop_stream(activeEngine, streamId, output.ptr))
                output.value
            }
        }
    }

    private suspend fun reclaimDiskCache(activeEngine: CPointer<nuvio_engine>) {
        awaitEvent(activeEngine, NUVIO_ENGINE_EVENT_DISK_CACHE_RECLAIMED,
            ReclaimEventTimeoutMs, "cache reclaim") {
            memScoped {
                val output = alloc<ULongVar>()
                checkStatus(nuvio_engine_reclaim_disk_cache(activeEngine, 0uL, output.ptr))
                output.value
            }
        }
    }

    private suspend fun awaitEvent(
        activeEngine: CPointer<nuvio_engine>,
        expectedType: UInt,
        timeoutMs: Long,
        description: String,
        submit: () -> ULong,
    ): NativeEvent {
        val result = CompletableDeferred<NativeEvent>()
        nativeCallMutex.withLock {
            checkActiveHandle(activeEngine)
            check(pendingEvent == null) { "Concurrent native event request" }
            // Submission and registration are atomic relative to the sole
            // event reader. A very fast native reply cannot be discarded.
            val requestId = submit()
            pendingEvent = PendingEvent(requestId, expectedType, result)
        }
        try {
            return withTimeoutOrNull(timeoutMs) { result.await() }
                ?: throw P2pStreamingException("Timed out waiting for $description")
        } finally {
            withContext(NonCancellable) {
                nativeCallMutex.withLock {
                    if (pendingEvent?.result === result) pendingEvent = null
                }
            }
        }
    }

    private fun startEventPump(activeEngine: CPointer<nuvio_engine>, serial: Long) {
        eventPumpJob = scope.launch {
            try {
                while (isActive) {
                    val event = nativeCallMutex.withLock {
                        if (engine != activeEngine) return@withLock null
                        pollEvent(activeEngine)
                    }
                    if (event == null) {
                        delay(EventPollIntervalMs)
                    } else {
                        // Record terminal ownership before waking a command
                        // waiter. Its cancellation handler must see whether
                        // cleanup was already delegated to another job.
                        processUnsolicitedEvent(activeEngine, serial, event)
                        nativeCallMutex.withLock {
                            val pending = pendingEvent
                            if (pending != null && pending.requestId == event.requestId) {
                                when (event.type) {
                                    NUVIO_ENGINE_EVENT_TORRENT_ERROR -> pending.result.completeExceptionally(
                                        P2pStreamingException(event.message ?: "Torrent engine command failed")
                                    )
                                    pending.expectedType -> pending.result.complete(event)
                                }
                            } else if (event.requestId == 0uL &&
                                event.type == NUVIO_ENGINE_EVENT_TORRENT_ERROR && pending != null &&
                                synchronized(controlLock) { startingTorrentId == event.torrentId }
                            ) {
                                pending.result.completeExceptionally(P2pStreamingException(
                                    event.message ?: "Torrent failed while preparing the stream"
                                ))
                            }
                        }
                        // Keep a busy native queue from starving unrelated tasks.
                        yield()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                log("native event polling failed: ${error.message}")
                nativeCallMutex.withLock {
                    pendingEvent?.result?.completeExceptionally(P2pStreamingException(
                        error.message ?: "Torrent event polling failed"
                    ))
                }
                val shutdown = synchronized(controlLock) {
                    if (currentStream != null) {
                        generation += 1
                        stopEpoch += 1
                        statsJob?.cancel()
                        statsJob = null
                        currentStream = null
                        currentTorrentId = null
                        _state.value = P2pStreamingState.Error(
                            error.message ?: "Torrent event polling failed"
                        )
                        queueEngineTeardownWhileLocked(activeEngine, serial)
                    } else {
                        null
                    }
                }
                shutdown?.start()
            }
        }
    }

    private fun processUnsolicitedEvent(
        activeEngine: CPointer<nuvio_engine>,
        serial: Long,
        event: NativeEvent,
    ) {
        if (event.requestId != 0uL) return
        val (startup, cleanup) = synchronized(controlLock) {
            val affected = when (event.type) {
                NUVIO_ENGINE_EVENT_TORRENT_ERROR ->
                    event.torrentId != null &&
                        (currentTorrentId == event.torrentId || startingTorrentId == event.torrentId)
                NUVIO_ENGINE_EVENT_STREAM_STOPPED ->
                    currentStream != null && currentStream?.id == event.streamId
                else -> false
            }
            if (!affected) return@synchronized Pair<Job?, Job?>(null, null)
            generation += 1
            stopEpoch += 1
            val activeStartup = activeStartJob
            val affectedStream = currentStream
            statsJob?.cancel()
            statsJob = null
            currentStream = null
            currentTorrentId = null
            _state.value = P2pStreamingState.Error(
                event.message?.takeIf(String::isNotBlank) ?: when (event.type) {
                    NUVIO_ENGINE_EVENT_TORRENT_ERROR -> "Torrent failed unexpectedly"
                    else -> "Torrent stream stopped unexpectedly"
                }
            )
            // A terminal error may race the final return from startStream.
            // Always delegate the native route stop when it is known; the
            // startup cancellation handler observes this ownership marker.
            val cleanup = if (event.type == NUVIO_ENGINE_EVENT_TORRENT_ERROR &&
                affectedStream != null
            ) {
                terminalHandledStreamId = affectedStream.id
                queueTerminalCleanupWhileLocked(activeEngine, serial, affectedStream.id)
            } else {
                if (activeStartup != null && event.type == NUVIO_ENGINE_EVENT_STREAM_STOPPED) {
                    terminalHandledStreamId = affectedStream?.id
                }
                null
            }
            activeStartup to cleanup
        }
        startup?.cancel(CancellationException("Torrent terminated during startup"))
        cleanup?.start()
    }

    // Must be called with controlLock held so queued starts and stop requests
    // observe this cleanup before they can acquire the lifecycle owner.
    private fun queueTerminalCleanupWhileLocked(
        activeEngine: CPointer<nuvio_engine>,
        serial: Long,
        streamId: String,
    ): Job {
        val previous = pendingStopJob
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                lifecycleMutex.withLock {
                    if (engine == activeEngine && engineSerial == serial) {
                        val result = runCatching { stopNativeStream(activeEngine, streamId) }
                        if (result.isFailure) {
                            log("terminal torrent cleanup failed: ${result.exceptionOrNull()?.message}")
                            closeEngine()
                        }
                    }
                }
            } finally {
                val job = currentCoroutineContext()[Job]
                synchronized(controlLock) {
                    if (terminalHandledStreamId == streamId) terminalHandledStreamId = null
                    if (pendingStopJob === job) pendingStopJob = null
                }
            }
        }.also { pendingStopJob = it }
    }

    // If native event polling itself has failed, no command reply can be
    // awaited. Destroy the native handle from another coroutine after this
    // pump exits, preserving the engine identity and start/stop ordering.
    private fun queueEngineTeardownWhileLocked(
        activeEngine: CPointer<nuvio_engine>,
        serial: Long,
    ): Job {
        val previous = pendingStopJob
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                lifecycleMutex.withLock {
                    if (engine == activeEngine && engineSerial == serial) closeEngine()
                }
            } finally {
                val job = currentCoroutineContext()[Job]
                synchronized(controlLock) {
                    if (pendingStopJob === job) pendingStopJob = null
                }
            }
        }.also { pendingStopJob = it }
    }

    // This is the only event consumer and is called under nativeCallMutex.
    private fun pollEvent(activeEngine: CPointer<nuvio_engine>): NativeEvent? = memScoped {
        val event = alloc<nuvio_engine_event>()
        nuvio_engine_event_init_sized(event.ptr, sizeOf<nuvio_engine_event>().toUInt())
        val status = nuvio_engine_poll_event(activeEngine, event.ptr)
        if (status == NUVIO_ENGINE_STATUS_NO_EVENT) return@memScoped null
        checkStatus(status)
        NativeEvent(
            type = event.type,
            requestId = event.request_id,
            torrentId = event.torrent_id.toKString().ifBlank { null },
            message = event.message.toKString().ifBlank { null },
            fileIndex = event.file_index.takeUnless { it == UInt.MAX_VALUE },
            fileSize = event.file_size,
            streamId = event.stream_id.toKString().ifBlank { null },
            streamUrl = event.stream_url.toKString().ifBlank { null },
        )
    }

    private suspend fun readAggregateStats(activeEngine: CPointer<nuvio_engine>): AggregateStats =
        nativeCallMutex.withLock {
            checkActiveHandle(activeEngine)
            memScoped {
                val stats = alloc<nuvio_engine_stats>()
                nuvio_engine_stats_init_sized(stats.ptr, sizeOf<nuvio_engine_stats>().toUInt())
                checkStatus(nuvio_engine_get_stats(activeEngine, stats.ptr))
                AggregateStats(
                    peers = stats.connected_peers.toInt(),
                    seeds = stats.connected_seeds.toInt(),
                    downloadSpeed = stats.download_rate_bytes_per_second.toLong(),
                    uploadSpeed = stats.upload_rate_bytes_per_second.toLong(),
                    payloadDownloaded = stats.total_payload_download_bytes.toLong(),
                    diskUsed = stats.disk_cache_used_bytes.toLong(),
                    diskProtected = stats.disk_cache_protected_bytes.toLong(),
                )
            }
        }

    private suspend fun readStreamStats(
        activeEngine: CPointer<nuvio_engine>,
        streamId: String,
    ): StreamStats = nativeCallMutex.withLock {
        checkActiveHandle(activeEngine)
        memScoped {
            val stats = alloc<nuvio_engine_stream_stats>()
            nuvio_engine_stream_stats_init_sized(
                stats.ptr,
                sizeOf<nuvio_engine_stream_stats>().toUInt(),
            )
            checkStatus(nuvio_engine_get_stream_stats(activeEngine, streamId, stats.ptr))
            StreamStats(
                fileSize = stats.file_size.toLong(),
                contiguousReady = stats.contiguous_ready_bytes.toLong(),
                verified = stats.verified_file_bytes.toLong(),
                delivered = stats.delivered_bytes.toLong(),
            )
        }
    }

    private fun updateCacheState(stats: AggregateStats) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = stats.diskUsed,
            protectedBytes = stats.diskProtected,
            hasMeasurement = true,
        )
    }

    private fun checkStatus(status: UInt) {
        if (status == NUVIO_ENGINE_STATUS_OK) return
        val message = nuvio_engine_status_message(status)?.toKString()
            ?: "Nuvio Engine status $status"
        throw P2pStreamingException(message)
    }

    private fun createDirectory(path: String) {
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )) { "Could not create Nuvio Engine directory" }
    }

    private fun ratio(value: Long, total: Long): Float =
        if (total <= 0L) 0f else (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

    private fun log(message: String) {
        NSLog("NuvioP2PDiag: $message")
    }

    private val DefaultTrackers = listOf(
        "udp://zer0day.ch:1337/announce",
        "udp://tracker.publictracker.xyz:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "http://tracker.renfei.net:8080/announce",
        "udp://udp.tracker.projectk.org:23333/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.startwork.cv:1337/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.plx.im:6969/announce",
        "udp://tracker.nyaa.vc:6969/announce",
        "udp://tracker.iperson.xyz:6969/announce",
        "udp://tracker.gmi.gd:6969/announce",
        "udp://tracker.fnix.net:6969/announce",
        "udp://tracker.flatuslifir.is:6969/announce",
        "udp://tracker.ducks.party:1984/announce",
        "udp://tracker.bluefrog.pw:2710/announce",
    )
}
