package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeNoException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStorageManagementTest {
    @Test
    fun hardQuotaBlocksOversizedKnownDownloadsButAllowsExactlyTheRemainingBudget() {
        val root = File("unused")
        val owned = item(root).copy(id = "completed", downloadedBytes = 600L, totalBytes = 600L)
        val candidate = item(root).copy(id = "pending", status = DownloadStatus.Downloading,
            downloadedBytes = 0L, totalBytes = 500L)
        assertFalse(quotaAllowsDownloadStart(listOf(owned, candidate), emptySet(), "pending", 1_000L))
        val exactly = candidate.copy(totalBytes = 400L)
        assertTrue(quotaAllowsDownloadStart(listOf(owned, exactly), emptySet(), "pending", 1_000L))
        assertFalse(quotaAllowsDownloadStart(listOf(owned, exactly), emptySet(), "absent", 1_000L))
        assertFalse(quotaAllowsDownloadStart(listOf(owned, exactly), emptySet(), "pending", -1L))
    }

    @Test
    fun hardQuotaReservesKnownTotalsForConcurrentActiveTransfers() {
        val root = File("unused")
        val running = item(root).copy(id = "first", status = DownloadStatus.Downloading,
            downloadedBytes = 100L, totalBytes = 700L)
        val candidate = item(root).copy(id = "second", status = DownloadStatus.Downloading,
            downloadedBytes = 0L, totalBytes = 350L)
        assertEquals(700L, projectedDownloadQuotaBytes(listOf(running, candidate), setOf("first")))
        assertFalse(quotaAllowsDownloadStart(
            listOf(running, candidate), setOf("first"), "second", 1_000L,
        ))
        assertTrue(quotaRequiresPause(
            listOf(running, candidate), setOf("first", "second"), 1_000L,
        ))
        assertFalse(quotaRequiresPause(
            listOf(running, candidate), setOf("first"), 1_000L,
        ))
    }

    @Test
    fun unknownLengthStartsOnlyBelowLimitAndPausesWhenMetadataOrBytesExceedIt() {
        val root = File("unused")
        val existing = item(root).copy(id = "completed", downloadedBytes = 900L)
        val unknown = item(root).copy(id = "next", status = DownloadStatus.Downloading,
            downloadedBytes = 0L, totalBytes = null)
        assertTrue(quotaAllowsDownloadStart(listOf(existing, unknown), emptySet(), "next", 1_000L))
        assertTrue(quotaRequiresPause(
            listOf(existing, unknown.copy(downloadedBytes = 10L, totalBytes = 300L)),
            setOf("next"), 1_000L,
        ))
        assertTrue(quotaRequiresPause(
            listOf(existing, unknown.copy(downloadedBytes = 101L, totalBytes = null)),
            setOf("next"), 1_000L,
        ))
        assertFalse(quotaAllowsDownloadStart(listOf(existing, unknown), emptySet(), "next", 900L))
    }

    @Test
    fun quotaAccountingSaturatesOnMalformedOrEnormousSizeAndAllowsAfterCleanup() {
        val root = File("unused")
        val malformed = item(root).copy(id = "large", downloadedBytes = Long.MAX_VALUE - 100L,
            totalBytes = Long.MAX_VALUE)
        val another = item(root).copy(id = "next", status = DownloadStatus.Downloading,
            downloadedBytes = 200L, totalBytes = -20L)
        assertEquals(Long.MAX_VALUE,
            projectedDownloadQuotaBytes(listOf(malformed, another), setOf("large", "next")))
        assertFalse(quotaAllowsDownloadStart(listOf(malformed, another), emptySet(), "next", 10_000L))
        val cleaned = listOf(another.copy(downloadedBytes = 0L, totalBytes = 400L))
        assertTrue(quotaAllowsDownloadStart(cleaned, emptySet(), "next", 1_000L))
        assertEquals(400L, projectedDownloadQuotaBytes(cleaned, setOf("next")))
    }
    private fun progress(
        metaId: String,
        episode: Int,
        playedAt: Long,
        videoId: String = "shared-add-on-video-id",
    ) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = metaId,
        parentMetaType = "series",
        videoId = videoId,
        title = "Episode",
        seasonNumber = 1,
        episodeNumber = episode,
        lastPositionMs = 2_000L,
        durationMs = 4_000L,
        lastUpdatedEpochMs = playedAt,
    )
    private fun item(root: File, id: String = "owned123", status: DownloadStatus = DownloadStatus.Completed) =
        DownloadItem(
            id = id,
            contentType = "movie",
            parentMetaId = "meta",
            parentMetaType = "movie",
            videoId = "video",
            title = "Owned file",
            streamTitle = "1080p",
            providerName = "Test",
            sourceUrl = "https://example.invalid/video",
            destinationDirectory = root.absolutePath,
            fileName = "owned.mp4",
            status = status,
            downloadedBytes = 32L,
            totalBytes = 32L,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 200L,
        )

    private fun staged(root: File): DownloadItem {
        val record = item(root)
        val file = downloadPath(record)
        assertTrue(file.parentFile.mkdirs())
        file.writeBytes(ByteArray(32) { it.toByte() })
        File(file.parentFile, ".starlit-owner").writeText(record.id)
        val proof = DesktopDownloadProof(record.id, record.fileName,
            downloadSourceFingerprint(record, record.sourceUrl), "-", "fixture",
            file.length(), downloadSha256File(file))
        writeDesktopDownloadProof(file, proof)
        assertTrue(verifiedDesktopDownloadContent(file, proof))
        return record.copy(localFileUri = file.toURI().toString())
    }

    @Test
    fun cleanupRejectsAnythingUnownedOrUnverified() {
        val root = Files.createTempDirectory("starlit-storage-test").toFile()
        try {
            val completed = staged(root)
            val file = downloadPath(completed)
            assertEquals(32L, DownloadStorageManagement.eligibleCleanupBytes(completed))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(status = DownloadStatus.Paused)))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(phase = SAVED_DOWNLOAD_VERIFICATION_PHASE)))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(destinationDirectory = null)))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(localFileUri = File(root, "other.mp4").toURI().toString())))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(downloadedBytes = 7)))
            File(file.parentFile, ".starlit-owner").writeText("someone-else")
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
            File(file.parentFile, ".starlit-owner").writeText(completed.id)
            assertEquals(32L, DownloadStorageManagement.eligibleCleanupBytes(completed))
            assertTrue(downloadProofFile(file).delete())
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
            downloadProofFile(file).writeText("unverified record")
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupRejectsWrongProofOwnerTraversalAndChangedContentWithoutHashing() {
        val root = Files.createTempDirectory("starlit-cleanup-proof").toFile()
        try {
            val completed = staged(root)
            val file = downloadPath(completed)
            val proof = requireNotNull(readDesktopDownloadProof(file))
            writeDesktopDownloadProof(file, proof.copy(ownerId = "other-profile-file"))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
            writeDesktopDownloadProof(file, proof.copy(fileName = "other.mp4"))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
            writeDesktopDownloadProof(file, proof)
            assertTrue(verifiedDesktopDownloadContent(file, proof))
            assertFalse(removeVerifiedOwnedDownload(completed,
                com.nuvio.app.features.profiles.ProfileRepository.activeProfileId + 1))
            assertTrue(file.exists(), "A stale profile confirmation must preserve media")
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(fileName = "../owned.mp4")))
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed.copy(id = "..")))
            file.writeBytes(ByteArray(32) { 99 })
            file.setLastModified(System.currentTimeMillis() + 2_000L)
            assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed), "Changed media must need IO verification again")
            assertFalse(removeVerifiedOwnedDownload(completed,
                com.nuvio.app.features.profiles.ProfileRepository.activeProfileId),
                "Deletion must recheck a file changed since preview")
            assertTrue(file.exists(), "Eligibility must never delete media")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cleanupRejectsSymlinkMediaProofAndOwnershipMarker() {
        val root = Files.createTempDirectory("starlit-cleanup-link").toFile()
        val outside = Files.createTempFile("starlit-user-media", ".mp4").toFile()
        val links = mutableListOf<java.nio.file.Path>()
        try {
            outside.writeBytes(ByteArray(32) { 42 })
            val completed = staged(root)
            val file = downloadPath(completed)
            val proofFile = downloadProofFile(file)
            val marker = File(file.parentFile, ".starlit-owner")
            for (path in listOf(file.toPath(), proofFile.toPath(), marker.toPath())) {
                val original = Files.readAllBytes(path)
                Files.delete(path)
                try {
                    Files.createSymbolicLink(path, outside.toPath())
                    links.add(path)
                } catch (error: Exception) {
                    Files.write(path, original)
                    assumeNoException("Host cannot create test symlinks; Linux storage CI exercises this case", error)
                }
                assertNull(DownloadStorageManagement.eligibleCleanupBytes(completed))
                Files.delete(path)
                links.remove(path)
                Files.write(path, original)
            }
            assertEquals(32L, outside.length(), "Foreign media must remain untouched")
        } finally {
            links.forEach { Files.deleteIfExists(it) }
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun cleanupPreviewOnlyListsPlayableVerifiedAndOldestFirst() {
        val root = Files.createTempDirectory("starlit-storage-preview").toFile()
        try {
            val old = staged(root)
            val new = old.copy(id = "different", title = "Second file", createdAtEpochMs = 200L)
            val withoutProof = old.copy(id = "unverified", title = "No proof", localFileUri = null)
            val result = eligibleDownloadCleanup(
                listOf(new, old, old.copy(status = DownloadStatus.Downloading), withoutProof),
            ) { item ->
                if (item.id == old.id || item.id == new.id) 32L else null
            }
            assertEquals(listOf(old.id, new.id), result.map { it.id })
            assertEquals(listOf("Owned file", "Second file"), result.map { it.title })
            assertTrue(result.all { it.bytes > 0 })
            assertFalse(result.any { it.id == withoutProof.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun trackedUsageIsSaturatingAndNeverNegative() {
        val root = File("unused")
        assertEquals(0L, trackedDownloadBytes(emptyList()))
        assertEquals(0L, trackedDownloadBytes(listOf(item(root).copy(downloadedBytes = -100))))
        assertEquals(
            Long.MAX_VALUE,
            trackedDownloadBytes(listOf(item(root).copy(downloadedBytes = Long.MAX_VALUE), item(root))),
        )
    }

    @Test
    fun ageSuggestionsNeverIncludeFutureUnknownOrTooRecentFiles() {
        val day = 86_400_000L
        val now = 100L * day
        val entries = listOf(
            DownloadCleanupEntry("old", "Older copy", 200L, now - 90L * day),
            DownloadCleanupEntry("boundary", "30 days", 100L, now - 30L * day),
            DownloadCleanupEntry("recent", "Recent copy", 100L, now - 2L * day),
            DownloadCleanupEntry("future", "Clock-skewed copy", 100L, now + day),
            DownloadCleanupEntry("unknown", "Unknown creation date", 100L, 0L),
            DownloadCleanupEntry("empty", "Zero bytes", 0L, now - 60L * day),
        )
        assertEquals(setOf("old", "boundary"), suggestedOldDownloadCleanupIds(entries, now))
        assertEquals(emptySet(), suggestedOldDownloadCleanupIds(entries, 0L))
        assertEquals(emptySet(), suggestedOldDownloadCleanupIds(entries, now, ageDays = 0))
        assertEquals(emptySet(), suggestedOldDownloadCleanupIds(entries, now, ageDays = 3651))
    }

    @Test
    fun targetSuggestionsPreferOldestVerifiedCopiesWithoutGrantingDeletion() {
        val entries = listOf(
            DownloadCleanupEntry("newest", "Newer", 700L, 300L),
            DownloadCleanupEntry("oldest", "Older", 125L, 100L),
            DownloadCleanupEntry("middle", "Middle", 100L, 200L),
        )
        assertEquals(
            setOf("oldest", "middle"),
            suggestedTargetDownloadCleanupIds(entries, trackedBytes = 1_000L, targetBytes = 800L),
        )
        assertEquals(
            emptySet(),
            suggestedTargetDownloadCleanupIds(entries, trackedBytes = 1_000L, targetBytes = 1_000L),
        )
        assertEquals(
            emptySet(),
            suggestedTargetDownloadCleanupIds(entries, trackedBytes = 1_000L, targetBytes = null),
        )
        assertEquals(
            setOf("oldest"),
            suggestedTargetDownloadCleanupIds(
                listOf(DownloadCleanupEntry("oldest", "Huge verified file", Long.MAX_VALUE, 100L)),
                trackedBytes = Long.MAX_VALUE,
                targetBytes = 1L,
            ),
        )
    }

    @Test
    fun lastWatchedMappingUsesSeriesAndExactEpisodeAndRejectsFutureDates() {
        val root = File("unused")
        val owned = item(root).copy(id = "owned", contentType = "series", parentMetaType = "series",
            parentMetaId = "series-a", videoId = "shared-add-on-video-id", seasonNumber = 1, episodeNumber = 2)
        val unrelated = owned.copy(id = "other", parentMetaId = "series-b")
        val unwatched = owned.copy(id = "unwatched", episodeNumber = 3)
        val dates = lastWatchedEpochMsByDownloadedEpisodeId(
            listOf(owned, unrelated, unwatched),
            listOf(
                progress("series-a", 2, 2_000L),
                progress("series-a", 2, 3_000L),
                progress("series-b", 2, 1_000L),
                progress("series-a", 3, 20_000L), // Future timestamp is never accepted.
                progress("series-c", 2, 500L),
            ),
            nowEpochMs = 10_000L,
        )
        assertEquals(mapOf("owned" to 3_000L, "other" to 1_000L), dates)
    }

    @Test
    fun lruSuggestionsPrioritizeWatchedVerifiedCopiesAndNeverAuthorizeDeletion() {
        val eligible = listOf(
            DownloadCleanupEntry("newly-played", "Recent", 250L, 100L),
            DownloadCleanupEntry("unwatched", "Not watched", 800L, 1L),
            DownloadCleanupEntry("old-played", "Watched a while ago", 125L, 300L),
            DownloadCleanupEntry("mid-played", "Watched recently", 100L, 200L),
        )
        val watched = setOf("newly-played", "old-played", "mid-played")
        val timestamps = mapOf("old-played" to 10L, "mid-played" to 20L, "newly-played" to 30L,
            "unwatched" to 1L)
        val ids = suggestedLeastRecentlyWatchedCleanupIds(
            eligible, watched, timestamps, trackedBytes = 1_000L, targetBytes = 780L,
        )
        assertEquals(listOf("old-played", "mid-played"), ids.toList())
        assertFalse("unwatched" in ids)
        assertEquals(emptySet(), suggestedLeastRecentlyWatchedCleanupIds(
            eligible, watched, timestamps, trackedBytes = 1_000L, targetBytes = null,
        ))
        assertEquals(emptySet(), suggestedLeastRecentlyWatchedCleanupIds(
            eligible, watched, timestamps, trackedBytes = 1_000L, targetBytes = 1_000L,
        ))
        assertEquals(emptySet(), suggestedLeastRecentlyWatchedCleanupIds(
            eligible, emptySet(), timestamps, trackedBytes = 1_000L, targetBytes = 500L,
        ))
    }
}
