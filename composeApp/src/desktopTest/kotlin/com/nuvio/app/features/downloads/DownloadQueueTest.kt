package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DownloadQueueTest {
    private val media = byteArrayOf(0,0,0,16,102,116,121,112,105,115,111,109,1,2,3,4)
    private fun enqueue(episode: Int, url: String) = DownloadsRepository.enqueueFromStream("series", "absolute:${episode + 12}",
        "queue-show", "series", "Fixture", null,null,null,1,episode,"Episode $episode",null,
        StreamItem(name = "1080p", url = url, addonName = "Local fixture", addonId = "fixture"))
    private fun waitFor(predicate: () -> Boolean) = runBlocking {
        withTimeout(8_000) { while (!predicate()) delay(20) }
    }

    @Test fun completedDownloadRestoresPromptlyAndBecomesPlayableOnlyAfterAsyncVerification() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "Tests must use isolated storage")
        val root = Files.createTempDirectory("download-background-sha").toFile()
        val original = DownloadDesktopFeatures.directory()
        val id = "async-verified-episode"
        var observer: Job? = null
        try {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            val file = File(File(root, id).apply { mkdirs() }, "$id.mp4")
            File(file.parentFile, ".starlit-owner").writeText(id)
            // 8 MiB of deterministic local media: large enough to exercise streamed SHA,
            // but bounded in size and created without allocating a multi-megabyte array.
            val chunk = ByteArray(128 * 1024) { index -> (index * 31 + 7).toByte() }
            file.outputStream().buffered().use { output ->
                output.write(media)
                repeat(64) { output.write(chunk) }
            }
            val saved = DownloadItem(
                id = id, contentType = "series", parentMetaId = "async-show", parentMetaType = "series",
                videoId = "async-show:1:1", title = "Fixture", seasonNumber = 1, episodeNumber = 1,
                streamTitle = "1080p", providerName = "fixture",
                sourceUrl = "http://127.0.0.1/offline.mp4", fileName = file.name,
                localFileUri = file.toURI().toString(), destinationDirectory = root.absolutePath,
                status = DownloadStatus.Completed, downloadedBytes = file.length(), totalBytes = file.length(),
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            )
            writeDesktopDownloadProof(file, DesktopDownloadProof(
                saved.id, saved.fileName, downloadSourceFingerprint(saved, saved.sourceUrl),
                downloadEndpointFingerprint(URI.create(saved.sourceUrl)),
                "\"fixture\"", file.length(), downloadSha256File(file),
            ))
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(saved)))

            // Observe the StateFlow inline at publish time: the IO worker can finish a
            // fast test fixture before ensureLoaded returns, so a post-return phase
            // assertion would race. Unconfined observation records the pending phase
            // before the background verifier is scheduled.
            val observed = CopyOnWriteArrayList<DownloadItem>()
            val pendingLookup = AtomicInteger(-1)
            observer = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                DownloadsRepository.uiState.collect { state ->
                    state.items.firstOrNull { it.id == saved.id }?.let { item ->
                        observed += item
                        if (item.phase == SAVED_DOWNLOAD_VERIFICATION_PHASE) {
                            val available = DownloadsRepository.findPlayableDownloadByVideoId(saved.videoId)
                            pendingLookup.compareAndSet(-1, if (available == null) 0 else 1)
                        }
                    }
                }
            }

            val started = System.nanoTime()
            DownloadsRepository.ensureLoaded()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMs < 2_000L, "Saved metadata should load without waiting for an 8 MiB SHA-256 ($elapsedMs ms)")
            val pending = assertNotNull(observed.firstOrNull { it.phase == SAVED_DOWNLOAD_VERIFICATION_PHASE },
                "A completed file must enter an observable pending-verification state")
            assertEquals(DownloadStatus.Completed, pending.status)
            assertFalse(pending.isPlayable, "Unverified bytes must never be advertised as playable")
            assertEquals(0, pendingLookup.get(), "Playback lookup must deny an unverified saved file")

            waitFor {
                DownloadsRepository.uiState.value.items.singleOrNull()?.let {
                    it.id == id && it.phase == null && it.isPlayable &&
                        DownloadsRepository.findPlayableDownloadByVideoId(saved.videoId) != null
                } == true
            }
            val ready = DownloadsRepository.uiState.value.items.single()
            assertEquals(DownloadStatus.Completed, ready.status)
            assertEquals(file.toURI().toString(), DownloadsRepository.playableLocalFileUri(ready))
            assertEquals(file.length(), ready.totalBytes)
            assertTrue(file.isFile, "Background verification must preserve the saved media")
            assertTrue(downloadProofFile(file).isFile, "Background verification must preserve its proof")
        } finally {
            observer?.cancel()
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original)))
            root.deleteRecursively()
        }
    }

    @Test fun damagedDownloadsPayloadRecoversPreviousGoodVersionOrFailsWithoutOverwritingIt() {
        val key = ProfileScopedKey.of("downloads")
        val store = DesktopStorage.store("nuvio_downloads")
        val original = store.getString(key)
        try {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("""{"items":[]}""")
            assertFailsWith<IllegalArgumentException> { DownloadsStorage.savePayload("{damaged-current") }
            assertEquals("""{"items":[]}""", store.getString(key))
            // Simulate a malformed payload introduced by an older build or
            // external modification; normal writes now reject this content.
            store.putString(key, "{damaged-current")
            // The backup contains the last valid per-profile queue.
            assertEquals("""{"items":[]}""", DownloadsStorage.loadPayload())
            DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())

            DownloadsRepository.clearLocalState()
            val queued = DownloadItem(
                id = "preserved", contentType = "series", parentMetaId = "fixture",
                parentMetaType = "series", videoId = "fixture:1", title = "Fixture",
                streamTitle = "1080p", providerName = "fixture",
                sourceUrl = "https://example.invalid/media.mp4", fileName = "fixture.mp4",
                status = DownloadStatus.Paused, createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            )
            DownloadsStorage.savePayload("""{"items":${Json.encodeToString(listOf(queued))}}""")
            DownloadsRepository.ensureLoaded()
            assertEquals(listOf("preserved"), DownloadsRepository.uiState.value.items.map { it.id })

            store.putString(key, "{damaged-previous")
            store.putString(key, "{damaged-current-again")
            val damaged = store.getString(key)
            assertFailsWith<IllegalStateException> { DownloadsRepository.onProfileChanged() }
            assertFailsWith<IllegalStateException> { DownloadsRepository.ensureLoaded() }
            assertEquals(damaged, store.getString(key), "No failed load may persist an empty queue")
            assertEquals(listOf("preserved"), DownloadsRepository.uiState.value.items.map { it.id },
                "Corrupt data must not emit a fabricated empty list into the UI")
        } finally {
            DownloadsRepository.clearLocalState()
            store.putString(key, original)
        }
    }

    @Test fun removingActiveAndQueuedDownloadsNeverStartsTheRemovedQueueOrRestoresLateResults() {
        assertNotNull(System.getProperty("nuvio.dataDir"))
        val root = Files.createTempDirectory("download-bulk-queue").toFile()
        val original = DownloadDesktopFeatures.directory()
        val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
                runCatching {
                    exchange.sendResponseHeaders(200, media.size.toLong())
                    exchange.responseBody.use { it.write(media) }
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            DownloadDesktopFeatures.setParallelDownloads(1)
            val url = "http://127.0.0.1:${server.address.port}/fixture.mp4"
            enqueue(1, url); enqueue(2, url); enqueue(3, url)
            waitFor { requests.get() == 1 }
            val items = DownloadsRepository.uiState.value.items
            val profileId = ProfileRepository.activeProfileId
            assertEquals(profileId, DownloadsRepository.uiState.value.profileId)
            DownloadsRepository.cancelDownloads(items.map { it.id }.toSet(), profileId + 1)
            assertEquals(3, DownloadsRepository.uiState.value.items.size, "A stale profile confirmation must be ignored")
            DownloadsRepository.cancelDownloads(items.map { it.id }.toSet(), profileId)
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
            gate.countDown()
            Thread.sleep(400) // let the cancelled request's late response/callbacks arrive
            assertEquals(1, requests.get(), "Removed queued tasks must never open a transfer")
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
            waitFor { items.all { !downloadPath(it).exists() && !File(downloadPath(it).path + ".part").exists() } }
            DownloadsRepository.clearLocalState(); DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty(), "Batch removal must survive reload")
        } finally {
            gate.countDown(); server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setParallelDownloads(DefaultParallelDownloads)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
    }

    @Test fun severalEpisodesDownloadAtOnceUpToTheChosenLimit() {
        val root = Files.createTempDirectory("download-parallel").toFile()
        val original = DownloadDesktopFeatures.directory()
        val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests.incrementAndGet()
                gate.await(6, TimeUnit.SECONDS)
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"fixture\"")
                    exchange.sendResponseHeaders(200, media.size.toLong())
                    exchange.responseBody.use { it.write(media) }
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            assertEquals(DefaultParallelDownloads, DownloadDesktopFeatures.parallelDownloads())
            DownloadDesktopFeatures.setParallelDownloads(2)
            val url = "http://127.0.0.1:${server.address.port}/fixture.mp4"
            (1..4).forEach { enqueue(it, url) }
            waitFor { requests.get() == 2 }
            Thread.sleep(150)
            assertEquals(2, requests.get(), "Only two transfers may run at once")
            assertEquals(listOf("Queued", "Queued"), DownloadsRepository.uiState.value.items.drop(2).map { it.phase })
            // Raising the limit starts the next queued episode straight away, in queue order.
            DownloadDesktopFeatures.setParallelDownloads(3)
            waitFor { requests.get() == 3 }
            assertEquals("Queued", DownloadsRepository.uiState.value.items.last().phase)
            gate.countDown()
            waitFor { DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Completed } }
            assertEquals(4, requests.get())
        } finally {
            gate.countDown(); server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setParallelDownloads(DefaultParallelDownloads)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
    }

    @Test fun bulkSubsetRemovesOnlyItsOwnedFilesAndPersistsTheRemainingDownloads() {
        assertNotNull(System.getProperty("nuvio.dataDir"))
        val root = Files.createTempDirectory("download-bulk-files").toFile()
        fun fixture(id: String, completed: Boolean): DownloadItem {
            val folder = File(root, id).apply { mkdirs() }
            File(folder, ".starlit-owner").writeText(id)
            val file = File(folder, "$id.mp4")
            if (completed) file.writeBytes(media) else File(file.path + ".part").writeBytes(media)
            File(file.path + ".validator").writeText("fixture")
            return DownloadItem(id, "movie", id, "movie", id, id, streamTitle = "Fixture",
                providerName = "Fixture", sourceUrl = "https://example.test/$id.mp4",
                localFileUri = if (completed) file.toURI().toString() else null,
                destinationDirectory = root.absolutePath, fileName = file.name,
                status = if (completed) DownloadStatus.Completed else DownloadStatus.Paused,
                createdAtEpochMs = 0, updatedAtEpochMs = 0)
        }
        try {
            val completed = fixture("completed", true)
            val partial = fixture("partial", false)
            val keep = fixture("keep", true)
            val unrelated = File(root, "user-notes.txt").apply { writeText("Keep me") }
            val neighbor = File(downloadPath(completed).parentFile, "user-file.txt").apply { writeText("Also keep me") }
            val sidecars = File(downloadPath(completed).path + ".subtitles").apply { mkdirs() }
            File(sidecars, "0.srt").writeText("Offline captions")
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{\"items\":" + Json.encodeToString(listOf(completed, partial, keep)) + "}")
            DownloadsRepository.ensureLoaded()
            DownloadsRepository.cancelDownloads(setOf(completed.id, partial.id, "missing"), ProfileRepository.activeProfileId)
            assertEquals(listOf(keep.id), DownloadsRepository.uiState.value.items.map { it.id })
            waitFor { !downloadPath(completed).exists() && !File(downloadPath(partial).path + ".part").exists() &&
                !File(downloadPath(partial).path + ".validator").exists() && !sidecars.exists() }
            assertFalse(File(downloadPath(partial).path + ".validator").exists())
            assertFalse(sidecars.exists())
            assertEquals("Keep me", unrelated.readText())
            assertEquals("Also keep me", neighbor.readText())
            assertContentEquals(media, downloadPath(keep).readBytes())
            DownloadsRepository.clearLocalState(); DownloadsRepository.ensureLoaded()
            assertEquals(listOf(keep.id), DownloadsRepository.uiState.value.items.map { it.id })
        } finally {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            root.deleteRecursively()
        }
    }

    @Test fun queueIsBoundedDuplicateSafeAndDurableAndOfflineLookupUsesTheRightEpisode() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "Tests must use isolated storage")
        val root = Files.createTempDirectory("download-queue").toFile()
        val original = DownloadDesktopFeatures.directory()
        val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests.incrementAndGet()
                gate.await(6, TimeUnit.SECONDS)
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"fixture\"")
                    exchange.sendResponseHeaders(200, media.size.toLong())
                    exchange.responseBody.use { it.write(media) }
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            DownloadDesktopFeatures.setParallelDownloads(1)
            val url = "http://127.0.0.1:${server.address.port}/fixture.mp4"
            assertEquals(DownloadEnqueueResult.Started, enqueue(1,url))
            assertEquals(DownloadEnqueueResult.Started, enqueue(2,url))
            assertEquals(DownloadEnqueueResult.AlreadyAdded, enqueue(1,url))
            waitFor { requests.get() == 1 }
            Thread.sleep(150)
            assertEquals(1, requests.get(), "A second request must remain queued")
            assertEquals(listOf(1,2), DownloadsRepository.uiState.value.items.map { it.episodeNumber })
            assertEquals("Queued", DownloadsRepository.uiState.value.items[1].phase)
            DownloadsRepository.pauseActiveDownloads()
            assertTrue(DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Paused })
            val payload = DownloadsStorage.loadPayload()
            // The folder is stored canonically; CI temp paths can use 8.3 short names (RUNNER~1).
            assertTrue(payload.orEmpty().contains(root.canonicalPath.replace("\\", "\\\\")))
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertEquals(2, DownloadsRepository.uiState.value.items.size)
            assertTrue(DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Paused })
            assertEquals(1, requests.get())
            gate.countDown()
            DownloadsRepository.resumeAll()
            waitFor { DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Completed } }
            val first = assertNotNull(DownloadsRepository.findPlayableDownload("queue-show",1,1,"absolute:13"))
            assertEquals("absolute:13", first.videoId)
            assertNull(DownloadsRepository.findPlayableDownload("different-parent",1,1,"absolute:13"))
            val firstFile = File(java.net.URI(assertNotNull(DownloadsRepository.playableLocalFileUri(first))))
            assertContentEquals(media, firstFile.readBytes())
            assertEquals(DownloadEnqueueResult.AlreadyAdded, enqueue(1,url))
            assertContentEquals(media, firstFile.readBytes())
            server.stop(0) // every playback lookup below is offline
            DownloadsRepository.clearLocalState(); DownloadsRepository.ensureLoaded()
            waitFor { DownloadsRepository.findPlayableDownload("queue-show",1,2,"absolute:14") != null }
            assertNotNull(DownloadsRepository.findPlayableDownload("queue-show",1,2,"absolute:14"))
            assertTrue(firstFile.delete())
            assertNull(DownloadsRepository.findPlayableDownload("queue-show",1,1,"absolute:13"))
            DownloadsRepository.clearLocalState(); DownloadsRepository.ensureLoaded()
            waitFor { DownloadsRepository.uiState.value.items.first { it.episodeNumber == 1 }.status == DownloadStatus.Failed }
            assertEquals(DownloadStatus.Failed, DownloadsRepository.uiState.value.items.first { it.episodeNumber == 1 }.status)
        } finally {
            gate.countDown(); server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setParallelDownloads(DefaultParallelDownloads)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
    }

    @Test fun aManuallyPickedSourceReplacesAFailedTaskButNotAFinishedFile() {
        val root = Files.createTempDirectory("download-replace").toFile()
        val original = DownloadDesktopFeatures.directory()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/missing.mp4") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
            createContext("/fixture.mp4") { exchange ->
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.responseHeaders.add("ETag", "\"fixture\"")
                exchange.sendResponseHeaders(200, media.size.toLong())
                exchange.responseBody.use { it.write(media) }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(DownloadEnqueueResult.Started, enqueue(1, "$base/missing.mp4"))
            waitFor { DownloadsRepository.uiState.value.items.single().status == DownloadStatus.Failed }
            assertEquals(DownloadEnqueueResult.Replaced, enqueue(1, "$base/fixture.mp4"))
            waitFor { DownloadsRepository.uiState.value.items.single().status == DownloadStatus.Completed }
            assertEquals("$base/fixture.mp4", DownloadsRepository.uiState.value.items.single().sourceUrl)
            assertEquals(DownloadEnqueueResult.AlreadyAdded, enqueue(1, "$base/missing.mp4"))
            assertEquals(DownloadStatus.Completed, DownloadsRepository.uiState.value.items.single().status)
        } finally {
            server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
    }

    @Test fun profileGenerationRejectsLateCallbacksFromThePreviousQueue() {
        val root = Files.createTempDirectory("download-generation").toFile()
        val original = DownloadDesktopFeatures.directory()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            createContext("/") { exchange ->
                entered.countDown(); gate.await(5,TimeUnit.SECONDS)
                runCatching { exchange.sendResponseHeaders(200,media.size.toLong()); exchange.responseBody.use { it.write(media) } }
            }; start()
        }
        try {
            DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            enqueue(1,"http://127.0.0.1:${server.address.port}/fixture.mp4")
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            DownloadsRepository.clearLocalState() // same invalidation that runs before profile ID changes
            DownloadsStorage.savePayload("{}") // replacement profile payload
            DownloadsRepository.onProfileChanged()
            gate.countDown(); Thread.sleep(300)
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
            assertFalse(DownloadsStorage.loadPayload().orEmpty().contains("queue-show"))
        } finally {
            gate.countDown(); server.stop(0); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original))); root.deleteRecursively()
        }
    }
}
