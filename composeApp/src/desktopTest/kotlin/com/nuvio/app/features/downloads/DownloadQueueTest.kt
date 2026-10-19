package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
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
            assertNotNull(DownloadsRepository.findPlayableDownload("queue-show",1,2,"absolute:14"))
            assertTrue(firstFile.delete())
            assertNull(DownloadsRepository.findPlayableDownload("queue-show",1,1,"absolute:13"))
            DownloadsRepository.clearLocalState(); DownloadsRepository.ensureLoaded()
            assertEquals(DownloadStatus.Failed, DownloadsRepository.uiState.value.items.first { it.episodeNumber == 1 }.status)
        } finally {
            gate.countDown(); server.stop(0)
            DownloadsRepository.pauseActiveDownloads(); DownloadsRepository.clearLocalState(); DownloadsStorage.savePayload("{}")
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
