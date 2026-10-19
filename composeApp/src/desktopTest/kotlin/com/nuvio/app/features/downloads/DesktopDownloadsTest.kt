package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.*
import com.nuvio.app.features.p2p.*
import com.nuvio.app.features.streams.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopDownloadsTest {
    private val media = byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109, 1, 2, 3, 4)
    private fun item(root: File, id: String = "task1", url: String = "http://127.0.0.1/fixture.mp4") = DownloadItem(
        id, "series", "show", "series", "show:1:1", "Fixture", seasonNumber = 1, episodeNumber = 1,
        streamTitle = "1080p", providerName = "fixture", sourceUrl = url, fileName = "episode_$id.mp4",
        destinationDirectory = root.absolutePath, status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0)
    private fun server(block: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
            createContext("/", block); start()
        }
    private fun HttpServer.url() = "http://127.0.0.1:${address.port}/fixture.mp4"
    private fun partial(item: DownloadItem, body: ByteArray, validator: String = "\"fixture\"") {
        val file = downloadPath(item)
        file.parentFile.mkdirs()
        File(file.parentFile, ".starlit-owner").writeText(item.id)
        File(file.path + ".part").writeBytes(body)
        File(file.path + ".validator").writeText("$validator\n${media.size}")
    }
    private suspend fun copy(item: DownloadItem): File {
        val io = DownloadIo()
        return try { copyDesktopDownload(item, item.sourceUrl, io, onProgress = { _, _ -> }) } finally { io.close() }
    }

    @Test fun validatesResumeAndKeepsTheOriginalFolderAfterDefaultChanges() = runBlocking {
        val root = Files.createTempDirectory("download-range").toFile()
        var range: String? = null
        val http = server { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            assertEquals("\"fixture\"", exchange.requestHeaders.getFirst("If-Range"))
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.responseHeaders.add("Content-Range", "bytes 4-15/16")
            exchange.sendResponseHeaders(206, 12)
            exchange.responseBody.use { it.write(media, 4, 12) }
        }
        try {
            val download = item(root, url = http.url())
            partial(download, media.copyOfRange(0, 4))
            val anotherFolder = Files.createTempDirectory("download-new-default").toFile()
            val oldDefault = DownloadDesktopFeatures.directory()
            try {
                DownloadDesktopFeatures.setDirectory(anotherFolder)
                val file = copy(download)
                assertEquals("bytes=4-", range)
                assertContentEquals(media, file.readBytes())
                assertEquals(root.canonicalFile, file.parentFile.parentFile.canonicalFile)
                assertTrue(anotherFolder.listFiles().orEmpty().isEmpty())
            } finally { DownloadDesktopFeatures.setDirectory(File(requireNotNull(oldDefault))); anotherFolder.deleteRecursively() }
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun aChangedEntityRestartsBeforeAppendingAnyBytes() = runBlocking {
        val root = Files.createTempDirectory("download-entity").toFile()
        val calls = AtomicInteger()
        val http = server { exchange ->
            val attempt = calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("ETag", "\"new-entity\"")
            if (attempt == 1) {
                exchange.responseHeaders.add("Content-Range", "bytes 4-15/16")
                exchange.sendResponseHeaders(206, 12)
                exchange.responseBody.use { it.write(media,4,12) }
            } else {
                assertNull(exchange.requestHeaders.getFirst("Range"))
                exchange.sendResponseHeaders(200, media.size.toLong())
                exchange.responseBody.use { it.write(media) }
            }
        }
        try {
            val download = item(root, url = http.url())
            partial(download, byteArrayOf(99,99,99,99))
            assertContentEquals(media, copy(download).readBytes())
            assertEquals(2, calls.get())
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun provenFinalFileRecoversOfflineBeforeAnySourceLookup() {
        val root = Files.createTempDirectory("download-final-recovery").toFile()
        val done = CountDownLatch(1)
        var failure: String? = null
        try {
            val download = item(root).copy(sourceUrl = "", sourceSelection = DownloadSourceSelection(providerId = "missing-provider"))
            partial(download, media)
            val path = downloadPath(download)
            Files.move(File(path.path + ".part").toPath(), path.toPath())
            DownloadsPlatformDownloader.start(DownloadPlatformRequest(download, onResolved = { error("Recovered file must not resolve sources") }),
                { _, _ -> }, { uri, bytes -> assertEquals(path.toURI().toString(),uri); assertEquals(16L,bytes); done.countDown() },
                { failure = it; done.countDown() }, { done.countDown() })
            assertTrue(done.await(2,TimeUnit.SECONDS))
            assertNull(failure)
        } finally { root.deleteRecursively() }
    }

    @Test fun rangeIgnoredRestartsAndUnsatisfiableRangeRecoversWithoutAppending() = runBlocking {
        for (status in listOf(200, 416)) {
            val root = Files.createTempDirectory("download-restart").toFile()
            val calls = AtomicInteger()
            val http = server { exchange ->
                val count = calls.incrementAndGet()
                if (status == 416 && count == 1) { exchange.sendResponseHeaders(416, -1); exchange.close() }
                else {
                    if (status == 416) assertNull(exchange.requestHeaders.getFirst("Range"))
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("ETag", "\"fixture\"")
                    exchange.sendResponseHeaders(200, media.size.toLong())
                    exchange.responseBody.use { it.write(media) }
                }
            }
            try {
                val download = item(root, url = http.url())
                partial(download, byteArrayOf(9, 9, 9, 9))
                assertContentEquals(media, copy(download).readBytes())
                assertEquals(if (status == 416) 2 else 1, calls.get())
            } finally { http.stop(0); root.deleteRecursively() }
        }
    }

    @Test fun badRangeAndPlaylistNeverProduceACompletedFile() = runBlocking {
        for (bad in listOf("range", "playlist", "html")) {
            val root = Files.createTempDirectory("download-invalid").toFile()
            val http = server { exchange ->
                exchange.responseHeaders.add("Content-Type", if (bad == "html") "text/html" else "application/octet-stream")
                if (bad == "range") exchange.responseHeaders.add("Content-Range", "bytes 5-15/16")
                exchange.responseHeaders.add("ETag", "\"fixture\"")
                val data = if (bad == "playlist") "#EXTM3U\nfixture".toByteArray() else media
                exchange.sendResponseHeaders(if (bad == "range") 206 else 200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
            try {
                val download = item(root, url = http.url())
                if (bad == "range") partial(download, media.copyOfRange(0, 4))
                assertFails { copy(download) }
                assertFalse(downloadPath(download).exists())
                if (bad == "range") assertEquals(4L, File(downloadPath(download).path + ".part").length())
            } finally { http.stop(0); root.deleteRecursively() }
        }
    }

    @Test fun splitWhitespacePlaylistCannotBypassMediaValidation() = runBlocking {
        val root = Files.createTempDirectory("download-prefix").toFile()
        val http = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            val data = (" ".repeat(180) + "#EXTM3U\n#EXTINF:4\nsegment.ts").toByteArray()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { output -> for (byte in data) { output.write(byte.toInt()); output.flush() } }
        }
        try {
            val download = item(root, url = http.url())
            assertFails { copy(download) }
            assertFalse(downloadPath(download).exists())
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun truncatedResponseRetriesAreBoundedAndNeverFinalized() = runBlocking {
        val root = Files.createTempDirectory("download-truncated").toFile()
        val calls = AtomicInteger()
        val http = server { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, 100)
            runCatching { exchange.responseBody.write(media); exchange.close() }
        }
        try {
            val download = item(root, url = http.url())
            assertFails { withTimeout(15_000) { copy(download) } }
            assertEquals(3, calls.get())
            assertFalse(downloadPath(download).exists())
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun cancelClosesAnIdleBodyPromptlyAndLeavesAResumablePartial() {
        val root = Files.createTempDirectory("download-cancel").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val progress = CountDownLatch(1)
        var success = false
        val http = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(media); exchange.responseBody.flush(); entered.countDown()
            release.await(8, TimeUnit.SECONDS)
            runCatching { exchange.close() }
        }
        try {
            val download = item(root, url = http.url())
            val handle = DownloadsPlatformDownloader.start(DownloadPlatformRequest(download), { bytes, _ -> if (bytes > 0) progress.countDown() },
                { _, _ -> success = true }, { error("Unexpected failure: $it") }, { paused.countDown() })
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(progress.await(5, TimeUnit.SECONDS))
            handle.cancel()
            assertTrue(paused.await(2, TimeUnit.SECONDS), "Cancellation must close blocking input without waiting for the server")
            assertFalse(success)
            assertFalse(downloadPath(download).exists())
            assertTrue(File(downloadPath(download).path + ".part").exists())
        } finally { release.countDown(); http.stop(0); root.deleteRecursively() }
    }

    @Test fun englishCaptionsRemainUsableWithTheSubtitleServerStopped() = runBlocking {
        val root = Files.createTempDirectory("download-captions").toFile()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = server { exchange ->
            requests += exchange.requestURI.path
            val body = "1\n00:00:01,000 --> 00:00:02,000\nOffline caption\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            val video = File(root,"offline.mp4").also { it.writeBytes(media) }
            val base = http.url().substringBeforeLast('/')
            val tracks = (0..5).map { StreamSubtitle("$base/fr$it.srt","fr") } + StreamSubtitle("$base/en.srt","en","English")
            DownloadSubtitles.prepare(item(root).copy(sourceSubtitles = tracks), video.toURI().toString())
            http.stop(0)
            val english = DownloadSubtitles.localSubtitles(video.toURI().toString()).single { it.language == "en" }
            assertTrue(english.url.startsWith("file:"))
            assertTrue(com.nuvio.app.features.player.PlayerSubtitleCueParser.parse(File(java.net.URI(english.url)).readText(), english.url).isNotEmpty())
            assertTrue(requests.indexOf("/en.srt") in 0..3, "English should be fetched before later-language batches")
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun taskOwnershipPreventsTraversalReplacementAndUnrelatedFileDeletion() = runBlocking {
        val root = Files.createTempDirectory("download-owner").toFile()
        try {
            val download = item(root)
            assertFails { downloadPath(download.copy(fileName = "../outside.mp4")) }
            assertFails { downloadPath(download.copy(id = "../bad")) }
            val file = downloadPath(download)
            file.parentFile.mkdirs()
            File(file.parentFile, ".starlit-owner").writeText("another-task")
            file.writeText("User file")
            assertFalse(removeOwnedDownload(download))
            assertEquals("User file", file.readText())
            assertFails { copy(download) }
            File(file.parentFile, ".starlit-owner").writeText(download.id)
            assertFails { copy(download) } // no durable size proof: never overwrite
            assertEquals("User file", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun sourceSelectionAndBatchMappingPreserveExactEpisodeIdsAndAiredOrder() {
        val meta = MetaDetails("anime:series", "series", "Fixture", videos = listOf(
            MetaVideo("anime:absolute-14", "Second", "2020-01-01", season = 2, episode = 2),
            MetaVideo("anime:absolute-13", "First", "2020-01-01", season = 2, episode = 1),
            MetaVideo("future", "Later", "2099-01-01", season = 3, episode = 1),
            MetaVideo("blocked", "Unavailable", available = false, season = 1, episode = 1)))
        val selected = batchDownloadEpisodes(meta, meta.videos.map(::downloadEpisodeKey).toSet(), "2026-10-19")
        assertEquals(listOf("anime:absolute-13", "anime:absolute-14"), selected.map { it.id })
        val direct = StreamItem(name = "1080p", url = "https://example.test/video.mp4", addonName = "Fixture", addonId = "fixture")
        assertTrue(matchesDownloadSelection(direct, DownloadSourceSelection(quality = "1080p", transport = "Direct files")))
        assertFalse(matchesDownloadSelection(direct, DownloadSourceSelection(quality = "720p")))
        assertFalse(matchesDownloadSelection(direct.copy(url = "https://example.test/master.m3u8?token=1"), DownloadSourceSelection()))
        assertFalse(matchesDownloadSelection(direct.copy(url = null, externalUrl = "https://example.test/page"), DownloadSourceSelection()))
    }

    @Test fun torrentsRequireAnExactMediaFileAndUseOnlyTheirOwnBackend() = runBlocking {
        val files = listOf(TorrServerFile(1, "Show/E01.mkv", 100), TorrServerFile(2, "Show/E02.mkv", 200), TorrServerFile(3, "readme.txt", 20))
        assertEquals(2, selectDownloadTorrentFile(files, 1, null).id)
        assertEquals(1, selectDownloadTorrentFile(files, null, "E01.mkv").id)
        assertFails { selectDownloadTorrentFile(files, null, null) }
        assertFails { selectDownloadTorrentFile(files, 2, null) }
        assertFails { selectDownloadTorrentFile(files, 100, null) }
        val calls = mutableListOf<String>()
        val backend = object : DesktopTorrentBackend {
            override suspend fun prepare(settings: P2pSettingsUiState) { calls += "prepare" }
            override suspend fun restart(settings: P2pSettingsUiState) { error("must not reset any engine") }
            override suspend fun stop() { calls += "stop" }
            override suspend fun dropTorrent(hash: String) { calls += "drop:$hash" }
            override suspend fun addTorrent(magnetLink: String): String { assertTrue(magnetLink.contains("btih:")); calls += "add"; return "own-hash" }
            override suspend fun getTorrentStats(hash: String) = TorrServerStats(0,0,0,0,0,0,300,files)
            override fun getStreamUrl(magnetLink: String, fileIdx: Int): String { calls += "file:$fileIdx"; return "http://127.0.0.1/own-stream" }
        }
        val root = Files.createTempDirectory("download-torrent").toFile()
        try {
            val download = item(root).copy(sourceUrl = "", torrentHash = "a".repeat(40), torrentFileIdx = 1)
            assertFails { downloadTorrentFile(download, backend, { P2pSettingsUiState(p2pEnabled = false) }) { _, _ -> error("P2P disabled") } }
            assertTrue(calls.isEmpty())
            val result = downloadTorrentFile(download, backend, { P2pSettingsUiState(p2pEnabled = true) }) { url, size ->
                assertEquals(200L, size)
                assertEquals("http://127.0.0.1/own-stream", url); File(root, "copied.mkv").also { it.writeBytes(media) }
            }
            assertContentEquals(media, result.readBytes())
            assertEquals(listOf("prepare", "add", "file:2", "drop:own-hash"), calls)
        } finally { root.deleteRecursively() }
    }
}
