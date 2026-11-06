package com.nuvio.app.features.downloads

import android.app.Application
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.features.profiles.ProfileRepository
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import org.junit.Assume.assumeNoException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34, 36], application = Application::class)
class AndroidDownloadRemovalSafetyTest {
    private data class Fixture(val scheduler: AndroidDownloadScheduler, val file: File, val item: DownloadItem) {
        fun dispose() {
            scheduler.store.remove(item.fileName)
            file.delete()
            File(scheduler.directory, "${item.fileName}.part").delete()
        }
    }

    private fun completedDownload(profileId: Int): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        // Production has one application context. Robolectric can construct a
        // new context per test while the platform downloader singleton keeps
        // the first scheduler. Align their roots before testing ownership.
        DownloadsPlatformDownloader.scheduler(scheduler.context)
        PlatformLocalAccountDataCleaner.initialize(scheduler.context)
        val name = "owned-${UUID.randomUUID()}.mkv"
        val file = File(scheduler.directory.apply { mkdirs() }, name)
        file.writeText("test owned media")
        val item = downloadItem(id = "id-${UUID.randomUUID()}").copy(
            fileName = name, status = DownloadStatus.Completed,
            downloadedBytes = file.length(), totalBytes = file.length(),
            localFileUri = file.toURI().toString(),
        )
        val transfer = scheduler.store.begin(item, profileId)
        scheduler.store.update(name, transfer.generation) { it.copy(item = item) }
        return Fixture(scheduler, file, item)
    }

    @Test
    fun deletingCompletedOwnedMediaRequiresMatchingTransferAndActiveProfile() {
        val current = ProfileRepository.activeProfileId
        val owned = completedDownload(current)
        val foreign = completedDownload(current + 1)
        try {
            assertTrue(owned.item.isPlayable, "A completed transfer must be playable")
            assertTrue(PlatformLocalAccountDataCleaner.appContext != null, "Cleaner context must be initialized")
            assertTrue(DownloadsPlatformDownloader.managedTransfers().any {
                it.item.id == owned.item.id && it.item.fileName == owned.item.fileName &&
                    it.profileId == current && it.item.status == DownloadStatus.Completed &&
                    it.item.downloadedBytes == owned.item.downloadedBytes &&
                    it.item.localFileUri == owned.item.localFileUri
            }, "An owned completed transfer must remain visible to the preview")
            assertTrue(isPrivateStarlitDownloadFile(owned.scheduler.directory, owned.file),
                "A real app-private regular file must qualify")
            assertEquals(owned.file.canonicalFile, File(URI(requireNotNull(owned.item.localFileUri))).canonicalFile)
            assertEquals(owned.item.downloadedBytes, owned.file.length())
            assertTrue(DownloadStorageManagement.eligibleCleanupBytes(owned.item) != null)
            assertFalse(DownloadsPlatformDownloader.removeFile(foreign.file.toURI().toString()))
            assertFalse(DownloadsPlatformDownloader.removePartialFile(foreign.item.fileName))
            assertTrue(foreign.file.exists(), "Other-profile media must remain untouched")
            assertTrue(foreign.scheduler.store.get(foreign.item.fileName) != null)

            assertTrue(DownloadsPlatformDownloader.removeFile(owned.file.toURI().toString()))
            assertFalse(owned.file.exists())
            assertTrue(DownloadsPlatformDownloader.removePartialFile(owned.item.fileName))
            assertTrue(owned.scheduler.store.get(owned.item.fileName) == null)
        } finally {
            owned.dispose()
            foreign.dispose()
        }
    }

    @Test
    fun arbitrarySavedPathsAndUnmanagedPrivateFilesNeverGetDeleted() {
        val owned = completedDownload(ProfileRepository.activeProfileId)
        val context = RuntimeEnvironment.getApplication()
        val outside = File(context.filesDir, "foreign-${UUID.randomUUID()}.txt")
        val unmanaged = File(owned.scheduler.directory, "unmanaged-${UUID.randomUUID()}.mkv")
        try {
            outside.writeText("retain outside file")
            unmanaged.writeText("retain unmanaged file")
            assertFalse(DownloadsPlatformDownloader.removeFile(outside.toURI().toString()))
            assertFalse(DownloadsPlatformDownloader.removeFile(unmanaged.toURI().toString()))
            assertFalse(DownloadsPlatformDownloader.removeFile(owned.file.parentFile.toURI().toString()))
            assertFalse(DownloadsPlatformDownloader.removePartialFile("../${outside.name}"))
            assertFalse(DownloadsPlatformDownloader.removePartialFile("..\\${outside.name}"))
            assertFalse(DownloadsPlatformDownloader.removePartialFile(unmanaged.name))
            assertTrue(outside.isFile)
            assertTrue(unmanaged.isFile)
            assertTrue(owned.file.isFile)
        } finally {
            owned.dispose()
            outside.delete()
            unmanaged.delete()
        }
    }

    @Test
    fun staleOrUnverifiedTransferCannotAuthorizeDeletingOwnedFile() {
        val owned = completedDownload(ProfileRepository.activeProfileId)
        try {
            val transfer = requireNotNull(owned.scheduler.store.get(owned.item.fileName))
            owned.scheduler.store.update(owned.item.fileName, transfer.generation) {
                it.copy(item = it.item.copy(status = DownloadStatus.Paused))
            }
            assertFalse(DownloadsPlatformDownloader.removeFile(owned.file.toURI().toString()))
            assertTrue(owned.file.isFile)
            owned.scheduler.store.remove(owned.item.fileName)
            assertFalse(DownloadsPlatformDownloader.removeFile(owned.file.toURI().toString()))
            assertFalse(DownloadsPlatformDownloader.removePartialFile(owned.item.fileName))
            assertTrue(owned.file.isFile)
        } finally {
            owned.dispose()
        }
    }

    @Test
    fun symlinkedMediaIsNeverEligibleForCleanupOrDeletion() {
        val owned = completedDownload(ProfileRepository.activeProfileId)
        val external = File(owned.scheduler.directory, "foreign-${UUID.randomUUID()}.mkv")
        try {
            external.writeText("another file's contents")
            assertTrue(owned.file.delete())
            try {
                Files.createSymbolicLink(owned.file.toPath(), external.toPath())
            } catch (error: Exception) {
                assumeNoException("Host cannot create test symlinks; Linux Android CI exercises this case", error)
            }
            assertFalse(isPrivateStarlitDownloadFile(owned.scheduler.directory, owned.file))
            assertFalse(DownloadsPlatformDownloader.removeFile(owned.file.toURI().toString()))
            assertFalse(DownloadStorageManagement.eligibleCleanupBytes(owned.item) != null)
            assertTrue(external.isFile, "A symlink must never delete its target")
        } finally {
            Files.deleteIfExists(owned.file.toPath())
            owned.dispose()
            external.delete()
        }
    }
}
