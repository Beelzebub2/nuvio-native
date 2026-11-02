package com.nuvio.app.features.downloads

import com.nuvio.engine.NuvioTorrentFile
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidTorrentFileSelectionTest {
    private fun file(index: Int, name: String, size: Long = 1_048_576L) =
        NuvioTorrentFile(index, 0L, size, name, false)

    private fun episode(season: Int = 1, number: Int = 2, hint: String? = null): DownloadItem =
        downloadItem().copy(
            contentType = "series", parentMetaId = "tt0123", videoId = "tt0123:$season:$number",
            seasonNumber = season, episodeNumber = number, episodeTitle = "Episode $number",
            torrentHash = "0123456789abcdef0123456789abcdef01234567",
            torrentFileIdx = 0, torrentFilename = hint,
        )

    @Test
    fun choosesCorrectEpisodeFromSeasonPackIgnoringStaleIndex() {
        val requested = episode(hint = "Show.S01E01.mkv")
        val chosen = selectAndroidTorrentFile(listOf(
            file(0, "Show.S01E01.mkv"),
            file(1, "Show.S01E02.mkv"),
            file(2, "Show.S01E03.mkv"),
        ), requested)
        assertEquals(1, chosen.index)
    }

    @Test
    fun rejectsAmbiguousSeasonPackAndWrongEpisode() {
        val noLabels = listOf(file(0, "one.mkv"), file(1, "two.mkv"))
        assertFailsWith<AndroidTorrentFileSelectionException> {
            selectAndroidTorrentFile(noLabels, episode(hint = "one.mkv"))
        }
        assertFailsWith<AndroidTorrentFileSelectionException> {
            selectAndroidTorrentFile(listOf(file(0, "Show.S01E03.mkv")), episode())
        }
    }

    @Test
    fun checksSeasonFolderAndEpisodeNumber() {
        val chosen = selectAndroidTorrentFile(listOf(
            file(0, "Show/Season 1/Episode 01.mp4"),
            file(1, "Show/Season 1/Episode 02.mp4"),
            file(2, "Show/Season 2/Episode 02.mp4"),
        ), episode())
        assertEquals(1, chosen.index)
    }

    @Test
    fun rejectsMultiEpisodeFilesAndAuxiliaryClips() {
        assertFailsWith<AndroidTorrentFileSelectionException> {
            selectAndroidTorrentFile(listOf(file(0, "Show.S01E02-E03.mkv")), episode())
        }
        assertFailsWith<AndroidTorrentFileSelectionException> {
            selectAndroidTorrentFile(listOf(file(0, "Show.Preview.mkv")), episode())
        }
    }

    @Test
    fun fullMovieTorrentWithOneVideoIsAccepted() {
        val chosen = selectAndroidTorrentFile(listOf(
            file(0, "readme.txt", 200),
            file(4, "Movie.2026.1080p.mkv", 8_000_000_000L),
        ), downloadItem())
        assertEquals(4, chosen.index)
    }

    @Test
    fun identityTiesPartialBytesToExactMetadataFile() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val original = file(1, "Show.S01E02.mkv")
        assertEquals(androidTorrentSourceIdentity(hash, original),
            androidTorrentSourceIdentity(hash.uppercase(), original))
        assertNotEquals(androidTorrentSourceIdentity(hash, original),
            androidTorrentSourceIdentity(hash, file(2, "Show.S01E03.mkv")))
    }

    @Test
    fun peerMetadataTimeoutIsRetryableInsteadOfCancellingTheWorker() = runBlocking {
        val failure = assertFailsWith<IOException> {
            withRetryableTorrentMetadataTimeout(15L) { delay(10_000L) }
        }
        assertTrue(failure.message.orEmpty().contains("metadata"))
        assertTrue(shouldRetryAndroidDownload(failure, 0))
    }
}
