package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import java.net.URI

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null
    private var downloadScheduler: AndroidDownloadScheduler? = null
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var accountEpoch = 0L

    fun initialize(context: Context) {
        scheduler(context)
    }

    @Synchronized
    internal fun scheduler(context: Context): AndroidDownloadScheduler {
        appContext = context.applicationContext
        return downloadScheduler ?: AndroidDownloadScheduler(context.applicationContext).also { downloadScheduler = it }
    }

    internal fun managedTransfers(): List<AndroidDownloadTransfer> =
        downloadScheduler?.store?.transfers?.value?.values?.toList().orEmpty()

    actual fun pauseForAccountCleanup() {
        accountEpoch++
        val scheduler = downloadScheduler ?: appContext?.let(::scheduler) ?: return
        scheduler.pauseAllForAccountCleanup()
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem = downloadScheduler?.restore(item)
        ?: if (item.status == DownloadStatus.Downloading) item.copy(status = DownloadStatus.Paused) else item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val scheduler = checkNotNull(downloadScheduler) { "Downloads are not initialized" }
        val epoch = accountEpoch
        var scheduled: AndroidDownloadTransfer? = null
        val observer = observerScope.launch {
            try {
                // Batch downloads need the same provider/quality lookup as desktop,
                // before a durable JobScheduler/WorkManager transfer can be enqueued.
                val stream = if (request.item.sourceSelection != null) withContext(Dispatchers.IO) {
                    withTimeout(120_000L) {
                        resolveDownloadSource(request.item.copy(sourceSelection =
                            request.item.sourceSelection.copy(transport = "Direct files")))
                    }
                } else null
                coroutineContext.ensureActive()
                if (epoch != accountEpoch) return@launch
                val item = if (stream == null) request.item else request.item.copy(
                    sourceUrl = stream.playableDirectUrl.orEmpty(),
                    sourceHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty(),
                    sourceResponseHeaders = stream.behaviorHints.proxyHeaders?.response.orEmpty(),
                    sourceSubtitles = stream.externalSubtitles,
                    streamTitle = stream.streamLabel,
                    streamSubtitle = stream.streamSubtitle,
                    providerName = stream.addonName,
                    providerAddonId = stream.addonId,
                )
                require(supportedDesktopDownloadUrl(item.sourceUrl)) {
                    "Choose a direct media file. Android offline downloads do not support torrent files or streaming playlists."
                }
                request.onResolved(item)
                coroutineContext.ensureActive()
                if (epoch != accountEpoch) return@launch
                val transfer = scheduler.enqueue(item)
                scheduled = transfer
                scheduler.store.transfers.map { it[request.destinationFileName] }
                    .distinctUntilChanged()
                    .takeWhile { current ->
                        if (current == null || current.generation != transfer.generation) return@takeWhile false
                        val currentItem = current.item
                        when (currentItem.status) {
                            DownloadStatus.Downloading -> onProgress(currentItem.downloadedBytes, currentItem.totalBytes)
                            DownloadStatus.Completed -> onSuccess(checkNotNull(currentItem.localFileUri), currentItem.totalBytes)
                            DownloadStatus.Failed -> onFailure(currentItem.errorMessage ?: "Download failed")
                            DownloadStatus.Paused -> onPaused()
                        }
                        currentItem.status == DownloadStatus.Downloading
                    }.collect()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error.message ?: "Could not find a downloadable file for this episode.")
            }
        }
        return object : DownloadsTaskHandle {
            override fun cancel() {
                observer.cancel()
                val transfer = scheduled
                if (transfer != null &&
                    scheduler.store.get(request.destinationFileName)?.generation == transfer.generation) {
                    scheduler.pause(request.destinationFileName)
                }
            }

            // The scheduler keeps running the transfer; restoreItem reattaches it after a reload.
            override fun detach() {
                observer.cancel()
            }
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val scheduler = downloadScheduler ?: return false
        scheduler.remove(destinationFileName)
        return true
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadsDir = File(context.filesDir, "downloads")
        val localFile = File(downloadsDir, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}
