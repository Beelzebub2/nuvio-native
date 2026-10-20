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

    @Test fun highestQualityRanksLabelledResolutionsAndKeepsSourceOrderForTies() {
        fun stream(name: String, title: String? = null) = StreamItem(name = name, title = title, url = "https://example.test/$name.mp4", addonName = "Fixture", addonId = "fixture")
        assertEquals(2160, downloadStreamQuality(stream("Torrentio\n4K HDR")))
        assertEquals(2160, downloadStreamQuality(stream("Show.S01E01.2160p.WEB")))
        assertEquals(1080, downloadStreamQuality(stream("Fixture", "Show S01E01 1080p x265")))
        assertEquals(720, downloadStreamQuality(stream("720p")))
        assertNull(downloadStreamQuality(stream("Fixture", "Episode 1 · 720 MB")))
        val ranked = rankByDownloadQuality(listOf(stream("first", "480p"), stream("second", "unknown"), stream("third", "1080p"), stream("fourth", "1080p"), stream("fifth", "4k")))
        assertEquals(listOf("fifth", "third", "fourth", "first", "second"), ranked.map { it.name })
        val uhd = stream("Fixture", "Show 2160p")
        assertTrue(matchesDownloadSelection(uhd, DownloadSourceSelection(quality = DownloadQuality.Highest)))
        assertTrue(matchesDownloadSelection(stream("Fixture", "Show 4K"), DownloadSourceSelection(quality = "2160p")))
        assertFalse(matchesDownloadSelection(uhd, DownloadSourceSelection(quality = "1080p")))
    }

    @Test fun longFolderPathsKeepTheDriveAndNearestFolders() {
        assertEquals("D:\\Media", compactFolderPath("D:\\Media"))
        assertEquals("C:\\…\\Roaming\\NuvioNative", compactFolderPath("C:\\Users\\ricar\\AppData\\Roaming\\NuvioNative"))
        assertEquals("/home/…/Videos/Anime", compactFolderPath("/home/someone/Media/Library/Videos/Anime"))
    }

    @Test fun torrentFilenameWinsOverAnIndexFromADifferentFileOrder() {
        val firstSeason = TorrServerFile(8, "Pack/Season 1/Show.S01E06.mkv", 100)
        val secondSeason = TorrServerFile(6, "Pack/Season 2/Show.S02E06.mkv", 200)
        val files = listOf(secondSeason, firstSeason)
        for (index in listOf(5, -1, Int.MAX_VALUE, null)) {
            assertEquals(firstSeason, selectDownloadTorrentFile(files, index, "Show.S01E06.mkv"))
            assertEquals(firstSeason, selectDownloadTorrentFile(files.reversed(), index, "Show.S01E06.mkv"))
        }
        // Specials may intentionally use a regular season's OVA filename from the provider.
        val ova = TorrServerFile(9, "Pack/Show.S01E11.OVA.mkv", 50)
        assertEquals(ova, selectDownloadTorrentFile(files + ova, 5, "Show.S01E11.OVA.mkv"))
    }

    @Test fun torrentPathsDisambiguateRepeatedBasenamesWithoutGuessingAnotherSeason() {
        val firstSeason = TorrServerFile(7, "Pack/Season 1/E01.mkv", 100)
        val secondSeason = TorrServerFile(1, "Pack/Season 2/E01.mkv", 200)
        val files = listOf(secondSeason, firstSeason)
        assertEquals(firstSeason, selectDownloadTorrentFile(files, 0, "pack\\season 1\\E01.MKV"))
        assertEquals(firstSeason, selectDownloadTorrentFile(files, 0, "Season 1/E01.mkv"))
        for (filename in listOf("E01.mkv", "missing.mkv", "Season 3/E01.mkv", "../Season 1/E01.mkv")) {
            assertFailsWith<IllegalStateException> { selectDownloadTorrentFile(files, 0, filename) }
        }
        assertFails { selectDownloadTorrentFile(listOf(firstSeason, firstSeason.copy(id = 8)), 0, firstSeason.path) }
        assertFails { selectDownloadTorrentFile(listOf(firstSeason, firstSeason.copy(id = 8, path = "Other/Season 1/E01.mkv")), 0, "Season 1/E01.mkv") }
        // An explicit missing filename must fail even in a single-video torrent.
        assertFails { selectDownloadTorrentFile(listOf(firstSeason), 6, "missing.mkv") }
    }

    @Test fun aMixedSeasonTorrentCopiesTheNamedFilesNativeIdAndVerifiesItsSize() = runBlocking {
        val files = parseTorrServerStats(kotlinx.serialization.json.Json.parseToJsonElement("""
            {"file_stats":[{"id":7,"path":"Pack/Season 1/E06.mp4","length":16},
                           {"id":1,"path":"Pack/Season 2/E06.mp4","length":20}]}
        """).let { it as kotlinx.serialization.json.JsonObject }).files
        val calls = mutableListOf<String>()
        val http = server { exchange ->
            assertEquals("index=7", exchange.requestURI.query)
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("ETag", "\"selected-file\"")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        val backend = object : DesktopTorrentBackend {
            override suspend fun isHealthy() = true
            override suspend fun prepare(settings: P2pSettingsUiState) { calls += "prepare" }
            override suspend fun restart(settings: P2pSettingsUiState) { error("must not reset any engine") }
            override suspend fun stop() { error("caller owns the backend") }
            override suspend fun dropTorrent(hash: String) { calls += "drop" }
            override suspend fun addTorrent(magnetLink: String) = "fixture"
            override suspend fun getTorrentStats(hash: String) = TorrServerStats(0,0,0,0,0,0,36,files)
            override fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
                calls += "file:$fileIdx"
                return "${http.url()}?index=$fileIdx"
            }
        }
        val root = Files.createTempDirectory("download-mixed-seasons").toFile()
        val io = DownloadIo()
        try {
            val download = item(root).copy(videoId = "show:1:6", episodeNumber = 6, sourceUrl = "",
                torrentHash = "a".repeat(40), torrentFileIdx = 0, torrentFilename = "Season 1/E06.mp4")
            val copied = downloadTorrentFile(download, backend, { P2pSettingsUiState(p2pEnabled = true) }) { url, size ->
                assertEquals(16L, size)
                copyDesktopDownload(download, url, io, { _, _ -> }, size)
            }
            assertContentEquals(media, copied.readBytes())
            assertEquals("16", File(copied.path + ".validator").readLines()[1])
            assertEquals(listOf("prepare", "file:7", "drop"), calls)
            calls.clear()
            assertFails {
                downloadTorrentFile(download.copy(torrentFilename = "missing.mp4"), backend, { P2pSettingsUiState(p2pEnabled = true) }) { _, _ ->
                    error("A missing filename must never start copying")
                }
            }
            assertEquals(listOf("prepare", "drop"), calls)
            // A server returning a different file's length must not produce a completed file.
            val mismatch = item(root, id = "mismatch")
            assertFails { copyDesktopDownload(mismatch, "${http.url()}?index=7", io, { _, _ -> }, 20L) }
            assertFalse(downloadPath(mismatch).exists())
        } finally { io.close(); http.stop(0); root.deleteRecursively() }
    }

    @Test fun torrentsRequireAnExactMediaFileAndUseOnlyTheirOwnBackend() = runBlocking {
        val files = listOf(TorrServerFile(1, "Show/E01.mkv", 100), TorrServerFile(2, "Show/E02.mkv", 200), TorrServerFile(3, "readme.txt", 20))
        assertEquals(2, selectDownloadTorrentFile(files, 1, null).id)
        assertEquals(1, selectDownloadTorrentFile(files, null, "E01.mkv").id)
        assertFails { selectDownloadTorrentFile(files, null, null) }
        assertFails { selectDownloadTorrentFile(files, 2, null) }
        assertFails { selectDownloadTorrentFile(files, 100, null) }
        assertFails { selectDownloadTorrentFile(files, -1, null) }
        assertFails { selectDownloadTorrentFile(files, Int.MAX_VALUE, null) }
        assertEquals(files.first(), selectDownloadTorrentFile(listOf(files.first()), null, null))
        val calls = mutableListOf<String>()
        val backend = object : DesktopTorrentBackend {
            override suspend fun isHealthy() = true
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
