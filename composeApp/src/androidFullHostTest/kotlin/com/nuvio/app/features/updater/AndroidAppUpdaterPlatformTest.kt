package com.nuvio.app.features.updater

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAppUpdaterPlatformTest {
    private lateinit var app: Application
    private lateinit var updateDirectory: File
    private val ownedNames = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        AndroidAppUpdaterPlatform.initialize(app)
        updateDirectory = File(app.cacheDir, "updates")
    }

    @AfterTest
    fun cleanup() {
        ownedNames.forEach { name ->
            File(updateDirectory, name).delete()
            temporaryFiles(name).forEach(File::delete)
        }
        AndroidAppUpdaterPlatform.initialize(app)
    }

    @Test
    fun matchingDigestPublishesCompleteApkAndProgress() = runBlocking {
        val name = assetName()
        val body = ByteArray(65_536) { it.toByte() }
        val reported = mutableListOf<Pair<Long, Long?>>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            server.start()

            val result = AndroidAppUpdaterPlatform.downloadUpdateAsset(
                server.url("/update.apk").toString(),
                name,
                sha256(body).uppercase(),
            ) { downloaded, total -> reported += downloaded to total }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            val installedFile = File(result.getOrThrow())
            assertEquals(File(updateDirectory, name).absolutePath, installedFile.absolutePath)
            assertContentEquals(body, installedFile.readBytes())
            assertEquals(body.size.toLong() to body.size.toLong(), reported.last())
            assertTrue(temporaryFiles(name).isEmpty())
        }
    }

    @Test
    fun verifiedUpdateReplacesExistingInstallerAtomically() = runBlocking {
        val name = assetName()
        val destination = File(updateDirectory.apply { mkdirs() }, name)
        destination.writeText("previous installer")
        val body = "complete replacement package".toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            server.start()

            val result = AndroidAppUpdaterPlatform.downloadUpdateAsset(
                server.url("/update.apk").toString(),
                name,
                sha256(body),
            ) { _, _ -> }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertContentEquals(body, destination.readBytes())
            assertFalse(File(updateDirectory, "$name.new").exists())
            assertTrue(temporaryFiles(name).isEmpty())
        }
    }

    @Test
    fun missingOrMalformedChecksumRejectsDownloadBeforeNetwork() = runBlocking {
        val name = assetName()
        for (checksum in listOf(null, "", "abc123", "g".repeat(64))) {
            val result = AndroidAppUpdaterPlatform.downloadUpdateAsset(
                "http://127.0.0.1:1/update.apk",
                name,
                checksum,
            ) { _, _ -> error("Invalid checksum must prevent the download.") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertFalse(File(updateDirectory, name).exists())
        assertTrue(temporaryFiles(name).isEmpty())
    }

    @Test
    fun checksumMismatchPreservesExistingInstallerAndCleansPartial() = runBlocking {
        val name = assetName()
        val destination = File(updateDirectory.apply { mkdirs() }, name)
        destination.writeText("existing verified installer")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("untrusted bytes"))
            server.start()

            val result = AndroidAppUpdaterPlatform.downloadUpdateAsset(
                server.url("/update.apk").toString(),
                name,
                sha256("trusted bytes".toByteArray()),
            ) { _, _ -> }

            assertTrue(result.isFailure)
            assertEquals("existing verified installer", destination.readText())
            assertTrue(temporaryFiles(name).isEmpty())
        }
    }

    @Test
    fun interruptedResponseDoesNotPublishPartialApk() = runBlocking {
        val name = assetName()
        val body = ByteArray(65_536) { it.toByte() }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(Buffer().write(body))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.start()

            val result = AndroidAppUpdaterPlatform.downloadUpdateAsset(
                server.url("/update.apk").toString(),
                name,
                sha256(body),
            ) { _, _ -> }

            assertTrue(result.isFailure)
            assertFalse(File(updateDirectory, name).exists())
            assertTrue(temporaryFiles(name).isEmpty())
        }
    }

    @Test
    fun cancellationRemovesPartialDownload() = runBlocking {
        val name = assetName()
        val body = ByteArray(65_536) { it.toByte() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            server.start()

            val job = launch(Dispatchers.IO) {
                val downloadJob = coroutineContext[Job]!!
                AndroidAppUpdaterPlatform.downloadUpdateAsset(
                    server.url("/update.apk").toString(),
                    name,
                    sha256(body),
                ) { _, _ -> downloadJob.cancel() }
            }
            job.join()

            assertTrue(job.isCancelled)
            assertFalse(File(updateDirectory, name).exists())
            assertTrue(temporaryFiles(name).isEmpty())
        }
    }

    @Test
    fun packageInstallPermissionSecurityExceptionIsDenied() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): PackageManager = throw SecurityException("Permission denied")
        }
        AndroidAppUpdaterPlatform.initialize(context)

        assertFalse(AndroidAppUpdaterPlatform.canRequestPackageInstalls())
    }

    private fun assetName(): String = "integrity-${UUID.randomUUID()}.apk".also(ownedNames::add)

    private fun temporaryFiles(name: String): List<File> =
        updateDirectory.listFiles().orEmpty().filter {
            it.name.startsWith("apk-${name.take(40)}-") && it.name.endsWith(".part")
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
