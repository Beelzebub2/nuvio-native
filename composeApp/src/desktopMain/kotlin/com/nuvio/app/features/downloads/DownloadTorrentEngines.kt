package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.DesktopTorrentBackend
import com.nuvio.app.features.p2p.P2pSettingsUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One torrent engine per torrent, shared by every download that reads from it. Episodes from the same
 * season pack reuse the peers it is already connected to, and the engine stays warm for [idleMs] after
 * the last transfer so the next queued episode does not have to find peers from scratch.
 */
internal class DownloadTorrentEngines(
    private val create: (hash: String) -> DesktopTorrentBackend,
    private val scope: CoroutineScope,
    private val idleMs: Long = 90_000L,
    private val onRetired: (hash: String) -> Unit = {},
) {
    private class Engine(val backend: DesktopTorrentBackend) {
        /** Serialises starting and retiring this engine's process. */
        val setup = Mutex()
        var users = 0
        var running = false
        var idle: Job? = null
    }

    private val lock = Mutex()
    // Entries are kept after retiring so a torrent always maps to one engine and one settings folder.
    private val engines = ConcurrentHashMap<String, Engine>()

    /** Every engine, for the shutdown hook; stopping an engine that is not running does nothing. */
    fun backends(): List<DesktopTorrentBackend> = engines.values.map { it.backend }

    suspend fun <T> use(hash: String, settings: P2pSettingsUiState, block: suspend (DesktopTorrentBackend) -> T): T {
        val key = hash.lowercase()
        val engine = lock.withLock {
            engines.getOrPut(key) { Engine(create(key)) }.also { engine ->
                engine.idle?.cancel()
                engine.idle = null
                engine.users++
            }
        }
        try {
            engine.setup.withLock {
                // A running engine keeps its settings; restarting it would cut the other episodes' transfers.
                if (!engine.running || !engine.backend.isHealthy()) {
                    engine.running = false
                    engine.backend.prepare(settings)
                    engine.running = true
                }
            }
            return block(engine.backend)
        } finally {
            withContext(NonCancellable) { release(key, engine) }
        }
    }

    private suspend fun release(key: String, engine: Engine) = lock.withLock {
        engine.users--
        if (engine.users > 0) return@withLock
        engine.idle = scope.launch {
            delay(idleMs)
            engine.setup.withLock {
                if (lock.withLock { engine.users > 0 }) return@withLock
                withContext(NonCancellable) {
                    // Dropping first lets the engine delete its own piece cache before it exits.
                    runCatching { engine.backend.dropTorrent(key) }
                    runCatching { engine.backend.stop() }
                    engine.running = false
                    runCatching { onRetired(key) }
                }
            }
        }
    }
}
