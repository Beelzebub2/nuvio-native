package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.p2p.DesktopTorrentBackend
import com.nuvio.app.features.p2p.MAX_DOWNLOAD_TORRENT_ENGINES
import com.nuvio.app.features.p2p.P2pSettingsUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads of one infohash share a backend, including its live peers. A backend stays warm for
 * [idleMs] after its final reader, then is stopped and removed from the map. At most [maxEngines]
 * backend processes can be prepared at a time, even if more hashes are queued.
 */
internal class DownloadTorrentEngines(
    private val create: (hash: String) -> DesktopTorrentBackend,
    private val scope: CoroutineScope,
    private val idleMs: Long = 90_000L,
    private val onRetired: (hash: String) -> Unit = {},
    private val onPreparing: (hash: String) -> Unit = {},
    private val onPrepared: (hash: String, backend: DesktopTorrentBackend) -> Unit = { _, _ -> },
    maxEngines: Int = MAX_DOWNLOAD_TORRENT_ENGINES,
) {
    init {
        require(idleMs >= 0)
        require(maxEngines in 1..MAX_DOWNLOAD_TORRENT_ENGINES)
    }

    private class Engine {
        /** Serialises preparation, use-vs-retirement and shutdown of this backend. */
        val setup = Mutex()
        @Volatile var backend: DesktopTorrentBackend? = null
        var users = 0
        var running = false
        var stopFailed = false
        var hasPermit = false
        var idle: Job? = null
        var idleSince = 0L
        var evictionScheduled = false
        /** Acquisitions wait for cleanup rather than racing a new backend into the same cache. */
        var retiring: CompletableDeferred<Unit>? = null
    }

    private val log = Logger.withTag("DownloadTorrentEngines")
    private val lock = Mutex()
    private val slots = Semaphore(maxEngines)
    private val pendingPermits = AtomicInteger()
    private val engines = ConcurrentHashMap<String, Engine>()

    /** Snapshot of live or preparing backends for the application shutdown hook. */
    fun backends(): List<DesktopTorrentBackend> = engines.values.mapNotNull { it.backend }

    /** Number of hashes currently waiting for a freed process slot. */
    internal fun waitingForProcessSlot(): Int = pendingPermits.get()

    suspend fun <T> use(hash: String, settings: P2pSettingsUiState, block: suspend (DesktopTorrentBackend) -> T): T {
        val key = hash.lowercase()
        val engine = acquire(key)
        try {
            val backend = engine.setup.withLock {
                check(!engine.stopFailed) { "A previous torrent process could not be stopped; retry shortly" }
                val existing = engine.backend
                if (engine.running && existing != null && existing.isHealthy()) return@withLock existing

                if (!engine.hasPermit) {
                    // The permit covers the entire warm lifetime, not just the active download.
                    if (!slots.tryAcquire()) {
                        pendingPermits.incrementAndGet()
                        try {
                            evictOldestIdleEngine()
                            slots.acquire()
                        } finally {
                            pendingPermits.decrementAndGet()
                        }
                    }
                    engine.hasPermit = true
                }
                try {
                    val prepared = engine.backend ?: create(key).also { engine.backend = it }
                    // A prior PID is unsafe if prepare restarts a dead/unhealthy child: a crash
                    // before onPrepared would otherwise make its new cache look orphaned.
                    onPreparing(key)
                    prepared.prepare(settings)
                    onPrepared(key, prepared)
                    engine.running = true
                    prepared
                } catch (failure: Throwable) {
                    engine.running = false
                    val stopped = withContext(NonCancellable) {
                        runCatching { engine.backend?.stop() }
                    }
                    if (stopped.isFailure) {
                        engine.stopFailed = true
                        log.w(stopped.exceptionOrNull()!!) { "Failed to stop the partially prepared torrent backend" }
                    } else {
                        engine.backend = null
                        if (engine.hasPermit) {
                            engine.hasPermit = false
                            slots.release()
                        }
                    }
                    throw failure
                }
            }
            return block(backend)
        } finally {
            withContext(NonCancellable) { release(key, engine) }
        }
    }

    private suspend fun acquire(key: String): Engine {
        while (true) {
            var retiring: CompletableDeferred<Unit>? = null
            val acquired = lock.withLock {
                val engine = engines.getOrPut(key) { Engine() }
                retiring = engine.retiring
                if (retiring != null) null else engine.also {
                    it.users++
                    it.evictionScheduled = false
                    it.idle?.cancel()
                    it.idle = null
                }
            }
            if (acquired != null) return acquired
            // Retired settings/cache folders must be fully released before the next generation.
            retiring!!.await()
        }
    }

    private suspend fun release(key: String, engine: Engine) = lock.withLock {
        check(engine.users > 0)
        engine.users--
        if (engine.users != 0) return@withLock
        engine.idleSince = System.nanoTime()
        // A waiter may have arrived while every slot was active. Give it this newly idle
        // slot immediately instead of making it wait through an additional 90 seconds.
        val underPressure = pendingPermits.get() > 0 && engine.hasPermit
        engine.idle = scope.launch {
            // A failed/never-started backend has no peers worth retaining.
            if (!underPressure && engine.backend != null && !engine.stopFailed) delay(idleMs)
            retire(key, engine)
        }
    }

    /** An idle process cannot hold up a newly queued hash for the entire warm retention period. */
    private suspend fun evictOldestIdleEngine() = lock.withLock {
        if (slots.availablePermits > 0) return@withLock
        val oldest = engines.entries
            .filter { (_, engine) ->
                engine.users == 0 && engine.hasPermit && engine.retiring == null && !engine.evictionScheduled
            }
            .minByOrNull { (_, engine) -> engine.idleSince }
            ?: return@withLock
        val engine = oldest.value
        engine.evictionScheduled = true
        engine.idle?.cancel()
        engine.idle = scope.launch { retire(oldest.key, engine) }
    }

    private suspend fun retire(key: String, engine: Engine): Unit = engine.setup.withLock {
        val completed = lock.withLock {
            if (engines[key] !== engine || engine.users != 0 || engine.retiring != null) null
            else CompletableDeferred<Unit>().also { engine.retiring = it }
        } ?: return@withLock

        // Once marked retiring, new readers wait for this whole critical section. A newer
        // same-hash backend cannot be created while its predecessor still owns the cache.
        withContext(NonCancellable) {
            var stoppedSafely = true
            try {
                val backend = engine.backend
                if (backend != null) {
                    if (engine.running) runCatching { backend.dropTorrent(key) }
                        .onFailure { log.w(it) { "Dropping a retired download torrent" } }
                    val stopped = runCatching { backend.stop() }
                    if (stopped.isSuccess) {
                        engine.stopFailed = false
                        runCatching { onRetired(key) }
                            .onFailure { log.w(it) { "Cleaning a retired download torrent cache" } }
                    } else {
                        engine.stopFailed = true
                        stoppedSafely = false
                        log.w(stopped.exceptionOrNull()!!) { "Download torrent backend could not be stopped" }
                    }
                } else {
                    // A factory may have allocated an owner directory before preparation failed.
                    runCatching { onRetired(key) }
                        .onFailure { log.w(it) { "Cleaning a failed download torrent cache" } }
                }
            } finally {
                lock.withLock {
                    if (stoppedSafely) {
                        engine.running = false
                        engine.backend = null
                        if (engine.hasPermit) {
                            engine.hasPermit = false
                            slots.release()
                        }
                        engines.remove(key, engine)
                    } else {
                        // Retain the slot and ownership until we can prove the child stopped.
                        engine.retiring = null
                        engine.idle = scope.launch { delay(1_000); retire(key, engine) }
                    }
                    completed.complete(Unit)
                }
            }
        }
    }
}
