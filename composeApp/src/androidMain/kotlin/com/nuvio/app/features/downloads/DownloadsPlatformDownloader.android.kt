package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import com.nuvio.app.features.p2p.P2pSettingsRepository
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    actual val supportsTorrentDownloads: Boolean = true
    @Volatile private var appContext: Context? = null
    @Volatile private var downloadScheduler: AndroidDownloadScheduler? = null
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var accountEpoch = 0L

    fun initialize(context: Context) {
        // Keep the cold-start Activity path free of the transfer-store scan.
        // The first download restore/notification/worker access opens the store instead.
        appContext = context.applicationContext
    }

    @Synchronized
    internal fun scheduler(context: Context): AndroidDownloadScheduler {
        appContext = context.applicationContext
        return downloadScheduler ?: AndroidDownloadScheduler(context.applicationContext).also { downloadScheduler = it }
    }

    internal fun managedTransfers(): List<AndroidDownloadTransfer> =
        (downloadScheduler ?: appContext?.let(::scheduler))
            ?.store?.transfers?.value?.values?.toList().orEmpty()

    actual fun pauseForAccountCleanup() {
        accountEpoch++
        val scheduler = downloadScheduler ?: appContext?.let(::scheduler) ?: return
        scheduler.pauseAllForAccountCleanup()
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        (downloadScheduler ?: appContext?.let(::scheduler))?.restore(item)
        ?: if (item.status == DownloadStatus.Downloading) item.copy(status = DownloadStatus.Paused) else item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val scheduler = checkNotNull(downloadScheduler ?: appContext?.let(::scheduler)) {
            "Downloads are not initialized"
        }
        val epoch = accountEpoch
        var scheduled: AndroidDownloadTransfer? = null
        val observer = observerScope.launch {
            try {
                // Batch downloads need the same provider/quality lookup as desktop,
                // before a durable JobScheduler/WorkManager transfer can be enqueued.
                val stream = if (request.item.sourceSelection != null) withContext(Dispatchers.IO) {
                    withTimeout(120_000L) {
                        resolveDownloadSource(request.item)
                    }
                } else null
                coroutineContext.ensureActive()
                if (epoch != accountEpoch) return@launch
                val filenameHints = stream?.downloadTorrentFilenameHints().orEmpty()
                val item = if (stream == null) request.item else request.item.copy(
                    sourceUrl = stream.playableDirectUrl.orEmpty(),
                    torrentHash = if (stream.playableDirectUrl == null) stream.p2pInfoHash else null,
                    torrentFileIdx = stream.p2pFileIdx,
                    torrentFilename = filenameHints.firstOrNull(),
                    torrentFilenameAlternates = filenameHints.drop(1),
                    torrentTrackers = stream.p2pTrackers,
                    sourceHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty(),
                    sourceResponseHeaders = stream.behaviorHints.proxyHeaders?.response.orEmpty(),
                    sourceSubtitles = stream.externalSubtitles,
                    streamTitle = stream.streamLabel,
                    streamSubtitle = stream.streamSubtitle,
                    providerName = stream.addonName,
                    providerAddonId = stream.addonId,
                )
                if (item.torrentHash != null && item.sourceUrl.isBlank()) {
                    P2pSettingsRepository.ensureLoaded()
                    require(P2pSettingsRepository.isVisible && P2pSettingsRepository.uiState.value.p2pEnabled) {
                        "Enable P2P in Settings to download torrent sources."
                    }
                } else require(supportedDesktopDownloadUrl(item.sourceUrl)) {
                    "Choose a downloadable video file or torrent. Streaming playlists cannot be saved."
                }
                request.onResolved(item)
                coroutineContext.ensureActive()
                if (epoch != accountEpoch) return@launch
                val transfer = scheduler.enqueue(item)
                scheduled = transfer
                var lastResolvedPhase = item.phase
                scheduler.store.transfers.map { it[request.destinationFileName] }
                    .distinctUntilChanged()
                    .takeWhile { current ->
                        if (current == null || current.generation != transfer.generation) return@takeWhile false
                        val currentItem = current.item
                        when (currentItem.status) {
                            DownloadStatus.Downloading -> {
                                if (currentItem.phase != lastResolvedPhase) {
                                    lastResolvedPhase = currentItem.phase
                                    request.onResolved(currentItem)
                                }
                                onProgress(currentItem.downloadedBytes, currentItem.totalBytes)
                            }
                            DownloadStatus.Completed -> onSuccess(checkNotNull(currentItem.localFileUri), currentItem.totalBytes)
                            DownloadStatus.Failed -> onFailure(currentItem.errorMessage ?: "Download failed")
                            DownloadStatus.Paused -> {
                                onProgress(currentItem.downloadedBytes, currentItem.totalBytes)
                                onPaused()
                            }
                        }
                        currentItem.status == DownloadStatus.Downloading
                    }.collect()
            } catch (_: TimeoutCancellationException) {
                onFailure("The source search timed out. Retry this download.")
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
        // Queue records (including imported/legacy records) are not proof of
        // ownership. Never delete an arbitrary file:// path, another profile's
        // completed download, or a symlink on the strength of a saved URI.
        return runCatching {
            val context = appContext ?: return false
            val scheduler = downloadScheduler ?: return false
            val file = localFileUri.toLocalFileOrNull() ?: return false
            val root = File(context.filesDir, "downloads")
            if (!isPrivateStarlitDownloadFile(root, file)) return false
            val owned = scheduler.store.get(file.name) ?: return false
            if (owned.profileId != com.nuvio.app.features.profiles.ProfileRepository.activeProfileId ||
                owned.item.status != DownloadStatus.Completed ||
                owned.item.localFileUri?.toLocalFileOrNull()?.canonicalFile != file.canonicalFile) return false
            file.delete()
        }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val scheduler = downloadScheduler ?: return false
        if (destinationFileName.isBlank() || destinationFileName == "." ||
            destinationFileName == ".." || '/' in destinationFileName || '\\' in destinationFileName) return false
        val transfer = scheduler.store.get(destinationFileName) ?: return false
        if (transfer.profileId != com.nuvio.app.features.profiles.ProfileRepository.activeProfileId) return false
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
