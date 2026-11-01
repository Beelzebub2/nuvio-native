package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadRecoveryTest {
    private val media = byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109, 1, 2, 3, 4)

    private fun owned(root: File, id: String = "old-task", episode: Int = 2): DownloadItem {
        val item = DownloadItem(
            id = id, contentType = "series", parentMetaId = "series:alpha",
            parentMetaType = "series", videoId = "series:alpha:1:$episode",
            title = "Example Series", seasonNumber = 1, episodeNumber = episode,
            episodeTitle = "Example episode", streamTitle = "1080p", providerName = "Fixture",
            sourceUrl = "https://example.invalid/private?token=NEVER_STORE",
            sourceHeaders = mapOf("Authorization" to "Bearer NEVER_STORE"),
            fileName = "Example Series S01E0${episode}_$id.mp4",
            destinationDirectory = root.absolutePath, status = DownloadStatus.Completed,
            createdAtEpochMs = 1234, updatedAtEpochMs = 1234,
        )
        val file = downloadPath(item)
        file.parentFile.mkdirs()
        File(file.parentFile, ".starlit-owner").writeText(id)
        file.writeBytes(media)
        writeDesktopDownloadProof(file, DesktopDownloadProof(
            ownerId = id, fileName = item.fileName, sourceFingerprint = downloadSha256Text("fixture"),
            endpointFingerprint = "-", validator = "", totalBytes = media.size.toLong(),
            completedSha256 = downloadSha256File(file),
        ))
        return item.copy(localFileUri = file.toURI().toString(),
            downloadedBytes = media.size.toLong(), totalBytes = media.size.toLong())
    }

    @Test fun explicitFolderScanRequiresVerifiedIdentityAndNeverStoresDownloadCredentials() {
        val root = Files.createTempDirectory("starlit-recover-identity").toFile()
        try {
            val item = owned(root)
            assertTrue(discoverDesktopDownloads(root.absolutePath).isEmpty(),
                "A matching title and proof do not establish catalogue identity without saved metadata")
            assertTrue(recordDesktopDownloadIdentity(item))
            val manifest = File(downloadPath(item).parentFile, ".starlit-download.json").readText()
            assertFalse(manifest.contains("NEVER_STORE"))
            assertFalse(manifest.contains("Authorization"))
            assertFalse(manifest.contains("sourceUrl"))
            val discovered = discoverDesktopDownloads(root.absolutePath)
            assertEquals(1, discovered.size)
            assertEquals(item.logicalContentKey, discovered.single().logicalContentKey)
            assertEquals(item.id, discovered.single().id)
            assertEquals("", discovered.single().sourceUrl)
            assertEquals(DownloadStatus.Completed, discovered.single().status)
            assertEquals(media.size.toLong(), discovered.single().totalBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun savedRecordCanVerifyHistoricalFileWithoutManifestButNeverGuessesAnotherTask() {
        val root = Files.createTempDirectory("starlit-recover-known").toFile()
        try {
            val item = owned(root)
            val failed = item.copy(status = DownloadStatus.Failed, localFileUri = null)
            assertNull(recoverKnownDesktopDownload(failed.copy(id = "different-task"), root.absolutePath))
            val restored = assertNotNull(recoverKnownDesktopDownload(failed, root.absolutePath))
            assertEquals(DownloadStatus.Completed, restored.status)
            assertEquals(item.localFileUri, restored.localFileUri)
            assertEquals(item.logicalContentKey, discoverDesktopDownloads(root.absolutePath).single().logicalContentKey)
            assertNull(recoverKnownDesktopDownload(failed.copy(parentMetaId = "other-series"), root.absolutePath),
                "An existing identity sidecar must reject conflicting catalogue IDs")
            assertNull(recoverKnownDesktopDownload(failed.copy(episodeNumber = 3), root.absolutePath))
        } finally { root.deleteRecursively() }
    }

    @Test fun changedBytesMissingProofAndUnownedTaskNeverAppearAsCompleted() {
        val root = Files.createTempDirectory("starlit-recover-reject").toFile()
        try {
            val original = owned(root)
            assertTrue(recordDesktopDownloadIdentity(original))
            val file = downloadPath(original)
            file.writeBytes(media.copyOf().apply { this[lastIndex] = 9 })
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 5_000L))
            assertTrue(discoverDesktopDownloads(root.absolutePath).isEmpty())
            assertNull(recoverKnownDesktopDownload(original, root.absolutePath))
            file.writeBytes(media)
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 6_000L))
            assertEquals(1, discoverDesktopDownloads(root.absolutePath).size)
            downloadProofFile(file).delete()
            assertTrue(discoverDesktopDownloads(root.absolutePath).isEmpty())
            assertNull(recoverKnownDesktopDownload(original, root.absolutePath))
            val orphan = File(root, "unknown").apply { mkdirs() }
            File(orphan, "Example Series S01E02.mp4").writeBytes(media)
            assertTrue(discoverDesktopDownloads(root.absolutePath).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun selectingFolderRecoversExactlyOnceAndAnonymousImportMergesWithoutDuplicate() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "This test must use isolated app storage")
        val root = Files.createTempDirectory("starlit-recover-folder").toFile()
        val previousFolder = DownloadDesktopFeatures.directory()
        try {
            val episode = owned(root)
            assertTrue(recordDesktopDownloadIdentity(episode))
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty(),
                "Loading another account must not silently scan a shared folder")
            DownloadDesktopFeatures.setDirectory(root)
            assertEquals(1, DownloadsRepository.recoverFromSelectedFolder())
            assertEquals(0, DownloadsRepository.recoverFromSelectedFolder())
            assertEquals(1, DownloadsRepository.uiState.value.items.size)
            assertEquals(episode.logicalContentKey, DownloadsRepository.uiState.value.items.single().logicalContentKey)
            val save = DownloadsCodec.encodeItems(listOf(episode))
            DownloadsRepository.mergeAnonymousDownloadRecords(save, root.absolutePath)
            DownloadsRepository.mergeAnonymousDownloadRecords(save, root.absolutePath)
            assertEquals(1, DownloadsRepository.uiState.value.items.size)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertEquals(1, DownloadsRepository.uiState.value.items.size)
            assertEquals(DownloadStatus.Completed, DownloadsRepository.uiState.value.items.single().status)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            previousFolder?.let { DownloadDesktopFeatures.setDirectory(File(it)) }
            root.deleteRecursively()
        }
    }

    @Test fun importingAnonymousListPreservesExistingAccountRecordsAndPausesUnfinishedTasks() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "This test must use isolated app storage")
        val root = Files.createTempDirectory("starlit-import-merging").toFile()
        val previousFolder = DownloadDesktopFeatures.directory()
        try {
            val completed = owned(root, "already-done", 1)
            val account = owned(root, "account-task", 2)
            // A genuinely unfinished transfer has no completed file or content proof.
            // Creating it through owned() would create a verified finished episode,
            // which import correctly recovers as Completed instead of pausing.
            val pending = completed.copy(
                id = "unfinished-task", videoId = "series:alpha:1:3", episodeNumber = 3,
                fileName = "Example Series S01E03_unfinished-task.mp4",
                status = DownloadStatus.Downloading, localFileUri = null,
                downloadedBytes = 0L, totalBytes = null,
            )
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(account)))
            DownloadsRepository.ensureLoaded()
            val guest = DownloadsCodec.encodeItems(listOf(completed, account.copy(id = "guest-duplicate"), pending))
            DownloadsRepository.mergeAnonymousDownloadRecords(guest, root.absolutePath)
            val items = DownloadsRepository.uiState.value.items
            assertEquals(3, items.size)
            assertEquals(account.id, items.single { it.episodeNumber == 2 }.id)
            assertEquals(DownloadStatus.Completed, items.single { it.episodeNumber == 1 }.status)
            assertEquals(DownloadStatus.Paused, items.single { it.episodeNumber == 3 }.status)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            previousFolder?.let { DownloadDesktopFeatures.setDirectory(File(it)) }
            root.deleteRecursively()
        }
    }

    @Test fun explicitAccountFolderIsKeptWhileImportedGuestFileUsesItsOriginalDirectory() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "This test must use isolated app storage")
        val guestRoot = Files.createTempDirectory("starlit-guest-media").toFile()
        val accountRoot = Files.createTempDirectory("starlit-account-media").toFile()
        val previousFolder = DownloadDesktopFeatures.directory()
        try {
            val completedGuest = owned(guestRoot, "guest-download", 4).copy(localFileUri = null)
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            DownloadDesktopFeatures.setDirectory(accountRoot)
            DownloadsRepository.mergeAnonymousDownloadRecords(
                DownloadsCodec.encodeItems(listOf(completedGuest)), guestRoot.absolutePath,
            )
            assertEquals(accountRoot.canonicalPath, DownloadDesktopFeatures.directory(),
                "Import must preserve the account's already selected default folder")
            val adopted = DownloadsRepository.uiState.value.items.single()
            assertEquals(DownloadStatus.Completed, adopted.status)
            assertEquals(guestRoot.canonicalPath, adopted.destinationDirectory)
            assertEquals(downloadPath(completedGuest).toURI().toString(), adopted.localFileUri)
            assertTrue(downloadPath(completedGuest).isFile)
            assertTrue(accountRoot.listFiles().isNullOrEmpty(), "Recovery should never move or copy media")
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            previousFolder?.let { DownloadDesktopFeatures.setDirectory(File(it)) }
            guestRoot.deleteRecursively()
            accountRoot.deleteRecursively()
        }
    }

    @Test fun absentNewProfileDownloadListRemainsAbsentUntilFirstActualMutation() {
        assertNotNull(System.getProperty("nuvio.dataDir"), "This test must use isolated app storage")
        val store = DesktopStorage.store("nuvio_downloads")
        val key = ProfileScopedKey.of("downloads")
        val original = DownloadsStorage.loadPayload()
        try {
            DownloadsRepository.clearLocalState()
            store.remove(key)
            assertNull(DownloadsStorage.loadPayload())
            DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
            assertNull(DownloadsStorage.loadPayload(),
                "Loading a fresh account must not write an empty list before its backup can be restored")
        } finally {
            DownloadsRepository.clearLocalState()
            if (original == null) store.remove(key)
            else DownloadsStorage.savePayload(original)
        }
    }
}
