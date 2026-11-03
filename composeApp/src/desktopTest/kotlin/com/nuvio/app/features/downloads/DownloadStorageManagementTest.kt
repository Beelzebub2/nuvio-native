package com.nuvio.app.features.downloads

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStorageManagementTest {
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
        downloadProofFile(file).writeText("validation record")
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
        } finally {
            root.deleteRecursively()
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
}
