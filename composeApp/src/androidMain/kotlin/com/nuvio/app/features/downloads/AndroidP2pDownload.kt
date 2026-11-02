package com.nuvio.app.features.downloads

import android.content.Context
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.buildNuvioEngineConfig
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.app.features.p2p.canonicalP2pInfoHash
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioTorrentFile
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Separate native engine: saving an episode must never replace the playing P2P stream. */
internal object AndroidP2pDownload {
    // Nuvio Engine's native payload cache is shared by routes within one instance.
    // A single independent download instance avoids simultaneous cache reconfiguration,
    // while the regular Android scheduler keeps HTTP downloads parallel.
    private val engineLock = Mutex()

    suspend fun transfer(
        context: Context,
        item: DownloadItem,
        directory: File,
        previousIdentity: String?,
        onSelected: (NuvioTorrentFile, String) -> Unit,
        onHeaders: (Long?, String?) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ): File = engineLock.withLock {
        withContext(Dispatchers.IO) {
            P2pSettingsRepository.ensureLoaded()
            val settings = P2pSettingsRepository.uiState.value
            require(P2pSettingsRepository.isVisible && settings.p2pEnabled) {
                "Enable P2P in Settings to download torrent sources."
            }
            val hash = canonicalP2pInfoHash(checkNotNull(item.torrentHash) { "Torrent info hash is missing" })
            val engineState = File(context.noBackupFilesDir, "nuvio-engine/offline-download-state")
            val engineCache = File(context.cacheDir, "nuvio-engine/offline-download-cache")
            check((engineState.isDirectory || engineState.mkdirs()) &&
                (engineCache.isDirectory || engineCache.mkdirs())) { "Cannot initialize the offline torrent engine" }
            val engine = NuvioEngine.create(buildNuvioEngineConfig(
                stateDirectory = engineState,
                cacheDirectory = engineCache,
                uploadEnabled = settings.enableUpload,
                torrentProfile = settings.torrentProfile,
                diskCacheCapacityBytes = settings.cacheSize.bytes,
            ))
            var routeId: String? = null
            try {
                currentCoroutineContext().ensureActive()
                val trackers = (item.torrentTrackers + listOf(
                    "udp://tracker.opentrackr.org:1337/announce",
                    "udp://tracker.torrent.eu.org:451/announce",
                    "udp://open.stealth.si:80/announce",
                )).distinct()
                val torrentId = engine.addMagnet(buildP2pMagnetUri(hash, trackers))
                val files = withRetryableTorrentMetadataTimeout(120_000L) {
                    var result: List<NuvioTorrentFile>
                    do {
                        currentCoroutineContext().ensureActive()
                        result = try { engine.files(torrentId) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() } // Metadata may not be ready yet.
                        if (result.isNotEmpty() && result.all { it.path.isNotBlank() && it.size >= 0L }) break
                        delay(700L)
                    } while (true)
                    result
                }
                val selected = selectAndroidTorrentFile(files, item)
                val identity = androidTorrentSourceIdentity(hash, selected)
                val partial = File(directory, "${item.fileName}.part")
                // A source change, stale on-disk record or old app version cannot
                // prove existing partial bytes belong to this exact torrent file.
                // Only this task's own .part file is reset; saved completed media is never touched.
                if (partial.exists() && previousIdentity != identity) {
                    check(partial.delete()) { "Could not safely restart this torrent download" }
                }
                currentCoroutineContext().ensureActive()
                onSelected(selected, identity)
                val route = engine.prepareStream(
                    torrentId = torrentId, fileIndex = selected.index, filenameHint = selected.path,
                )
                routeId = route.id
                check(route.fileIndex == selected.index && route.fileSize == selected.size) {
                    "Torrent stream selected an unexpected file. The download was stopped."
                }
                val address = URI(route.url)
                check(address.scheme.equals("http", true) &&
                    address.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
                    "The native torrent engine returned an invalid local stream address"
                }
                val client = downloadHttpClient.newBuilder()
                    // The route is an app-owned loopback endpoint, not the
                    // source provider. Do not forward its credentials or
                    // follow a redirect to an arbitrary network host.
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .readTimeout(180L, TimeUnit.SECONDS)
                    .connectTimeout(60L, TimeUnit.SECONDS)
                    .build()
                val result = transferAndroidDownload(
                    item = item.copy(sourceUrl = route.url, sourceHeaders = emptyMap()),
                    directory = directory,
                    validator = null,
                    client = client,
                    expectedBytes = selected.size,
                    immutableTorrent = true,
                    onHeaders = { _, _ -> onHeaders(selected.size, null) },
                    onProgress = onProgress,
                )
                check(result.length() == selected.size) {
                    "Torrent download is incomplete; retry to resume the verified partial file."
                }
                result
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    routeId?.let { id -> runCatching { engine.stopStream(id) } }
                    runCatching { engine.shutdown() }
                }
            }
        }
    }
}

/** Metadata starvation must not be interpreted as a cancelled Android job. */
internal suspend fun <T> withRetryableTorrentMetadataTimeout(
    timeoutMillis: Long,
    operation: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis) { operation() }
} catch (timeout: TimeoutCancellationException) {
    throw IOException("Torrent metadata is unavailable from peers. Retry this download.", timeout)
}
