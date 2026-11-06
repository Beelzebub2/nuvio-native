package com.nuvio.app.features.downloads

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadQuotaLifecycleTest {
    private val gib = 1L shl 30
    private fun waitFor(predicate: () -> Boolean) = runBlocking {
        withTimeout(12_000L) { while (!predicate()) delay(20) }
    }

    @Test fun deferredCleanupFailureRetainsTheQueueAndQuotaUntilActualDeletionSucceeds(): Unit = runBlocking {
        val root = Files.createTempDirectory("download-cleanup-accounting").toFile()
        val original = DownloadDesktopFeatures.directory()
        val profile = ProfileRepository.activeProfileId
        val id = "cleanup-accounting"
        val file = File(File(root, id).apply { mkdirs() }, "fixture.mp4")
        File(file.parentFile, ".starlit-owner").writeText(id)
        file.writeBytes(byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109, 1, 2, 3, 4))
        val item = DownloadItem(id, "movie", "cleanup-meta", "movie", "cleanup-video", "Fixture",
            streamTitle = "1080p", providerName = "Fixture", sourceUrl = "https://example.invalid/file.mp4",
            destinationDirectory = root.absolutePath, fileName = file.name, localFileUri = file.toURI().toString(),
            status = DownloadStatus.Completed, downloadedBytes = file.length(), totalBytes = file.length(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
        val proof = DesktopDownloadProof(id, file.name, downloadSourceFingerprint(item, item.sourceUrl),
            "-", "fixture", file.length(), downloadSha256File(file))
        writeDesktopDownloadProof(file, proof)
        val ioLock = downloadPathLock(item)
        try {
            DownloadsRepository.clearLocalState()
            DownloadDesktopFeatures.setDirectory(root)
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(item)))
            DownloadsRepository.ensureLoaded()
            waitFor { DownloadsRepository.uiState.value.items.singleOrNull()?.isPlayable == true }
            ioLock.lock()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                DownloadsRepository.removeSelectedVerifiedDownloads(setOf(id), profile)
            }
            assertFalse(deletion.isCompleted, "Cleanup must wait for the transfer IO lock")
            assertEquals(file.length(), trackedDownloadBytes(DownloadsRepository.uiState.value.items))
            downloadProofFile(file).writeText("changed after confirmation")
            ioLock.unlock()
            assertEquals(0, deletion.await())
            assertTrue(file.exists())
            assertEquals(listOf(id), DownloadsRepository.uiState.value.items.map { it.id })
            assertEquals(file.length(), trackedDownloadBytes(DownloadsRepository.uiState.value.items))
            assertEquals(listOf(id), DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).map { it.id })

            writeDesktopDownloadProof(file, proof)
            assertTrue(verifiedDesktopDownloadContent(file, proof))
            assertEquals(1, DownloadsRepository.removeSelectedVerifiedDownloads(setOf(id), profile))
            assertFalse(file.exists())
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
            assertEquals(0L, trackedDownloadBytes(DownloadsCodec.decodeItems(DownloadsStorage.loadPayload())))
        } finally {
            if (ioLock.isLocked) ioLock.unlock()
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original)))
            root.deleteRecursively()
        }
    }

    @Test fun discoveredOversizePausesWithoutLateCompletionOrAutomaticResume() {
        assertNotNull(System.getProperty("nuvio.dataDir"))
        val root = Files.createTempDirectory("download-quota-lifecycle").toFile()
        val original = DownloadDesktopFeatures.directory()
        val profile = ProfileRepository.activeProfileId
        val previousTarget = DownloadStorageManagement.quotaBytes(profile)
        val previousEnforcement = DownloadStorageManagement.enforceQuota(profile)
        val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests.incrementAndGet()
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"quota\"")
                    exchange.sendResponseHeaders(200, gib + 1L)
                    // The downloader waits for a validated media prefix before
                    // reporting the discovered Content-Length. Send a real MP4
                    // ftyp prefix rather than blocking with an empty body.
                    exchange.responseBody.use { response ->
                        response.write(byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112,
                            105, 115, 111, 109, 1, 2, 3, 4))
                        response.flush()
                        gate.await(8, TimeUnit.SECONDS)
                    }
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadDesktopFeatures.setDirectory(root)
            DownloadStorageManagement.setQuotaBytes(profile, gib)
            DownloadStorageManagement.setEnforceQuota(profile, true)
            assertEquals(gib, DownloadStorageManagement.quotaBytes(profile))
            assertTrue(DownloadStorageManagement.enforceQuota(profile), "The active profile's hard quota must be enabled")
            assertEquals(DownloadEnqueueResult.Started, DownloadsRepository.enqueueFromStream("movie", "quota-video", "quota-meta", "movie",
                "Quota fixture", null, null, null, null, null, null, null,
                StreamItem(name = "1080p", url = "http://127.0.0.1:${server.address.port}/video.mp4",
                    addonName = "Fixture", addonId = "fixture")))
            try {
                waitFor { DownloadsRepository.uiState.value.items.singleOrNull()?.status == DownloadStatus.Paused }
            } catch (timedOut: TimeoutCancellationException) {
                val state = DownloadsRepository.uiState.value.items.singleOrNull()
                throw AssertionError(
                    "Oversize transfer did not pause: requests=${requests.get()}, " +
                        "status=${state?.status}, phase=${state?.phase}, " +
                        "size=${state?.totalBytes}, error=${state?.errorMessage}",
                    timedOut,
                )
            }
            val paused = DownloadsRepository.uiState.value.items.single()
            assertEquals(gib + 1L, paused.totalBytes)
            assertFalse(downloadPath(paused).exists())
            gate.countDown()
            DownloadStorageManagement.setEnforceQuota(profile, false)
            DownloadsRepository.onStoragePolicyChanged()
            Thread.sleep(250) // deliver the cancelled HTTP completion
            assertEquals(1, requests.get())
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
        } finally {
            gate.countDown()
            server.stop(0)
            DownloadsRepository.pauseActiveDownloads()
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadStorageManagement.setQuotaBytes(profile, previousTarget)
            DownloadStorageManagement.setEnforceQuota(profile, previousEnforcement)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original)))
            root.deleteRecursively()
        }
    }

    @Test fun loweringQuotaReducesAllReservationsBeforeStartingQueuedTransfers() {
        val root = Files.createTempDirectory("download-quota-reduce").toFile()
        val original = DownloadDesktopFeatures.directory()
        val profile = ProfileRepository.activeProfileId
        val previousTarget = DownloadStorageManagement.quotaBytes(profile)
        val previousEnforcement = DownloadStorageManagement.enforceQuota(profile)
        val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val size = 700L * 1024L * 1024L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests.incrementAndGet()
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"quota\"")
                    exchange.sendResponseHeaders(200, size)
                    exchange.responseBody.flush()
                    gate.await(8, TimeUnit.SECONDS)
                    exchange.responseBody.close()
                }
            }
            start()
        }
        try {
            DownloadsRepository.clearLocalState()
            DownloadDesktopFeatures.setDirectory(root)
            DownloadDesktopFeatures.setParallelDownloads(2)
            DownloadStorageManagement.setQuotaBytes(profile, 2L * gib)
            DownloadStorageManagement.setEnforceQuota(profile, true)
            val items = (1..3).map { index ->
                DownloadItem("quota-$index", "movie", "meta-$index", "movie", "video-$index", "Fixture $index",
                    streamTitle = "1080p", providerName = "Fixture",
                    sourceUrl = "http://127.0.0.1:${server.address.port}/$index.mp4", fileName = "$index.mp4",
                    destinationDirectory = root.absolutePath, status = DownloadStatus.Downloading,
                    totalBytes = size, createdAtEpochMs = index.toLong(), updatedAtEpochMs = 1L)
            }
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(items))
            DownloadsRepository.ensureLoaded()
            waitFor { requests.get() == 2 && DownloadsRepository.uiState.value.items.take(2).all { it.totalBytes == size } }
            DownloadStorageManagement.setQuotaBytes(profile, gib)
            DownloadsRepository.onStoragePolicyChanged()
            val after = DownloadsRepository.uiState.value.items
            assertEquals(1, after.count { it.status == DownloadStatus.Downloading })
            assertEquals(2, after.count { it.status == DownloadStatus.Paused })
            assertEquals(2, requests.get(), "Queued transfer must not start while reservations are being reduced")
        } finally {
            gate.countDown()
            server.stop(0)
            DownloadsRepository.pauseActiveDownloads()
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("{}")
            DownloadStorageManagement.setQuotaBytes(profile, previousTarget)
            DownloadStorageManagement.setEnforceQuota(profile, previousEnforcement)
            DownloadDesktopFeatures.setParallelDownloads(DefaultParallelDownloads)
            DownloadDesktopFeatures.setDirectory(File(requireNotNull(original)))
            root.deleteRecursively()
        }
    }
}
