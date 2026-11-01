package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.*
import com.nuvio.app.features.p2p.*
import com.nuvio.app.features.streams.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
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
    private fun partial(item: DownloadItem, body: ByteArray, validator: String = "\"fixture\"", complete: Boolean = false) {
        val file = downloadPath(item)
        file.parentFile.mkdirs()
        File(file.parentFile, ".starlit-owner").writeText(item.id)
        val part = File(file.path + ".part")
        part.writeBytes(body)
        writeDesktopDownloadProof(file, DesktopDownloadProof(item.id, item.fileName,
            downloadSourceFingerprint(item, item.sourceUrl),
            downloadEndpointFingerprint(URI.create(item.sourceUrl.ifBlank { "http://offline.invalid" })),
            validator, media.size.toLong(), if (complete) downloadSha256File(part) else null))
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

    @Test fun differentSourceWithTheSameEtagCannotAppendOldSourceBytes() = runBlocking {
        val root = Files.createTempDirectory("download-cross-source").toFile()
        val received = media.copyOf().apply { this[5] = 88 }
        val http = server { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Range"), "A different URL may reuse an ETag")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.sendResponseHeaders(200, received.size.toLong())
            exchange.responseBody.use { it.write(received) }
        }
        try {
            val source = item(root, url = "http://old.example/episode.mp4")
            partial(source, byteArrayOf(9, 9, 9, 9))
            val chosen = source.copy(sourceUrl = http.url())
            val result = copy(chosen)
            assertContentEquals(received, result.readBytes())
            assertEquals(downloadSourceFingerprint(chosen, chosen.sourceUrl),
                readDesktopDownloadProof(result)?.sourceFingerprint)
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun rangeIgnoredCrashAfterDurableResetCannotPublishMixedPayload() = runBlocking {
        val root = Files.createTempDirectory("download-crash-range-200").toFile()
        val requestedRanges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val http = server { exchange ->
            requestedRanges += exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        try {
            val download = item(root, url = http.url())
            partial(download, byteArrayOf(9, 9, 9, 9))
            val io = DownloadIo()
            try {
                assertFailsWith<IllegalStateException> {
                    copyDesktopDownload(download, download.sourceUrl, io, { _, _ -> },
                        onAfterPartReset = { error("simulated crash after fsynced truncate") })
                }
            } finally { io.close() }
            assertEquals(0L, File(downloadPath(download).path + ".part").length())
            assertContentEquals(media, copy(download).readBytes())
            assertEquals(listOf("bytes=4-", null), requestedRanges)
            val proof = assertNotNull(readDesktopDownloadProof(downloadPath(download)))
            assertEquals(downloadSha256File(downloadPath(download)), proof.completedSha256)
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun legacyPartialAlwaysRestartsButLegacyCompletedFileMigratesWithoutDeletingBytes() = runBlocking {
        val root = Files.createTempDirectory("download-legacy-proof").toFile()
        val http = server { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Range"), "Legacy proofs do not authorize resume")
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        try {
            val download = item(root, id = "legacy-part", url = http.url())
            val partialFile = downloadPath(download)
            partialFile.parentFile.mkdirs()
            File(partialFile.parentFile, ".starlit-owner").writeText(download.id)
            File(partialFile.path + ".part").writeBytes(byteArrayOf(8, 8, 8, 8))
            downloadProofFile(partialFile).writeText("\"fixture\"\n${media.size}")
            assertContentEquals(media, copy(download).readBytes())

            val completed = item(root, id = "legacy-done", url = "")
            val finalFile = downloadPath(completed)
            finalFile.parentFile.mkdirs()
            File(finalFile.parentFile, ".starlit-owner").writeText(completed.id)
            finalFile.writeBytes(media)
            downloadProofFile(finalFile).writeText("\"fixture\"\n${media.size}")
            assertEquals(finalFile, recoverFinalDownload(completed))
            val migrated = assertNotNull(readDesktopDownloadProof(finalFile))
            assertEquals(downloadSha256File(finalFile), migrated.completedSha256)
            assertEquals(finalFile.toURI().toString(),
                DownloadsPlatformDownloader.resolveLocalFileUri(finalFile.toURI().toString(), finalFile.name))
            finalFile.writeBytes(media.copyOf().apply { this[lastIndex] = 77 })
            Files.setLastModifiedTime(finalFile.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 3_000L))
            assertFailsWith<IllegalStateException> { recoverFinalDownload(completed) }
            assertNull(DownloadsPlatformDownloader.resolveLocalFileUri(finalFile.toURI().toString(), finalFile.name))
            assertTrue(finalFile.isFile, "Corrupt user data must be preserved for inspection")
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun cheapCompletedLookupRequiresFullVerificationAndRejectsChangedProofOrBytes() {
        val root = Files.createTempDirectory("download-cheap-lookup").toFile()
        try {
            val completed = item(root, id = "cached-proof").copy(status = DownloadStatus.Completed)
            val file = downloadPath(completed)
            file.parentFile.mkdirs()
            File(file.parentFile, ".starlit-owner").writeText(completed.id)
            file.writeBytes(media)
            val proof = DesktopDownloadProof(
                completed.id, completed.fileName,
                downloadSourceFingerprint(completed, completed.sourceUrl),
                downloadEndpointFingerprint(URI.create(completed.sourceUrl)),
                "\"fixture\"", media.size.toLong(), downloadSha256File(file),
            )
            writeDesktopDownloadProof(file, proof)
            val uri = file.toURI().toString()

            // Constructing a valid digest does not mark the file verified for this process.
            assertFalse(verifiedDesktopDownloadContent(file, proof, hashIfUncached = false))
            assertNull(DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))
            assertContentEquals(media, file.readBytes())

            assertEquals(file, recoverFinalDownload(completed))
            assertTrue(verifiedDesktopDownloadContent(file, proof, hashIfUncached = false))
            assertEquals(uri, DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))

            // Changing just the proof metadata invalidates the cached verification.
            val changedProof = proof.copy(validator = "\"updated-fixture\"")
            writeDesktopDownloadProof(file, changedProof)
            Files.setLastModifiedTime(downloadProofFile(file).toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 30_000L))
            assertFalse(verifiedDesktopDownloadContent(file, changedProof, hashIfUncached = false))
            assertNull(DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))
            assertContentEquals(media, file.readBytes())
            assertEquals(file, recoverFinalDownload(completed))
            assertEquals(uri, DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))

            // A same-length edit to the media must be rejected, without destroying the file.
            val tampered = media.copyOf().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
            file.writeBytes(tampered)
            Files.setLastModifiedTime(file.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000L))
            assertFalse(verifiedDesktopDownloadContent(file, changedProof, hashIfUncached = false))
            assertNull(DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))
            assertFailsWith<IllegalStateException> { recoverFinalDownload(completed) }
            assertNull(DownloadsPlatformDownloader.resolveLocalFileUri(uri, completed.fileName))
            assertContentEquals(tampered, file.readBytes())
            assertTrue(downloadProofFile(file).isFile, "Rejected proof must remain available for inspection")
        } finally { root.deleteRecursively() }
    }

    @Test fun aCancelledLargeFileDigestStopsBetweenReadChunksWithoutDeletingBytes() {
        val root = Files.createTempDirectory("download-cancel-digest").toFile()
        try {
            val file = File(root, "offline.mp4")
            file.writeBytes(ByteArray(1024 * 1024) { (it and 0xff).toByte() })
            var checks = 0
            assertFailsWith<CancellationException> {
                downloadSha256File(file) { ++checks <= 2 }
            }
            assertEquals(3, checks, "A cancelled scan should stop before reading the full file")
            assertEquals(1024 * 1024L, file.length())
        } finally { root.deleteRecursively() }
    }

    @Test fun completedDirectMediaWithoutOptionalIdentityIsImmediatelyPlayable() = runBlocking {
        val root = Files.createTempDirectory("download-unlisted-extension").toFile()
        val http = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        try {
            val download = item(root, id = "plain-mpg", url = http.url()).copy(fileName = "episode_plain-mpg.mpg")
            val result = CompletableDeferred<String>()
            DownloadsPlatformDownloader.start(
                DownloadPlatformRequest(download),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ -> result.complete(uri) },
                onFailure = { message -> result.completeExceptionally(IllegalStateException(message)) },
                onPaused = { result.completeExceptionally(CancellationException("Download was paused")) },
            )
            val uri = withTimeout(10_000L) { result.await() }
            assertContentEquals(media, File(URI(uri)).readBytes())
            assertFalse(File(downloadPath(download).parentFile, ".starlit-download.json").exists(),
                "An optional catalogue manifest should not be required for offline playback")
            assertEquals(uri, DownloadsPlatformDownloader.resolveLocalFileUri(uri, download.fileName))
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun aCompletedPartialWithDurableDigestFinishesWithoutNetworkAfterCrash() = runBlocking {
        val root = Files.createTempDirectory("download-complete-part").toFile()
        try {
            val download = item(root, url = "http://offline.invalid/episode.mp4")
            partial(download, media, complete = true)
            val file = copy(download)
            assertContentEquals(media, file.readBytes())
            assertFalse(File(file.path + ".part").exists())
            assertEquals(downloadSha256File(file), readDesktopDownloadProof(file)?.completedSha256)
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyOrTornOwnerMarkerIsRecoverableButForeignContentNeverIs() = runBlocking {
        val root = Files.createTempDirectory("download-owner-recovery").toFile()
        val http = server { exchange ->
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        try {
            for ((id, torn) in listOf("empty-owner" to false, "torn-owner" to true)) {
                val download = item(root, id, http.url())
                downloadPath(download).parentFile.mkdirs()
                if (torn) File(downloadPath(download).parentFile, ".starlit-owner").writeText("torn-")
                assertContentEquals(media, copy(download).readBytes())
                assertEquals(id, File(downloadPath(download).parentFile, ".starlit-owner").readText())
            }
            val foreign = item(root, id = "foreign-owner", url = http.url())
            val unrelated = File(downloadPath(foreign).parentFile.apply { mkdirs() }, "readme.txt")
            unrelated.writeText("Unrelated content")
            assertFailsWith<IllegalStateException> { copy(foreign) }
            assertEquals("Unrelated content", unrelated.readText())
        } finally { http.stop(0); root.deleteRecursively() }
    }

    @Test fun provenFinalFileRecoversOfflineBeforeAnySourceLookup() {
        val root = Files.createTempDirectory("download-final-recovery").toFile()
        val done = CountDownLatch(1)
        var failure: String? = null
        try {
            val download = item(root).copy(sourceUrl = "", sourceSelection = DownloadSourceSelection(providerId = "missing-provider"))
            partial(download, media, complete = true)
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
            val error = assertFails { withTimeout(15_000) { copy(download) } }
            // Gives up with its own error after the bounded retries instead of hitting the timeout.
            // The request count is not exact: the JDK client may add or lose one on a cut-off connection.
            assertIs<IOException>(error)
            assertTrue(error.message.orEmpty().startsWith("Transfer interrupted"), error.message)
            assertTrue(calls.get() in 2..4, "requests: ${calls.get()}")
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

    @Test fun highestQualityPrefersTheLeastCompressedFileAtTheBestResolution() {
        fun stream(name: String, description: String, videoSize: Long? = null) = StreamItem(
            name = name, description = description, url = "https://example.test/$name.mkv", addonName = "Fixture", addonId = "fixture",
            behaviorHints = StreamBehaviorHints(videoSize = videoSize))
        val ranked = rankByDownloadQuality(listOf(
            stream("small", "Show S01E01 1080p WEB x265\n👤 900 💾 300 MB ⚙️ Fixture"),
            stream("unknown", "Show S01E01 1080p WEB"),
            stream("large", "Show S01E01 1080p WEB-DL\n👤 40 💾 4.21 GB ⚙️ Fixture"),
            // The provider's exact byte count beats a size written in the label.
            stream("hinted", "Show S01E01 1080p BluRay 💾 900 MB", videoSize = 6_000_000_000L),
            stream("remux", "Show.S01E01.1080p.BDRemux 💾 3.8 GiB"),
            stream("uhd", "Show S01E01 2160p WEB 💾 1.9 GB"),
            // A season pack's total says nothing about this episode's file.
            stream("pack", "Show S01 1080p complete season\n📦 48 GB"),
        ))
        assertEquals(listOf("uhd", "remux", "hinted", "large", "small", "unknown", "pack"), ranked.map { it.name })
    }

    @Test fun highestQualityAsksEverySourceAndFallsBackWhenTheBestFileWontResolve() = runBlocking {
        fun stream(name: String, description: String) = StreamItem(
            name = name, description = description, url = "https://example.test/$name.mkv", addonName = "Fixture", addonId = "fixture")
        val asked = mutableListOf<Int>()
        val sources = listOf<suspend () -> List<StreamItem>>(
            { asked += 1; listOf(stream("compressed", "Show S01E01 1080p x265 💾 310 MB")) },
            { asked += 2; listOf(stream("dead", "Show S01E01 1080p WEB-DL 💾 5.6 GB"), stream("full", "Show S01E01 1080p WEB-DL 💾 4.2 GB")) },
        )
        val highest = selectDownloadSource(DownloadSourceSelection(quality = DownloadQuality.Highest), sources) { it.takeUnless { s -> s.name == "dead" } }
        assertEquals("full", highest.name)
        assertEquals(listOf(1, 2), asked)
        // "Any" still takes the first usable file so downloads start sooner.
        asked.clear()
        assertEquals("compressed", selectDownloadSource(DownloadSourceSelection(), sources).name)
        assertEquals(listOf(1), asked)
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
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
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
            override suspend fun stop() { calls += "stop" }
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = DownloadTorrentEngines(create = { backend }, scope = scope, idleMs = 60_000)
        try {
            val download = item(root).copy(videoId = "show:1:6", episodeNumber = 6, sourceUrl = "",
                torrentHash = "a".repeat(40), torrentFileIdx = 0, torrentFilename = "Season 1/E06.mp4")
            val copied = downloadTorrentFile(download, engines, { P2pSettingsUiState(p2pEnabled = true) }) { url, selected ->
                assertEquals(16L, selected.length)
                copyDesktopDownload(download, url, io, { _, _ -> }, selected.length, selected)
            }
            assertContentEquals(media, copied.readBytes())
            assertEquals(16L, readDesktopDownloadProof(copied)?.totalBytes)
            assertEquals(downloadSha256File(copied), readDesktopDownloadProof(copied)?.completedSha256)
            assertEquals(listOf("prepare", "file:7"), calls)
            calls.clear()
            assertFails {
                downloadTorrentFile(download.copy(torrentFilename = "missing.mp4"), engines, { P2pSettingsUiState(p2pEnabled = true) }) { _, _ ->
                    error("A missing filename must never start copying")
                }
            }
            assertEquals(emptyList(), calls, "The warm engine is reused, not restarted or dropped")
            // A server returning a different file's length must not produce a completed file.
            val mismatch = item(root, id = "mismatch")
            assertFails { copyDesktopDownload(mismatch, "${http.url()}?index=7", io, { _, _ -> }, 20L) }
            assertFalse(downloadPath(mismatch).exists())
        } finally { scope.cancel(); io.close(); http.stop(0); root.deleteRecursively() }
    }

    @Test fun episodesOfOneTorrentShareAWarmEngineThatRetiresWhenIdle() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val retired = java.util.Collections.synchronizedList(mutableListOf<String>())
        val created = AtomicInteger()
        fun backend(hash: String) = object : DesktopTorrentBackend {
            @Volatile var running = false
            override suspend fun isHealthy() = running
            override suspend fun prepare(settings: P2pSettingsUiState) { calls += "prepare:$hash"; running = true }
            override suspend fun restart(settings: P2pSettingsUiState) { error("must not reset a shared engine") }
            override suspend fun stop() { calls += "stop:$hash"; running = false }
            override suspend fun dropTorrent(hash: String) { calls += "drop:$hash" }
            override suspend fun addTorrent(magnetLink: String) = error("unused")
            override suspend fun getTorrentStats(hash: String) = error("unused")
            override fun getStreamUrl(magnetLink: String, fileIdx: Int) = error("unused")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engines = DownloadTorrentEngines(create = { created.incrementAndGet(); backend(it) }, scope = scope,
                idleMs = 200, onRetired = { retired += it })
            val settings = P2pSettingsUiState(p2pEnabled = true)
            val pack = "A".repeat(40)
            val key = pack.lowercase()
            val entered = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) { engines.use(pack, settings) { b -> entered.incrementAndGet(); release.await(); b } }
            val second = async(Dispatchers.Default) { engines.use(pack, settings) { b -> entered.incrementAndGet(); release.await(); b } }
            withTimeout(3_000) { while (entered.get() < 2) delay(10) }
            release.complete(Unit)
            assertSame(first.await(), second.await(), "Two episodes of one torrent share one engine")
            assertEquals(listOf("prepare:$key"), calls)
            // The next episode within the idle window reuses the running engine and its peers.
            engines.use(pack, settings) { }
            assertEquals(listOf("prepare:$key"), calls)
            // A different torrent gets its own engine.
            engines.use("b".repeat(40), settings) { }
            assertEquals(2, created.get())
            // Once idle, each engine drops its torrent, stops and clears its cache.
            withTimeout(3_000) { while (retired.size < 2) delay(20) }
            assertTrue(calls.containsAll(listOf("drop:$key", "stop:$key", "drop:${"b".repeat(40)}", "stop:${"b".repeat(40)}")))
            assertEquals(setOf(key, "b".repeat(40)), retired.toSet())
            // Using a retired engine starts its process again.
            engines.use(pack, settings) { }
            assertEquals(2, calls.count { it == "prepare:$key" })
            assertEquals(3, created.get())
        } finally { scope.cancel() }
    }

    @Test fun downloadSpeedIsAveragedOverSeveralSeconds() {
        val meter = DownloadRateMeter(windowMs = 8_000)
        assertEquals(0L, meter.sample(0, 0))
        // A burst then a stall: the shown speed stays near the real average instead of jumping.
        assertEquals(20_000_000L, meter.sample(500, 10_000_000))
        assertEquals(10_000_000L, meter.sample(1_000, 10_000_000))
        assertEquals(5_000_000L, meter.sample(2_000, 10_000_000))
        // Old samples leave the window, so a long stall eventually reads as slow.
        for (t in 3_000L..20_000L step 1_000) meter.sample(t, 10_000_000)
        assertEquals(0L, meter.sample(21_000, 10_000_000))
        // A restart from an earlier offset begins a fresh average.
        assertEquals(0L, meter.sample(22_000, 0))
        assertEquals(2_000_000L, meter.sample(23_000, 2_000_000))
    }

    @Test fun torrentsRequireAnExactMediaFileAndUseOnlyTheirOwnBackend() = runBlocking {
        val files = listOf(TorrServerFile(1, "Show/E01.mkv", 100), TorrServerFile(2, "Show/E02.mkv", 200), TorrServerFile(3, "readme.txt", 20))
        assertFails { selectDownloadTorrentFile(files, 1, null) }
        assertEquals(1, selectDownloadTorrentFile(files, null, "E01.mkv").id)
        assertFails { selectDownloadTorrentFile(files, null, null) }
        assertFails { selectDownloadTorrentFile(files, 2, null) }
        assertFails { selectDownloadTorrentFile(files, 100, null) }
        assertFails { selectDownloadTorrentFile(files, -1, null) }
        assertFails { selectDownloadTorrentFile(files, Int.MAX_VALUE, null) }
        assertEquals(files.first(), selectDownloadTorrentFile(listOf(files.first()), null, null))
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = DownloadTorrentEngines(create = { backend }, scope = scope, idleMs = 50)
        try {
            val download = item(root).copy(sourceUrl = "", torrentHash = "a".repeat(40), torrentFileIdx = 1,
                torrentFilename = "E02.mkv")
            assertFails { downloadTorrentFile(download, engines, { P2pSettingsUiState(p2pEnabled = false) }) { _, _ -> error("P2P disabled") } }
            assertTrue(calls.isEmpty())
            val result = downloadTorrentFile(download, engines, { P2pSettingsUiState(p2pEnabled = true) }) { url, selected ->
                assertEquals(200L, selected.length)
                assertEquals("http://127.0.0.1/own-stream", url); File(root, "copied.mkv").also { it.writeBytes(media) }
            }
            assertContentEquals(media, result.readBytes())
            withTimeout(3_000) { while ("stop" !in calls) delay(10) }
            assertEquals(listOf("prepare", "add", "file:2", "drop:${"a".repeat(40)}", "stop"), calls)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun shuffledTorrentFilesRequireAUniqueNameForMultiEpisodeDownloads() {
        val files = listOf(
            TorrServerFile(9, "Pack/Season 2/E02.mkv", 200),
            TorrServerFile(3, "Pack/Season 1/E02.mkv", 100),
            TorrServerFile(1, "Pack/readme.txt", 10),
        )
        assertFailsWith<IllegalStateException> { selectDownloadTorrentFile(files, 0, null) }
        assertFailsWith<IllegalStateException> { selectDownloadTorrentFile(files.reversed(), 1, null) }
        assertEquals(3, selectDownloadTorrentFile(files, 0, "Season 1/E02.mkv").id)
        assertFailsWith<IllegalStateException> { selectDownloadTorrentFile(files, 0, "E02.mkv") }
        assertEquals(9, selectDownloadTorrentFile(listOf(files.first(), files.last()), 0, null).id,
            "A single playable video is unambiguous regardless of original index")
    }

    @Test fun torrentMagnetBuilderSupportsV1V2Base32AndEncodesTrackerNames(): Unit = runBlocking {
        val tracker = "https://track.example/announce?q=two words&d=1"
        val magnetCalls = mutableListOf<String>()
        val files = listOf(TorrServerFile(7, "Movie.mp4", 16))
        val backend = object : DesktopTorrentBackend {
            override suspend fun isHealthy() = true
            override suspend fun prepare(settings: P2pSettingsUiState) {}
            override suspend fun restart(settings: P2pSettingsUiState) = error("Unexpected restart")
            override suspend fun stop() {}
            override suspend fun dropTorrent(hash: String) {}
            override suspend fun addTorrent(magnetLink: String): String { magnetCalls += magnetLink; return "fixture" }
            override suspend fun getTorrentStats(hash: String) = TorrServerStats(0, 0, 0, 0, 0, 0, 16, files)
            override fun getStreamUrl(magnetLink: String, fileIdx: Int) = "http://127.0.0.1/stream?index=$fileIdx"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = DownloadTorrentEngines(create = { backend }, scope = scope, idleMs = 60_000)
        val root = Files.createTempDirectory("torrent-magnets").toFile()
        try {
            val samples = listOf("a".repeat(40), "b".repeat(64), "A".repeat(32))
            for ((index, hash) in samples.withIndex()) {
                val download = item(root, id = "magnet-$index").copy(sourceUrl = "", torrentHash = hash,
                    torrentTrackers = listOf(tracker, "", tracker))
                val output = downloadTorrentFile(download, engines, { P2pSettingsUiState(p2pEnabled = true) }) { url, selected ->
                    assertEquals(7, selected.id)
                    assertTrue(url.contains("index=7"))
                    File(root, "output-$index").apply { writeBytes(media) }
                }
                assertContentEquals(media, output.readBytes())
                assertEquals(buildP2pMagnetUri(canonicalDownloadTorrentHash(hash), listOf(tracker)), magnetCalls.last())
            }
            assertTrue(magnetCalls[0].contains("xt=urn:btih:"))
            assertTrue(magnetCalls[1].contains("xt=urn:btmh:1220"))
            assertTrue(magnetCalls[2].contains("xt=urn:btih:${"0".repeat(40)}"))
            assertTrue(magnetCalls.all { it.contains("%20") && !it.contains("two+words") })
            assertFailsWith<IllegalArgumentException> { canonicalDownloadTorrentHash("invalid") }
            assertFailsWith<IllegalArgumentException> { canonicalDownloadTorrentHash("0".repeat(32)) }
            // JUnit 4 requires @Test methods to return void; assertFailsWith returns the exception.
            Unit
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun torrentV2BackendRejectionReportsUnsupportedSourceClearly(): Unit = runBlocking {
        val backend = object : DesktopTorrentBackend {
            override suspend fun isHealthy() = true
            override suspend fun prepare(settings: P2pSettingsUiState) {}
            override suspend fun restart(settings: P2pSettingsUiState) = error("Unexpected restart")
            override suspend fun stop() {}
            override suspend fun dropTorrent(hash: String) {}
            override suspend fun addTorrent(magnetLink: String): String =
                throw DesktopTorrentEngineUnavailable("Bad request", httpStatusCode = 400)
            override suspend fun getTorrentStats(hash: String) = error("Metadata must not be read")
            override fun getStreamUrl(magnetLink: String, fileIdx: Int) = error("Must not serve rejected torrent")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engines = DownloadTorrentEngines(create = { backend }, scope = scope, idleMs = 60_000)
            val download = item(Files.createTempDirectory("torrent-v2-rejection").toFile())
                .copy(sourceUrl = "", torrentHash = "b".repeat(64))
            val failure = assertFailsWith<DesktopTorrentEngineUnavailable> {
                downloadTorrentFile(download, engines, { P2pSettingsUiState(p2pEnabled = true) }) { _, _ -> error("Unexpected copy") }
            }
            assertTrue(failure.message.orEmpty().contains("Torrent v2"))
            assertEquals(400, failure.httpStatusCode)
            File(download.destinationDirectory!!).deleteRecursively()
            Unit
        } finally { scope.cancel() }
    }

    @Test fun transientTorrentMetadataFailuresRecoverWithoutRestartButMissingTorrentFailsPromptly(): Unit = runBlocking {
        val calls = AtomicInteger()
        val backend = object : DesktopTorrentBackend {
            override suspend fun isHealthy() = true
            override suspend fun prepare(settings: P2pSettingsUiState) = error("No restart or prepare is needed")
            override suspend fun restart(settings: P2pSettingsUiState) = error("No restart is needed")
            override suspend fun stop() {}
            override suspend fun dropTorrent(hash: String) {}
            override suspend fun addTorrent(magnetLink: String) = "fixture"
            override suspend fun getTorrentStats(hash: String): TorrServerStats {
                val count = calls.incrementAndGet()
                if (count == 1) throw DesktopTorrentEngineUnavailable("Temporary outage", httpStatusCode = 503)
                val files = if (count == 2) emptyList() else listOf(TorrServerFile(4, "Movie.mp4", 16))
                return TorrServerStats(0, 0, 0, 0, 0, 0, 16, files)
            }
            override fun getStreamUrl(magnetLink: String, fileIdx: Int) = error("Unused")
        }
        val files = awaitDownloadTorrentMetadata(backend, "fixture", timeoutMs = 1_000, pollMs = 5)
        assertEquals(3, calls.get())
        assertEquals(listOf(4), files.map { it.id })

        val missing = object : DesktopTorrentBackend by backend {
            override suspend fun getTorrentStats(hash: String): TorrServerStats =
                throw DesktopTorrentEngineUnavailable("Missing", httpStatusCode = 404)
        }
        val failure = assertFailsWith<DesktopTorrentEngineUnavailable> {
            awaitDownloadTorrentMetadata(missing, "missing", timeoutMs = 100, pollMs = 5)
        }
        assertEquals(404, failure.httpStatusCode)
        val neverReady = object : DesktopTorrentBackend by backend {
            override suspend fun getTorrentStats(hash: String) = TorrServerStats(0, 0, 0, 0, 0, 0, 16, emptyList())
        }
        assertFailsWith<TimeoutCancellationException> {
            awaitDownloadTorrentMetadata(neverReady, "offline", timeoutMs = 50, pollMs = 5)
        }
        Unit
    }
}
