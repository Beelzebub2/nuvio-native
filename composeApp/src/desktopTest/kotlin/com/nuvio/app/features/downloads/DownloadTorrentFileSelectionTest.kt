package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.DesktopTorrentBackend
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.p2p.TorrServerFile
import com.nuvio.app.features.p2p.TorrServerStats
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.*

class DownloadTorrentFileSelectionTest {
    @Test fun embeddedStreamsWithSharedSeriesIdsResolveOnlyTheRequestedEpisode() {
        val first = StreamItem(infoHash = "a".repeat(40), addonId = "fixture", addonName = "Fixture")
        val second = StreamItem(infoHash = "b".repeat(40), addonId = "fixture", addonName = "Fixture")
        val meta = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show", "First", season = 3, episode = 1, streams = listOf(first)),
            MetaVideo("show", "Second", season = 3, episode = 2, streams = listOf(second)),
        ))
        val item = DownloadItem(id = "task", contentType = "series", parentMetaId = "show",
            parentMetaType = "series", videoId = "show", title = "Show", seasonNumber = 3,
            episodeNumber = 2, streamTitle = "Automatic", providerName = "", sourceUrl = "",
            fileName = "episode.mkv", status = DownloadStatus.Downloading,
            createdAtEpochMs = 0, updatedAtEpochMs = 0)
        assertEquals(listOf(second), embeddedDownloadStreams(meta, item))
        assertEquals(emptyList(), embeddedDownloadStreams(meta, item.copy(seasonNumber = null, episodeNumber = null)))
    }

    @Test fun staleHintsAndPluginTorrentsCanResolveUniqueEpisodesFromThePack() {
        val files = listOf(
            TorrServerFile(93, "Pack/Season 3/Show.S03E01.mkv", 50),
            TorrServerFile(4, "Pack/Season 3/Show.S03E02.mkv", 60),
            TorrServerFile(7, "Pack/Season 3/Show.S03E03.mkv", 70),
        )
        assertEquals(4, selectDownloadTorrentFile(files, 0, "Another.Release.mkv", 3, 2, requireStrongEpisodeEvidence = true).id)
        assertEquals(4, selectDownloadTorrentFile(files.reversed(), null, null, 3, 2, requireStrongEpisodeEvidence = true).id)
        assertEquals(4, selectDownloadTorrentFile(files, 99, "Show.S03E01.mkv", 3, 2, requireStrongEpisodeEvidence = true).id,
            "Even an exact but wrong hint must not override the requested episode")
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(files, 0, "Show.S03E01.mkv", 3, 4, requireStrongEpisodeEvidence = true)
        }
    }

    @Test fun duplicateBasenamesAreDisambiguatedOnlyByExplicitEpisodeEvidence() {
        val first = TorrServerFile(77, "Pack/Season 1/E02.mkv", 100)
        val third = TorrServerFile(9, "Pack/Season 3/E02.mkv", 200)
        val files = listOf(first, third)
        assertEquals(third, selectDownloadTorrentFile(files, 0, "E02.mkv", 3, 2))
        assertEquals(third, selectDownloadTorrentFile(files.reversed(), 0, null, 3, 2, requireStrongEpisodeEvidence = true))
        assertFailsWith<DownloadTorrentFileSelectionException> { selectDownloadTorrentFile(files, 0, "E02.mkv") }
        assertFailsWith<DownloadTorrentFileSelectionException> { selectDownloadTorrentFile(files, 0, "Season 1/E02.mkv", 3, 7) }
    }

    @Test fun staleIntroHintNeverBeatsTheActualEpisodeOrDownloadsAnAuxiliaryClip() {
        val intro = TorrServerFile(1, "Pack/intro.mp4", 5_000)
        val episode = TorrServerFile(7, "Pack/Show.S03E02.mkv", 600_000)
        assertEquals(episode, selectDownloadTorrentFile(listOf(intro, episode), 0, "intro.mp4", 3, 2, requireStrongEpisodeEvidence = true))
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(listOf(intro, TorrServerFile(8, "Pack/Show.mkv", 400_000)),
                0, "intro.mp4", 3, 2, requireStrongEpisodeEvidence = true)
        }
    }

    @Test fun singleFileContradictionsAndMultipleVersionsDoNotGuess() {
        val wrong = TorrServerFile(12, "Show.S03E01.mkv", 100)
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(listOf(wrong), 0, null, 3, 2)
        }
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(listOf(wrong), 0, wrong.path, 3, 2)
        }
        val versions = listOf(
            TorrServerFile(2, "S03E02.720p.mkv", 100),
            TorrServerFile(4, "S03E02.1080p.mkv", 300),
        )
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(versions, 0, "wrong.mkv", 3, 2)
        }
        assertEquals(4, selectDownloadTorrentFile(versions, 0, "S03E02.1080p.mkv", 3, 2).id)
        assertFalse(matchesDownloadEpisode("Pack/Season 3/Show.S02E02.mkv", 3, 2)!!)
        assertFalse(matchesDownloadEpisode("Pack/Season 3/E03.mkv", 3, 2)!!)
        assertFalse(matchesDownloadEpisode("Pack/Season 3/E020.mkv", 3, 2)!!)
        assertFalse(matchesDownloadEpisode("Pack/Show.S03E02-E03.mkv", 3, 2)!!)
        assertFalse(matchesDownloadEpisode("Pack/Show.S03E02E03.mkv", 3, 2)!!)
        val seasonUnknown = listOf(TorrServerFile(1, "Pack/E02.mkv", 100), TorrServerFile(2, "Pack/E03.mkv", 100))
        assertNull(matchesDownloadEpisode("Pack/E02.mkv", 3, 2))
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(seasonUnknown, 0, "old-pack.mkv", 3, 2)
        }
        assertEquals(1, selectDownloadTorrentFile(seasonUnknown, null, "E02.mkv", 3, 2).id)
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(seasonUnknown, null, "E02.mkv", 3, 2, requireStrongEpisodeEvidence = true)
        }
        for (path in listOf("Pack/E02.mkv", "Pack/Show.mkv")) {
            assertFailsWith<DownloadTorrentFileSelectionException> {
                selectDownloadTorrentFile(listOf(TorrServerFile(1, path, 100)), null, null,
                    3, 2, requireStrongEpisodeEvidence = true)
            }
        }
    }

    @Test fun animeAbsoluteNumberIsUsedOnlyWhenCatalogueTitleExplicitlyProvidesOne() {
        val files = listOf(
            TorrServerFile(8, "KONOSUBA Season 3/[Group] KONOSUBA - 24 [1080p].mkv", 100),
            TorrServerFile(19, "KONOSUBA Season 3/[Group] KONOSUBA - 25 [1080p].mkv", 120),
        )
        val absolute = downloadAbsoluteEpisode("25. A Smile for This Dour Girl!")
        assertEquals(25, absolute)
        assertEquals(19, selectDownloadTorrentFile(files, 0, "Season 3 pack", 3, 2, absolute, requireStrongEpisodeEvidence = true).id)
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(files, null, "Season 3 pack", 3, 2)
        }
        assertNull(downloadAbsoluteEpisode("A Smile for This Dour Girl!"))
    }

    @Test fun alternateProviderMetadataAndCanonicalPathAreAcceptedWithoutUnsafeIndices() {
        val files = listOf(TorrServerFile(42, "Actual.Root/Episode.02.MKV", 70))
        assertEquals(42, selectDownloadTorrentFile(files, Int.MAX_VALUE, "old-root/episode-02.mkv",
            alternateFilenames = listOf("Episode.02.MKV")).id)
        assertFailsWith<DownloadTorrentFileSelectionException> {
            selectDownloadTorrentFile(files, 0, "../Episode.02.MKV", alternateFilenames = listOf("Episode.02.MKV"))
        }
        assertEquals(files.single(), selectDownloadTorrentFile(files, null, null))
        assertFailsWith<DownloadTorrentFileSelectionException> { selectDownloadTorrentFile(files, null, "missing.mkv") }
    }

    @Test fun incompleteFileMetadataIsPolledUntilPathsAndSizesArrive() = runBlocking {
        var polls = 0
        val backend = object : DesktopTorrentBackend {
            override suspend fun prepare(settings: P2pSettingsUiState) {}
            override suspend fun restart(settings: P2pSettingsUiState) {}
            override suspend fun dropTorrent(hash: String) {}
            override suspend fun stop() {}
            override suspend fun addTorrent(magnetLink: String) = "fixture"
            override suspend fun isHealthy() = true
            override fun getStreamUrl(magnetLink: String, fileIdx: Int) = "http://127.0.0.1/"
            override suspend fun getTorrentStats(hash: String): TorrServerStats {
                val files = when (polls++) {
                    0 -> listOf(TorrServerFile(1, "", 0))
                    1 -> listOf(TorrServerFile(1, "Pack/S03E01.mkv", 1024), TorrServerFile(2, "Pack/S03E02.mkv", 0))
                    else -> listOf(TorrServerFile(1, "Pack/S03E01.mkv", 1024), TorrServerFile(2, "Pack/S03E02.mkv", 1024),
                        TorrServerFile(3, "", 0), TorrServerFile(4, "Pack/empty-preview.mp4", 0))
                }
                return TorrServerStats(0, 0, 0, 0, 0, 0, 1024, files)
            }
        }
        val requested = DownloadItem(id = "test", contentType = "series", parentMetaId = "show",
            parentMetaType = "series", videoId = "show:3:2", title = "Show", seasonNumber = 3,
            episodeNumber = 2, streamTitle = "Test", providerName = "Test", sourceUrl = "",
            fileName = "S03E02.mkv", status = DownloadStatus.Downloading,
            createdAtEpochMs = 0, updatedAtEpochMs = 0)
        val complete = awaitDownloadTorrentMetadata(backend, "fixture", timeoutMs = 1000, pollMs = 1, item = requested)
        assertEquals(1024L, complete.first { it.id == 2 }.length)
        assertEquals(4, complete.size, "Empty zero-byte extras should not delay the requested episode")
        assertEquals(3, polls)
    }

    @Test fun partialTorrentResumesWithoutHttpValidatorWhenInfohashAndFileAreVerified() = runBlocking {
        val media = byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109, 1, 2, 3, 4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        server.executor = executor
        var rangeHeader: String? = null
        var ifRangeHeader: String? = null
        server.createContext("/") { exchange ->
            rangeHeader = exchange.requestHeaders.getFirst("Range")
            ifRangeHeader = exchange.requestHeaders.getFirst("If-Range")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Range", "bytes 4-15/16")
            exchange.sendResponseHeaders(206, 12)
            exchange.responseBody.use { it.write(media, 4, 12) }
        }
        server.start()
        val root = Files.createTempDirectory("torrent-resume-no-validator").toFile()
        val io = DownloadIo()
        try {
            val url = "http://127.0.0.1:${server.address.port}/episode.mp4"
            val selected = TorrServerFile(17, "Pack/Show.S03E02.mp4", media.size.toLong())
            val item = DownloadItem(id = "torrenttask", contentType = "series", parentMetaId = "show",
                parentMetaType = "series", videoId = "show:3:2", title = "Show", seasonNumber = 3,
                episodeNumber = 2, streamTitle = "1080p", providerName = "Test", sourceUrl = "",
                torrentHash = "a".repeat(40), torrentFilename = selected.path, fileName = "episode.mp4",
                destinationDirectory = root.absolutePath, status = DownloadStatus.Downloading,
                createdAtEpochMs = 0, updatedAtEpochMs = 0)
            val output = downloadPath(item)
            assertTrue(output.parentFile.mkdir())
            File(output.parentFile, ".starlit-owner").writeText(item.id)
            File(output.path + ".part").writeBytes(media.copyOfRange(0, 4))
            writeDesktopDownloadProof(output, DesktopDownloadProof(item.id, item.fileName,
                downloadSourceFingerprint(item, url, selected), "-", "", media.size.toLong()))
            val completed = copyDesktopDownload(item, url, io, { _, _ -> }, media.size.toLong(), selected)
            assertEquals("bytes=4-", rangeHeader)
            assertNull(ifRangeHeader, "The immutable torrent identity allows a validated range without ETag")
            assertContentEquals(media, completed.readBytes())
            assertEquals(downloadSha256File(completed), readDesktopDownloadProof(completed)?.completedSha256)
        } finally {
            io.close()
            server.stop(0)
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun automaticSelectionSkipsBadTorrentAndKeepsProviderPreferences() = runBlocking {
        val bad = StreamItem(name = "1080p bad", infoHash = "a".repeat(40),
            addonName = "Provider", addonId = "provider", behaviorHints = StreamBehaviorHints(filename = "wrong.mkv"))
        val good = StreamItem(name = "1080p good", infoHash = "b".repeat(40),
            addonName = "Provider", addonId = "provider", behaviorHints = StreamBehaviorHints(filename = "S03E02.mkv"))
        val attempted = mutableListOf<String>()
        val result = downloadUsingAlternativeSources(
            choose = { excluded -> listOf(bad, good).first { downloadSourceIdentity(it) !in excluded } },
            download = { stream ->
                attempted += stream.name.orEmpty()
                if (stream === bad) throw DownloadTorrentFileSelectionException("Unmatched file")
                stream.name.orEmpty()
            },
        )
        assertEquals("1080p good", result)
        assertEquals(listOf("1080p bad", "1080p good"), attempted)
        val sameReleaseDifferentLabel = StreamItem(name = "Some other label", infoHash = "a".repeat(40),
            addonName = "Provider", addonId = "provider", behaviorHints = StreamBehaviorHints(filename = "wrong.mkv"))
        assertEquals(downloadSourceIdentity(bad), downloadSourceIdentity(sameReleaseDifferentLabel),
            "Changing the add-on label must not exhaust the fallback budget on identical torrents")

        var attempts = 0
        assertFailsWith<IOException> {
            downloadUsingAlternativeSources(choose = { bad }, download = { attempts++; throw IOException("network issue") })
        }
        assertEquals(1, attempts, "Network failures after source validation must not switch media")
        assertFailsWith<CancellationException> {
            downloadUsingAlternativeSources(choose = { throw CancellationException("paused") }, download = { "never" })
        }
        Unit
    }
}
