package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.DesktopTorrentBackend
import com.nuvio.app.features.p2p.P2pCacheSize
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.p2p.P2pTorrentProfile
import com.nuvio.app.features.p2p.TorrServerStats
import com.nuvio.app.features.p2p.desktopTorrentSettings
import com.nuvio.app.features.p2p.downloadTorrentResourceBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadTorrentResourcesTest {
    private class FakeBackend(private val onStart: () -> Unit = {}, private val onStop: () -> Unit = {}) : DesktopTorrentBackend {
        @Volatile var running = false
        override suspend fun prepare(settings: P2pSettingsUiState) {
            assertFalse(running)
            running = true
            onStart()
        }
        override suspend fun restart(settings: P2pSettingsUiState) = prepare(settings)
        override suspend fun dropTorrent(hash: String) = Unit
        override suspend fun stop() {
            if (running) {
                running = false
                onStop()
            }
        }
        override suspend fun isHealthy() = running
        override suspend fun addTorrent(magnetLink: String) = "fixture"
        override suspend fun getTorrentStats(hash: String) = TorrServerStats(0, 0, 0, 0, 0, 0, 0, emptyList())
        override fun getStreamUrl(magnetLink: String, fileIdx: Int) = "http://127.0.0.1/fixture"
    }

    @Test fun sameHashSharesLivePeersAndExpiredEnginesAreActuallyEvicted() = runBlocking {
        val created = AtomicInteger()
        val retired = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadTorrentEngines(create = { created.incrementAndGet(); FakeBackend() },
            scope = scope, idleMs = 30, onRetired = { retired.incrementAndGet() })
        val settings = P2pSettingsUiState(p2pEnabled = true)
        try {
            val hold = CompletableDeferred<Unit>()
            val started = AtomicInteger()
            val hash = "A".repeat(40)
            val first = async(Dispatchers.Default) { manager.use(hash, settings) { started.incrementAndGet(); hold.await(); it } }
            val second = async(Dispatchers.Default) { manager.use(hash, settings) { started.incrementAndGet(); hold.await(); it } }
            withTimeout(3_000) { while (started.get() != 2) delay(5) }
            hold.complete(Unit)
            assertSame(first.await(), second.await())
            assertEquals(1, created.get())
            withTimeout(3_000) { while (retired.get() < 1 || manager.backends().isNotEmpty()) delay(5) }
            assertTrue(manager.backends().isEmpty(), "A retired backend must leave the map")
            manager.use(hash, settings) { assertTrue(it.isHealthy()) }
            assertEquals(2, created.get(), "After eviction, the next generation creates a new backend")
            withTimeout(3_000) { while (retired.get() < 2 || manager.backends().isNotEmpty()) delay(5) }
            assertTrue(manager.backends().isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun atMostFiveIndependentBackendProcessesRunAndWaitingDoesNotLeakPermits() = runBlocking {
        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        val created = AtomicInteger()
        val hold = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadTorrentEngines(create = {
            created.incrementAndGet()
            FakeBackend(onStart = {
                val running = concurrent.incrementAndGet()
                peak.accumulateAndGet(running) { previous, observed -> maxOf(previous, observed) }
            }, onStop = { concurrent.decrementAndGet() })
        }, scope = scope, idleMs = 20)
        try {
            val settings = P2pSettingsUiState(p2pEnabled = true)
            val tasks = (0 until 6).map { n -> async(Dispatchers.Default) {
                manager.use(n.toString(16).padStart(40, 'a'), settings) { hold.await() }
            } }
            withTimeout(3_000) { while (concurrent.get() != 5) delay(5) }
            assertEquals(5, created.get(), "The sixth backend must wait before constructing its HTTP client")
            assertEquals(5, peak.get())
            hold.complete(Unit)
            withTimeout(3_000) { tasks.awaitAll() }
            withTimeout(3_000) { while (concurrent.get() != 0 || manager.backends().isNotEmpty()) delay(5) }
            assertEquals(6, created.get())
            assertTrue(peak.get() <= 5)
        } finally { scope.cancel() }
    }

    @Test fun aNewHashPromptlyEvictsWarmIdleProcessInsteadOfWaitingNinetySeconds() = runBlocking {
        val stopped = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadTorrentEngines(create = { FakeBackend(onStop = { stopped.incrementAndGet() }) },
            scope = scope, idleMs = 90_000, maxEngines = 2)
        try {
            val settings = P2pSettingsUiState()
            manager.use("a".repeat(40), settings) { }
            manager.use("b".repeat(40), settings) { }
            assertEquals(2, manager.backends().size)
            withTimeout(2_000) { manager.use("c".repeat(40), settings) { assertTrue(it.isHealthy()) } }
            assertTrue(stopped.get() >= 1, "The oldest warm backend should yield its process slot")
            assertTrue(manager.backends().size <= 2)
        } finally { scope.cancel() }
    }

    @Test fun aHashWaitingOnActiveSlotsStartsAsSoonAsOneBecomesIdle(): Unit {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val prepared = AtomicInteger()
            val manager = DownloadTorrentEngines(create = { FakeBackend() },
                scope = scope, idleMs = 90_000L, maxEngines = 2)
            val releaseA = CompletableDeferred<Unit>()
            val releaseB = CompletableDeferred<Unit>()
            try {
                val settings = P2pSettingsUiState()
                val first = scope.async { manager.use("a".repeat(40), settings) { prepared.incrementAndGet(); releaseA.await() } }
                val second = scope.async { manager.use("b".repeat(40), settings) { prepared.incrementAndGet(); releaseB.await() } }
                withTimeout(3_000) { while (prepared.get() != 2) delay(5) }
                val nextStarted = CompletableDeferred<Unit>()
                val third = scope.async { manager.use("c".repeat(40), settings) { nextStarted.complete(Unit) } }
                withTimeout(3_000) { while (manager.waitingForProcessSlot() == 0) delay(5) }
                assertFalse(nextStarted.isCompleted, "All process slots still belong to active readers")
                releaseA.complete(Unit)
                withTimeout(2_000) { nextStarted.await() }
                assertFalse(releaseB.isCompleted, "The second original download should still be active")
                releaseB.complete(Unit)
                withTimeout(3_000) { first.await(); second.await(); third.await() }
            } finally { releaseA.complete(Unit); releaseB.complete(Unit); scope.cancel() }
        }
    }

    @Test fun failedProcessStopKeepsItsSlotAndCacheOwnershipUntilStopSucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val created = AtomicInteger()
        val stops = AtomicInteger()
        val retired = AtomicInteger()
        val allowStop = AtomicBoolean(false)
        val manager = DownloadTorrentEngines(create = {
            created.incrementAndGet()
            object : DesktopTorrentBackend {
                @Volatile var running = false
                override suspend fun prepare(settings: P2pSettingsUiState) { running = true }
                override suspend fun restart(settings: P2pSettingsUiState) = prepare(settings)
                override suspend fun dropTorrent(hash: String) = Unit
                override suspend fun stop() {
                    stops.incrementAndGet()
                    if (!allowStop.get()) throw IllegalStateException("Process is still alive")
                    running = false
                }
                override suspend fun isHealthy() = running
                override suspend fun addTorrent(magnetLink: String) = "fixture"
                override suspend fun getTorrentStats(hash: String) = TorrServerStats(0, 0, 0, 0, 0, 0, 0, emptyList())
                override fun getStreamUrl(magnetLink: String, fileIdx: Int) = ""
            }
        }, scope = scope, idleMs = 10, maxEngines = 1, onRetired = { retired.incrementAndGet() })
        val settings = P2pSettingsUiState()
        try {
            manager.use("a".repeat(40), settings) { }
            withTimeout(3_000) { while (stops.get() < 1) delay(5) }
            assertEquals(0, retired.get(), "Never remove ownership while stop failed")
            assertEquals(1, manager.backends().size, "The unknown child still occupies its slot")
            assertFailsWith<IllegalStateException> {
                manager.use("a".repeat(40), settings) { error("Cannot use a backend after stop failure") }
            }
            assertEquals(1, created.get(), "A second same-hash process cannot reuse the old cache")
            val queued = scope.async { manager.use("b".repeat(40), settings) { } }
            delay(100)
            assertEquals(1, created.get(), "A failed stop cannot release its process budget")
            assertFalse(queued.isCompleted)
            allowStop.set(true)
            withTimeout(4_000) { queued.await() }
            withTimeout(3_000) { while (retired.get() == 0) delay(5) }
            assertEquals(2, created.get())
        } finally { allowStop.set(true); scope.cancel() }
    }

    @Test fun cancellationWhileWaitingForAProcessPermitDoesNotLeakTheSlot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstRunning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = DownloadTorrentEngines(create = { FakeBackend() }, scope = scope, idleMs = 20, maxEngines = 1)
        try {
            val settings = P2pSettingsUiState()
            val first = async { manager.use("a".repeat(40), settings) { firstRunning.complete(Unit); release.await() } }
            withTimeout(3_000) { firstRunning.await() }
            val canceled = async { manager.use("b".repeat(40), settings) { error("Must not acquire a held slot") } }
            delay(50)
            canceled.cancel()
            canceled.join()
            release.complete(Unit)
            first.await()
            withTimeout(3_000) { manager.use("c".repeat(40), settings) { assertTrue(it.isHealthy()) } }
        } finally { scope.cancel() }
    }

    @Test fun perDownloadCacheAndConnectionSettingsCannotMultiplyByFive() {
        val directory = Files.createTempDirectory("download-budget-test")
        val owner = DownloadTorrentCacheOwnership(directory.resolve("download-torrents"))
        try {
            val cache = owner.claim("a".repeat(40))
            val defaults = Json.parseToJsonElement("""{"CustomFutureSetting":42}""").jsonObject
            for (profile in P2pTorrentProfile.entries) {
                for (size in P2pCacheSize.entries) {
                    val settings = P2pSettingsUiState(cacheSize = size, torrentProfile = profile)
                    val configured = desktopTorrentSettings(defaults, settings, cache.absolutePath)
                    val reserved = downloadTorrentResourceBudget(settings)
                    assertEquals(reserved.cacheBytes, configured.getValue("CacheSize").jsonPrimitive.long)
                    assertEquals(reserved.connectionLimit, configured.getValue("ConnectionsLimit").jsonPrimitive.int)
                    // Increasing the interactive playback preload must not make
                    // background downloads wait for a large initial cache fill.
                    assertEquals(if (size == P2pCacheSize.NONE) 10 else 1,
                        configured.getValue("PreloadCache").jsonPrimitive.int)
                    assertTrue(5 * reserved.connectionLimit <= 400)
                    if (size != P2pCacheSize.NONE) assertTrue(5 * reserved.cacheBytes <= size.bytes)
                    else assertTrue(5 * reserved.cacheBytes <= 1_536L * 1024 * 1024)
                }
            }
            assertEquals(42, defaults.getValue("CustomFutureSetting").jsonPrimitive.int)
        } finally { owner.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun cacheSweeperReclaimsOnlyMarkedOrphansAndNeverActiveOrLegacyContent() {
        val directory = Files.createTempDirectory("download-cache-recovery-test")
        val root = directory.resolve("download-torrents")
        val owner = DownloadTorrentCacheOwnership(root)
        val nextProcess = DownloadTorrentCacheOwnership(root)
        val hash = "b".repeat(40)
        try {
            val legacy = root.resolve("c".repeat(40))
            Files.createDirectories(legacy)
            val sentinel = legacy.resolve("unmarked-private-file")
            Files.writeString(sentinel, "preserve")
            val active = owner.claim(hash)
            Files.writeString(active.toPath().resolve("piece.bin"), "cached-piece")
            // The child PID was persisted; after a crash, that process is no longer alive.
            owner.recordProcessId(hash, Long.MAX_VALUE)
            assertEquals(active, owner.claim(hash), "One running hash retains its owned cache")
            assertEquals(0L, nextProcess.scavenge(), "A cache held by a live process must not be reclaimed")
            assertTrue(active.exists())
            owner.close() // Simulate termination: OS releases the lock, leaving the cache behind.
            // The reclaimed count includes the persisted child PID record as well as
            // piece bytes; it can exceed the payload size while remaining exact to disk.
            assertTrue(nextProcess.scavenge() >= "cached-piece".length.toLong())
            assertFalse(active.exists())
            assertEquals("preserve", Files.readString(sentinel))
            assertEquals(0L, nextProcess.scavenge())
        } finally {
            owner.close(); nextProcess.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test fun cleanRetirementRemovesOnlyTheVerifiedCacheGeneration() {
        val directory = Files.createTempDirectory("download-cache-retire-test")
        val root = directory.resolve("download-torrents")
        val owner = DownloadTorrentCacheOwnership(root)
        try {
            val hash = "a".repeat(40)
            val legacy = root.resolve(hash).resolve("preexisting-legacy-data")
            Files.createDirectories(legacy)
            val sentinel = Files.writeString(legacy.resolve("do-not-remove"), "private")
            val cache = owner.claim(hash.uppercase())
            val nested = Files.createDirectories(cache.toPath().resolve("piece-folder"))
            Files.writeString(nested.resolve("piece.part"), "123456789")
            assertEquals(9L, owner.retire(hash))
            assertFalse(cache.exists())
            assertEquals("private", Files.readString(sentinel))
            assertTrue(Files.isDirectory(legacy))
        } finally { owner.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun sixtyFourHexAndBase32HashesAlsoGetVerifiedManagedCaches() {
        val directory = Files.createTempDirectory("download-v2-cache-test")
        val owner = DownloadTorrentCacheOwnership(directory.resolve("download-torrents"))
        try {
            val hashes = listOf("a".repeat(64), "Z".repeat(32))
            hashes.forEach { hash ->
                val cache = owner.claim(hash)
                assertTrue(com.nuvio.app.features.downloads.isManagedDownloadTorrentCache(cache))
                assertEquals(0L, owner.retire(hash))
                assertFalse(cache.exists())
            }
        } finally { owner.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun orphanScavengerPreservesPidlessSessionsRegardlessOfAgeAndSessionsWithLiveChildPid() {
        val directory = Files.createTempDirectory("download-cache-active-child-test")
        val root = directory.resolve("download-torrents")
        val owner = DownloadTorrentCacheOwnership(root)
        val rescuer = DownloadTorrentCacheOwnership(root)
        try {
            val hash = "e".repeat(40)
            val cache = owner.claim(hash)
            Files.writeString(cache.toPath().resolve("piece.bin"), "retain")
            owner.close()
            // A child could have survived its parent's crash even long after startup.
            Files.setLastModifiedTime(cache.toPath().parent.resolve(".nuvio-download-cache-owner"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000))
            assertEquals(0L, rescuer.scavenge(), "Missing child PID cannot prove that an orphan is safe to delete")
            assertTrue(cache.exists())

            val anotherHash = "f".repeat(40)
            val runningChildOwner = DownloadTorrentCacheOwnership(root)
            val activeCache = runningChildOwner.claim(anotherHash)
            runningChildOwner.recordProcessId(anotherHash, ProcessHandle.current().pid())
            runningChildOwner.close()
            assertEquals(0L, rescuer.scavenge(), "A living TorrServer child forbids cache deletion")
            assertTrue(activeCache.exists())
        } finally { owner.close(); rescuer.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun crashDuringChildRestartCannotMistakeAnOldDeadPidForSafeOrphan() {
        val directory = Files.createTempDirectory("download-cache-restart-fence-test")
        val root = directory.resolve("download-torrents")
        val owner = DownloadTorrentCacheOwnership(root)
        val rescuer = DownloadTorrentCacheOwnership(root)
        try {
            val hash = "a".repeat(40)
            val cache = owner.claim(hash)
            Files.writeString(cache.toPath().resolve("piece.bin"), "new-process-may-be-live")
            owner.recordProcessId(hash, Long.MAX_VALUE) // Previous child already died.
            owner.invalidateProcessId(hash) // Must happen BEFORE replacement child starts.
            owner.close() // Simulate parent crash before the replacement PID can be recorded.
            assertEquals(0L, rescuer.scavenge())
            assertTrue(cache.exists(), "Unknown replacement process still owns this cache")
        } finally { owner.close(); rescuer.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun invalidOwnershipMarkersNeverAuthorizeCacheDeletion() {
        val directory = Files.createTempDirectory("download-cache-marker-test")
        val root = directory.resolve("download-torrents")
        val owner = DownloadTorrentCacheOwnership(root)
        val another = DownloadTorrentCacheOwnership(root)
        try {
            val cache = owner.claim("d".repeat(40))
            val marker = cache.toPath().parent.resolve(".nuvio-download-cache-owner")
            val file = cache.toPath().resolve("custom.data")
            Files.writeString(file, "private")
            owner.close()
            Files.writeString(marker, "invalid ownership marker")
            assertEquals(0L, another.scavenge())
            assertEquals("private", Files.readString(file))
        } finally { owner.close(); another.close(); directory.toFile().deleteRecursively() }
    }
}
