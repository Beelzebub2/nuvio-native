package com.nuvio.app.features.updater

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed_http
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import nuvio.composeapp.generated.resources.updates_empty_download_body
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AndroidAppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val ignoredTagKey = "ignored_release_tag"
    private const val updateChannelKey = "update_channel"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun getSupportedAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    fun isDebugBuild(): Boolean {
        val context = appContext ?: return false
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun getIgnoredTag(): String? =
        preferences().getString(ignoredTagKey, null)

    fun setIgnoredTag(tag: String?) {
        preferences().edit().apply {
            if (tag == null) remove(ignoredTagKey) else putString(ignoredTagKey, tag)
        }.apply()
    }

    fun getUpdateChannel(): String? = preferences().getString(updateChannelKey, null)

    fun setUpdateChannel(channel: String) {
        preferences().edit().putString(updateChannelKey, channel).apply()
    }

    fun deleteDownloadedUpdate(path: String) {
        File(path).delete()
    }

    suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(expectedSha256 != null && expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                "This update is missing a valid SHA-256 checksum."
            }
            val context = requireContext()
            val sanitizedName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeName = sanitizedName.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "update.apk"
            val updateDirectory = File(context.cacheDir, "updates")
            check(updateDirectory.isDirectory || updateDirectory.mkdirs()) { "Unable to create update cache." }
            val destination = File(updateDirectory, safeName)
            val tempFile = File.createTempFile("apk-${safeName.take(40)}-", ".part", updateDirectory)

            try {
                val request = Request.Builder().url(assetUrl).build()
                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(runBlocking { getString(Res.string.updates_download_failed_http, response.code) })
                    }

                    val body = response.body ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })
                    val expectedLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = expectedLength?.takeIf { it > 0L }
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                currentCoroutineContext().ensureActive()
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloadedBytes += read.toLong()
                                onProgress(downloadedBytes, totalBytes)
                            }
                            output.flush()
                        }
                    }
                    check(expectedLength == null || downloadedBytes == expectedLength) {
                        "Update download length mismatch. The installer was discarded."
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(downloadedBytes > 0L && actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    "Update checksum verification failed. The installer was discarded."
                }
                publishVerifiedDownload(tempFile, destination, actualSha256)
                destination.absolutePath
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun publishVerifiedDownload(tempFile: File, destination: File, expectedSha256: String) {
        currentCoroutineContext().ensureActive()
        if (tempFile.renameTo(destination)) return

        // AtomicFile stages the verified bytes before replacing an existing installer.
        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()
        try {
            tempFile.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
            currentCoroutineContext().ensureActive()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }

        // Verify the committed file as some Android filesystem implementations can fail a rename silently.
        val committedDigest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) committedDigest.update(buffer, 0, read)
            }
        }
        val committedSha256 = committedDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(destination.length() == tempFile.length() && committedSha256 == expectedSha256) {
            "Update installation file could not be committed safely."
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        val context = appContext ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: SecurityException) {
                false
            }
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installDownloadedUpdate(path: String): Result<Unit> = runCatching {
        val context = requireContext()
        val apkFile = File(path)
        check(apkFile.exists()) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun preferences() = requireContext().getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun requireContext(): Context =
        requireNotNull(appContext) { "AndroidAppUpdaterPlatform.initialize must be called before use." }
}
