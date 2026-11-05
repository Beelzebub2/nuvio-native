package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], application = Application::class)
class AndroidDownloadQuotaTest {
    private val gib = 1L shl 30

    private fun budget(context: Context, profile: Int, bytes: Long) {
        context.getSharedPreferences("nuvio_download_storage_budget", Context.MODE_PRIVATE).edit()
            .putString("profile_${profile}_quota_bytes", bytes.toString())
            .putBoolean("profile_${profile}_hard_quota_enabled", true).commit()
    }

    @Test fun backgroundWorkerPausesKnownOversizeWithoutAnActivityObserverAndCanResume(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val profile = 2
        budget(context, profile, gib)
        val occupied = downloadItem().copy(id = "existing", fileName = "existing.mkv",
            status = DownloadStatus.Paused, downloadedBytes = gib - 4L)
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit()
            .putString(ProfileScopedKey.of("downloads_payload", profile), DownloadsCodec.encodeItems(listOf(occupied))).commit()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("complete video"))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "quota-worker").copy(fileName = "quota-worker.mkv")
            val transfer = scheduler.store.begin(item, profile)
            assertFalse(scheduler.execute(transfer) { })
            val paused = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Paused, paused.item.status)
            assertEquals(14L, paused.item.totalBytes)
            assertFalse(File(scheduler.directory, item.fileName).exists())
            assertFalse(File(scheduler.directory, "${item.fileName}.part").exists(), "Known oversized body must never be opened for writing")

            // Recreate the scheduler and change another profile's ceiling. It
            // must keep using the transfer's persisted owner profile.
            val recreated = AndroidDownloadScheduler(context)
            budget(context, 1, 2L * gib)
            val resumed = recreated.store.begin(paused.item, profile)
            server.enqueue(MockResponse().setBody("complete video"))
            assertFalse(recreated.execute(resumed) { })
            assertEquals(DownloadStatus.Paused, recreated.store.get(item.fileName)?.item?.status)

            budget(context, profile, 2L * gib)
            val allowed = recreated.store.begin(assertNotNull(recreated.store.get(item.fileName)).item, profile)
            server.enqueue(MockResponse().setBody("complete video"))
            assertFalse(recreated.execute(allowed) { })
            assertEquals(DownloadStatus.Completed, recreated.store.get(item.fileName)?.item?.status)
            assertEquals("complete video", File(recreated.directory, item.fileName).readText())
            assertEquals(profile, AndroidDownloadScheduler(context).store.get(item.fileName)?.profileId)
        }
    }

    @Test fun unknownLengthPausesInDurableWorkerAndPreservesPartialBytes(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val profile = 3
        budget(context, profile, gib)
        val occupied = downloadItem().copy(id = "existing", fileName = "existing.mkv",
            status = DownloadStatus.Paused, downloadedBytes = gib - 4L)
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit()
            .putString(ProfileScopedKey.of("downloads_payload", profile), DownloadsCodec.encodeItems(listOf(occupied))).commit()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("complete video", 14))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/unknown").toString(), "unknown-quota").copy(fileName = "unknown-quota.mkv")
            val transfer = scheduler.store.begin(item, profile)
            assertFalse(scheduler.execute(transfer) { })
            val paused = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Paused, paused.item.status)
            assertEquals(14L, paused.item.downloadedBytes)
            assertEquals("complete video", File(scheduler.directory, "${item.fileName}.part").readText())
            assertFalse(File(scheduler.directory, item.fileName).exists())
        }
    }

    @Test fun accountWipeClearsQuotaOptInAndTargets() {
        val context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        budget(context, 1, gib)
        budget(context, 2, 2L * gib)
        PlatformLocalAccountDataCleaner.wipe()
        assertTrue(context.getSharedPreferences("nuvio_download_storage_budget", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(null, DownloadStorageManagement.enforcedQuotaBytes(context, 1))
    }

    @Test fun transferOwnershipCannotBeReassignedToAnotherProfileOrFilenameClaim() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context)
        val item = downloadItem().copy(fileName = "owned.mkv")
        val first = scheduler.store.begin(item, 2)
        assertFailsWith<IllegalArgumentException> { scheduler.store.begin(item, 3) }
        assertFailsWith<IllegalArgumentException> { scheduler.store.begin(item.copy(id = "foreign"), 2) }
        assertEquals(first, scheduler.store.get(item.fileName))
        scheduler.directory.mkdirs()
        val untracked = File(scheduler.directory, "untracked.mkv").apply { writeText("preserve me") }
        assertFailsWith<IllegalArgumentException> { scheduler.enqueue(item.copy(fileName = untracked.name)) }
        assertEquals("preserve me", untracked.readText())
    }
}
