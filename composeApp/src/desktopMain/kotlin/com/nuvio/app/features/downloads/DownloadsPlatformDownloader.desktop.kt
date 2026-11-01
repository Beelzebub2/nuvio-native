package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.p2p.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Desktop
import java.io.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val desktopDownloadHttpClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
}
private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val pathLocks = ConcurrentHashMap<String, Mutex>()
private val pendingRemovals = ConcurrentHashMap<String, DownloadItem>()
private val transfers = ConcurrentHashMap<String, Job>()
private val transferIo = ConcurrentHashMap<String, DownloadIo>()
private val playbackAwareDownloadLimiter = PlaybackAwareDownloadLimiter()
private val torrentCacheOwnershipDelegate = lazy {
    DownloadTorrentCacheOwnership(DesktopStorage.cacheDir.resolve("download-torrents")).also { it.scavenge() }
}
private val torrentCacheOwnership by torrentCacheOwnershipDelegate
private val torrentEngines = DownloadTorrentEngines(
    create = { hash ->
        val pieces = torrentCacheOwnership.claim(hash)
        TorrServerBackend(
            // A separate app instance can use the same hash concurrently. Keep both
            // the engine's settings and its pieces in that instance's locked session.
            configurationDirectory = File(pieces.parentFile, "config"),
            cacheDirectory = pieces)
    },
    scope = transferScope,
    onRetired = { hash -> torrentCacheOwnership.retire(hash) },
    onPreparing = { hash -> torrentCacheOwnership.invalidateProcessId(hash) },
    onPrepared = { hash, backend -> torrentCacheOwnership.recordProcessId(hash, (backend as? TorrServerBackend)?.processId) },
)
private val shutdownHook by lazy {
    Runtime.getRuntime().addShutdownHook(Thread({
        transfers.values.forEach { it.cancel() }
        transferIo.values.forEach { it.close() }
        runBlocking { withTimeoutOrNull(8_000) { torrentEngines.backends().map { backend -> async(Dispatchers.IO) { backend.stop() } }.awaitAll() } }
        if (torrentCacheOwnershipDelegate.isInitialized()) torrentCacheOwnership.close()
    }, "starlit-download-shutdown"))
    true
}
internal val legacyDownloadsDirectory: File get() = DesktopStorage.rootDir.resolve("downloads").toFile()

internal actual object DownloadsPlatformDownloader {
    actual fun pauseForAccountCleanup() {}
    actual fun restoreItem(item: DownloadItem): DownloadItem = item.copy(bytesPerSecond = 0,
        phase = if (item.status == DownloadStatus.Downloading) "Queued" else null)

    actual fun start(request: DownloadPlatformRequest, onProgress: (Long, Long?) -> Unit,
        onSuccess: (String, Long?) -> Unit, onFailure: (String) -> Unit, onPaused: () -> Unit): DownloadsTaskHandle {
        check(shutdownHook)
        val io = DownloadIo()
        val job = transferScope.launch(start = CoroutineStart.LAZY) {
            val item = request.item
            val key = downloadPath(item).absolutePath
            try {
                pathLocks.computeIfAbsent(key) { Mutex() }.withLock {
                    currentCoroutineContext().ensureActive()
                    onProgress(0, null)
                    val recovered = recoverFinalDownload(item)
                    if (recovered != null) {
                        recordDesktopDownloadIdentity(item)
                        onSuccess(recovered.toURI().toString(), recovered.length())
                        return@withLock
                    }
                    val resolved = if (item.sourceSelection != null) withTimeout(120_000L) { resolveDownloadSource(item) } else null
                    val source = if (resolved == null) item else item.copy(
                        sourceUrl = resolved.playableDirectUrl.orEmpty(), torrentHash = resolved.p2pInfoHash,
                        torrentFileIdx = resolved.p2pFileIdx, torrentFilename = resolved.behaviorHints.filename,
                        torrentTrackers = resolved.p2pTrackers,
                        sourceHeaders = resolved.behaviorHints.proxyHeaders?.request.orEmpty(),
                        sourceSubtitles = resolved.externalSubtitles,
                        streamTitle = resolved.streamLabel, streamSubtitle = resolved.streamSubtitle,
                        providerName = resolved.addonName, providerAddonId = resolved.addonId)
                    request.onResolved(source)
                    val local = if (source.sourceUrl.isNotBlank()) {
                        check(supportedDesktopDownloadUrl(source.sourceUrl)) { "HLS/DASH playlists and external pages cannot be downloaded. Choose a media file or torrent." }
                        copyDesktopDownload(source, source.sourceUrl, io, onProgress)
                    } else {
                        downloadTorrent(source, io, onProgress)
                    }
                    // Save sidecars while the source is still available, before reporting offline-ready.
                    withTimeoutOrNull(60_000L) { DownloadSubtitles.prepare(source, local.toURI().toString()) }
                    currentCoroutineContext().ensureActive()
                    recordDesktopDownloadIdentity(source)
                    onSuccess(local.toURI().toString(), local.length())
                }
            } catch (e: TimeoutCancellationException) { onFailure("Source lookup timed out. Retry or choose another provider.") }
            catch (e: CancellationException) { onPaused(); throw e }
            catch (e: Exception) { onFailure(e.message ?: "Download failed; check the destination drive and retry") }
            finally {
                io.close()
                transferIo.remove(item.id, io)
            }
        }
        transferIo[request.item.id] = io
        transfers[request.item.id] = job
        // Completion runs even if cancellation wins before the coroutine starts.
        job.invokeOnCompletion {
            io.close()
            transferIo.remove(request.item.id, io)
            transferScope.launch {
                pathLocks.computeIfAbsent(downloadPath(request.item).absolutePath) { Mutex() }.withLock {
                    if (transfers.remove(request.item.id, job)) pendingRemovals.remove(request.item.id)?.let(::removeOwnedDownload)
                }
            }
        }
        job.start()
        return object : DownloadsTaskHandle {
            override fun cancel() { job.cancel(); io.close() }
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        // Legacy API is deliberately confined to the old application-owned download directory.
        val file = localFileUri?.toLocalFileOrNull() ?: return false
        if (file.parentFile.canonicalFile != legacyDownloadsDirectory.canonicalFile) return false
        return file.delete()
    }
    actual fun removePartialFile(destinationFileName: String): Boolean {
        if (!safeDownloadName(destinationFileName)) return false
        return File(legacyDownloadsDirectory, "$destinationFileName.part").let { !it.exists() || it.delete() }
    }
    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        if (localFileUri != null) return localFileUri.toLocalFileOrNull()?.let(::verifiedPlayableDownload)?.toURI()?.toString()
        if (!safeDownloadName(destinationFileName)) return null
        return verifiedPlayableDownload(File(legacyDownloadsDirectory, destinationFileName))?.toURI()?.toString()
    }
    actual fun openDownloadsDirectory(): Boolean = openDownloadDirectory(File(requireNotNull(DownloadDesktopFeatures.directory())))
}

private fun verifiedPlayableDownload(file: File): File? = runCatching {
    if (!file.isFile || file.length() <= 0L || Files.isSymbolicLink(file.toPath())) return null
    val proof = readDesktopDownloadProof(file)
    if (proof == null) {
        // Older completed downloads lacked content digests. Keep them available,
        // while rejecting damaged records that claim to be the new proof format.
        if (isModernDesktopDownloadProof(file)) return null
        return file
    }
    if (proof.fileName != file.name || proof.totalBytes != file.length() || proof.completedSha256 == null) return null
    val marker = File(file.parentFile, ".starlit-owner")
    if (marker.exists()) {
        if (!marker.isFile || Files.isSymbolicLink(marker.toPath()) || marker.readText() != proof.ownerId ||
            file.parentFile.toPath().toRealPath() != file.parentFile.parentFile.toPath().toRealPath().resolve(proof.ownerId)) return null
    } else if (file.parentFile.canonicalFile != legacyDownloadsDirectory.canonicalFile ||
        !file.name.contains("_${proof.ownerId}.")) return null
    file.takeIf { verifiedDesktopDownloadContent(it, proof) }
}.getOrNull()

internal fun openDownloadDirectory(directory: File): Boolean = runCatching {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(directory)
    else {
        val os = System.getProperty("os.name").lowercase()
        ProcessBuilder(when { os.contains("win") -> listOf("explorer", directory.absolutePath)
            os.contains("mac") -> listOf("open", directory.absolutePath)
            else -> listOf("xdg-open", directory.absolutePath) }).start()
    }
    true
}.getOrDefault(false)

internal fun safeDownloadName(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
    '/' !in value && '\\' !in value && ':' !in value && File(value).name == value

internal fun downloadPath(item: DownloadItem): File {
    require(safeDownloadName(item.id) && safeDownloadName(item.fileName)) { "Invalid download filename" }
    val root = item.destinationDirectory?.let(::File) ?: legacyDownloadsDirectory
    return File(if (item.destinationDirectory == null) root else File(root, item.id), item.fileName)
}
private fun prepareDownloadPath(item: DownloadItem): File {
    val file = downloadPath(item)
    val dir = file.parentFile
    check(!Files.isSymbolicLink(dir.toPath())) { "Download folder must not be a symbolic link" }
    if (item.destinationDirectory != null) {
        val root = File(item.destinationDirectory)
        check(root.isDirectory) { "Download drive / folder is unavailable: ${root.absolutePath}" }
        val marker = File(dir, ".starlit-owner")
        if (!dir.exists()) check(dir.mkdir()) { "Cannot create download directory. Check disk space and write access." }
        check(dir.toPath().toRealPath() == root.toPath().toRealPath().resolve(item.id)) { "Task directory must not redirect to another folder" }
        check(!Files.isSymbolicLink(marker.toPath())) { "Destination owner marker must not be a symbolic link" }
        // A crash can happen after mkdir or during the very first owner write. Only an
        // otherwise empty task directory can be claimed/repaired; never adopt payloads.
        val entries = dir.listFiles() ?: error("Cannot inspect the download directory")
        val recoverable = entries.isEmpty() ||
            (entries.size == 1 && entries.single() == marker && marker.isFile &&
                item.id.startsWith(marker.readText()) && marker.readText() != item.id)
        if (!marker.exists() || (recoverable && marker.readText() != item.id)) {
            check(recoverable) { "Destination contains unowned content; choose another folder" }
            FileOutputStream(marker, false).use { output ->
                output.write(item.id.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }
        check(!Files.isSymbolicLink(marker.toPath()) && marker.isFile && marker.readText() == item.id) { "Destination belongs to another file; choose another folder" }
    } else check(dir.isDirectory || dir.mkdirs()) { "Cannot create download directory" }
    return file
}
internal fun requestDownloadRemoval(item: DownloadItem) {
    pendingRemovals[item.id] = item
    transferScope.launch {
        pathLocks.computeIfAbsent(downloadPath(item).absolutePath) { Mutex() }.withLock {
            if (transfers[item.id]?.isCompleted != false) pendingRemovals.remove(item.id)?.let(::removeOwnedDownload)
        }
    }
}
internal fun removeOwnedDownload(item: DownloadItem): Boolean = runCatching {
    val file = downloadPath(item)
    val dir = file.parentFile
    check(!Files.isSymbolicLink(dir.toPath()) && !Files.isSymbolicLink(file.toPath()))
    if (item.destinationDirectory != null) {
        check(dir.toPath().toRealPath() == File(item.destinationDirectory).toPath().toRealPath().resolve(item.id))
        check(File(dir, ".starlit-owner").readText() == item.id)
    }
    else check(file.name.contains("_${item.id}."))
    // Never recursively delete a selected folder. Remove only this task's known files and sidecars.
    check(!Files.isSymbolicLink(File(file.path + ".subtitles").toPath()))
    removeDownloadSidecars(File(file.path + ".subtitles"))
    listOf(file, File(file.path + ".part"), downloadProofFile(file), downloadProofTempFile(file)).forEach { owned ->
        check(!Files.isSymbolicLink(owned.toPath()))
        if (owned.exists()) check(owned.delete())
    }
    if (item.destinationDirectory != null) {
        removeRecordedDesktopIdentity(item)
        File(dir, ".starlit-owner").delete()
        dir.delete() // succeeds only when empty
    }
    true
}.getOrDefault(false)

internal class DownloadIo {
    val body = AtomicReference<InputStream?>()
    val pending = AtomicReference<CompletableFuture<*>?>()
    fun close() { pending.getAndSet(null)?.cancel(true); runCatching { body.getAndSet(null)?.close() } }
    suspend fun send(request: HttpRequest): HttpResponse<InputStream> = suspendCancellableCoroutine { continuation ->
        val future = desktopDownloadHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        pending.set(future)
        continuation.invokeOnCancellation { future.cancel(true); close() }
        future.whenComplete { response, error ->
            pending.compareAndSet(future, null)
            if (error != null) { if (continuation.isActive) continuation.resumeWithException(error) }
            else {
                body.set(response.body())
                if (continuation.isActive) continuation.resume(response) else close()
            }
        }
    }
}

/** A restart must durably discard stale bytes before publishing a validator for new bytes. */
private fun resetDownloadPart(part: File) {
    check(!Files.isSymbolicLink(part.toPath())) { "Download partial must not be a symbolic link" }
    FileOutputStream(part, false).use { it.fd.sync() }
}

/** Writes only a validated continuous byte range; the final path never contains an incomplete file. */
internal suspend fun copyDesktopDownload(item: DownloadItem, url: String, io: DownloadIo,
    onProgress: (Long, Long?) -> Unit, expectedSize: Long? = null,
    selectedTorrentFile: TorrServerFile? = null,
    onAfterPartReset: (() -> Unit)? = null): File {
    val file = prepareDownloadPath(item)
    val part = File(file.path + ".part")
    require(!Files.isSymbolicLink(part.toPath()) && !Files.isSymbolicLink(downloadProofFile(file).toPath()))
    val sourceFingerprint = downloadSourceFingerprint(item, url, selectedTorrentFile)
    // Completed-file recovery verifies an actual content digest instead of trusting length alone.
    recoverFinalDownload(item)?.let { return it }
    check(!file.exists()) { "Destination file already exists; it was not overwritten" }
    var attempts = 0
    var entityRestarts = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            var offset = part.takeIf { it.isFile }?.length() ?: 0L
            var previous = readDesktopDownloadProof(file)?.takeIf {
                it.ownerId == item.id && it.fileName == item.fileName && it.sourceFingerprint == sourceFingerprint
            }
            // Legacy two-line records do not bind the URL, selected torrent file or redirect.
            // They cannot authorize an append, even if their ETag happens to match a new source.
            if (offset > 0 && (previous == null || previous.validator.isBlank() ||
                    previous.totalBytes <= 0 || offset > previous.totalBytes ||
                    (expectedSize != null && previous.totalBytes != expectedSize))) {
                resetDownloadPart(part)
                onAfterPartReset?.invoke()
                offset = 0L
                previous = null
            }
            if (offset > 0 && previous?.completedSha256 != null) {
                // Power loss between the durable completion record and the final rename.
                if (offset == previous.totalBytes && downloadSha256File(part) == previous.completedSha256) {
                    Files.move(part.toPath(), file.toPath())
                    return file
                }
                resetDownloadPart(part)
                onAfterPartReset?.invoke()
                offset = 0L
                previous = null
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (offset > 0L) withContext(Dispatchers.IO) {
                FileInputStream(part).buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var seen = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        currentCoroutineContext().ensureActive()
                        digest.update(buffer, 0, read)
                        seen += read
                    }
                    check(seen == offset) { "Partial file changed during resume" }
                }
            }
            suspend fun response(start: Long): HttpResponse<InputStream> {
                val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET()
                item.sourceHeaders.forEach { (key, value) ->
                    if (key.lowercase() !in setOf("range", "if-range", "accept-encoding", "host", "content-length") && key.isNotBlank()) builder.header(key, value)
                }
                builder.header("Accept-Encoding", "identity")
                if (start > 0) { builder.header("Range", "bytes=$start-"); builder.header("If-Range", previous!!.validator) }
                return io.send(builder.build())
            }
            var reply = response(offset)
            if (reply.statusCode() == 416) {
                io.close()
                resetDownloadPart(part)
                onAfterPartReset?.invoke()
                offset = 0L
                previous = null
                digest.reset()
                reply = response(0)
            }
            check(reply.statusCode() == 200 || reply.statusCode() == 206) { "Download HTTP ${reply.statusCode()}; retry later or choose another source" }
            val contentType = reply.headers().firstValue("Content-Type").orElse("").lowercase()
            check(!listOf("text/", "json", "xml", "mpegurl", "dash+xml", "bittorrent").any(contentType::contains)) {
                "Source returned a playlist or web page, not a downloadable media file"
            }
            val length = reply.headers().firstValueAsLong("Content-Length").orElse(-1)
            val range = reply.headers().firstValue("Content-Range").orElse(null)
            val total: Long?
            if (reply.statusCode() == 206) {
                val parsed = parseDownloadRange(range) ?: error("Invalid Content-Range; file was not appended")
                check(parsed.first == offset && parsed.second >= offset && parsed.third > parsed.second) { "Unexpected Content-Range offset; file was not appended" }
                check(length < 0 || length == parsed.second - parsed.first + 1) { "Content-Range length mismatch" }
                total = parsed.third
            } else {
                // A server ignoring Range is a new transfer, regardless of a matching ETag.
                val hadPriorBytes = part.exists() && part.length() > 0L
                resetDownloadPart(part)
                if (hadPriorBytes) onAfterPartReset?.invoke()
                offset = 0L
                digest.reset()
                previous = null
                total = length.takeIf { it >= 0 }
            }
            check(expectedSize == null || total == null || expectedSize == total) { "Torrent file length does not match the selected media" }
            val entity = reply.headers().firstValue("ETag").orElse("").takeIf { !it.startsWith("W/") && it.isNotBlank() }
                ?: reply.headers().firstValue("Last-Modified").orElse("")
            val endpointFingerprint = downloadEndpointFingerprint(reply.uri(), selectedTorrentFile)
            if (offset > 0L && reply.statusCode() == 206 &&
                (previous?.validator != entity || previous?.totalBytes != total ||
                    previous?.endpointFingerprint != endpointFingerprint)) {
                io.close()
                resetDownloadPart(part)
                onAfterPartReset?.invoke()
                check(++entityRestarts <= 1) { "Source keeps changing; choose another file" }
                continue
            }
            val proof = DesktopDownloadProof(item.id, item.fileName, sourceFingerprint,
                endpointFingerprint, entity, total ?: -1)
            writeDesktopDownloadProof(file, proof)
            var bytes = offset
            var lastProgress = 0L
            val lastRead = AtomicLong(System.nanoTime())
            val stalled = AtomicBoolean(false)
            // Reports from the reader and the heartbeat share one lock so progress never goes backwards.
            val progressLock = Any()
            val reported = AtomicLong(bytes)
            fun report(value: Long) = synchronized(progressLock) { reported.set(value); onProgress(value, total) }
            coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        delay(1_000)
                        val quiet = System.nanoTime() - lastRead.get()
                        if (quiet > 60_000_000_000L) { stalled.set(true); io.close(); break }
                        // While no data arrives, keep reporting so the shown speed falls instead of freezing.
                        if (quiet > 2_000_000_000L) synchronized(progressLock) { onProgress(reported.get(), total) }
                    }
                }
                try {
                    val transferContext = currentCoroutineContext()
                    runInterruptible(Dispatchers.IO) {
                    reply.body().buffered().use { input ->
                        FileOutputStream(part, offset > 0).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            report(bytes)
                            if (bytes == 0L) {
                                val prefix = ByteArrayOutputStream()
                                while (prefix.size() < 256) {
                                    transferContext.ensureActive()
                                    val next = input.read()
                                    if (next < 0) break
                                    lastRead.set(System.nanoTime())
                                    prefix.write(next)
                                    val text = prefix.toString(Charsets.US_ASCII).trimStart()
                                    if (text.length >= 16) break
                                }
                                val initial = prefix.toByteArray()
                                validateMediaPrefix(initial, initial.size)
                                output.write(initial)
                                digest.update(initial)
                                bytes += initial.size
                                report(bytes)
                            }
                            while (true) {
                                transferContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                lastRead.set(System.nanoTime())

                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                bytes += count
                                check(total == null || bytes <= total) { "Source sent more bytes than advertised" }
                                val now = System.nanoTime()
                                if (now - lastProgress >= 500_000_000L) { report(bytes); lastProgress = now }
                                // Throttle only when a P2P playback session is active. The HTTP
                                // reader applies TCP backpressure to each download's torrent
                                // process, freeing bandwidth for the live video. The shared
                                // limiter prevents three jobs each consuming a full allowance.
                                val state = P2pStreamingEngine.state.value
                                val playbackActive = state is P2pStreamingState.Connecting ||
                                    state is P2pStreamingState.Streaming
                                val pauseNanos = playbackAwareDownloadLimiter.reserveDelayNanos(
                                    count, playbackActive, now,
                                )
                                if (pauseNanos > 0L) TimeUnit.NANOSECONDS.sleep(pauseNanos)
                            }
                            if (stalled.get()) throw IOException("Source stopped sending data; retry to resume")
                            if (bytes <= 0 || (total != null && bytes != total) ||
                                (expectedSize != null && bytes != expectedSize)
                            ) throw IOException("Incomplete media file: received $bytes of $total bytes")
                            output.fd.sync()
                        }
                    }
                    }
                } finally { watchdog.cancel(); io.close() }
            }
            currentCoroutineContext().ensureActive()
            // The partial bytes were synced above. Persist their digest BEFORE renaming,
            // so a crash either leaves a resumable partial or a verifiable complete file.
            writeDesktopDownloadProof(file, proof.copy(totalBytes = bytes,
                completedSha256 = digest.digest().downloadHex()))
            Files.move(part.toPath(), file.toPath()) // same-directory rename; no REPLACE_EXISTING: never overwrite another file
            onProgress(bytes, bytes)
            return file
        } catch (e: CancellationException) { io.close(); throw e }
        catch (e: IOException) {
            io.close()
            if (++attempts >= 3) throw IOException("Transfer interrupted or destination unavailable: ${e.message}", e)
            delay(attempts * 2_000L)
        } catch (e: Exception) { io.close(); throw e }
    }
}
internal fun parseDownloadRange(value: String?): Triple<Long, Long, Long>? =
    value?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it.trim()) }?.destructured?.let { (a,b,c) ->
        val start = a.toLongOrNull() ?: return null
        val end = b.toLongOrNull() ?: return null
        val total = c.toLongOrNull() ?: return null
        Triple(start, end, total)
    }
private fun validateMediaPrefix(buffer: ByteArray, count: Int) {
    val prefix = String(buffer, 0, count, Charsets.US_ASCII).trimStart().lowercase()
    check(prefix.isNotBlank() && !prefix.startsWith("#extm3u") && !prefix.startsWith("<") && !prefix.startsWith("{") && !prefix.startsWith("[")) {
        "Source returned a playlist or web page instead of media"
    }
}
private fun String.toLocalFileOrNull(): File? = runCatching { if (startsWith("file:")) File(URI(this)) else File(this) }.getOrNull()

internal suspend fun downloadTorrent(item: DownloadItem, io: DownloadIo, progress: (Long, Long?) -> Unit): File =
    downloadTorrentFile(item, torrentEngines, { P2pSettingsRepository.ensureLoaded(); P2pSettingsRepository.uiState.value },
        { url, selected -> copyDesktopDownload(item, url, io, progress, selected.length, selected) })

/** TorrServer accepts hexadecimal BTIH; normalize legacy 32-character base32 BTIH first. */
internal fun canonicalDownloadTorrentHash(infoHash: String): String {
    val value = infoHash.trim()
    if (value.length != 32) return canonicalP2pInfoHash(value)
    val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val bytes = ByteArray(20)
    var bits = 0
    var buffer = 0
    var output = 0
    for (char in value.uppercase()) {
        val digit = base32.indexOf(char)
        require(digit >= 0) { "Torrent info hash is not valid base32" }
        buffer = (buffer shl 5) or digit
        bits += 5
        if (bits >= 8) {
            bits -= 8
            bytes[output++] = (buffer ushr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    check(output == bytes.size && bits == 0) { "Invalid torrent base32 hash length" }
    return bytes.downloadHex()
}

/** Metadata may be delayed or temporarily unavailable while a healthy engine connects to peers. */
internal suspend fun awaitDownloadTorrentMetadata(backend: DesktopTorrentBackend, hash: String,
    timeoutMs: Long = 90_000L, pollMs: Long = 1_000L): List<TorrServerFile> = withTimeout(timeoutMs) {
    var files: List<TorrServerFile> = emptyList()
    while (files.isEmpty()) {
        try {
            files = backend.getTorrentStats(hash).files
        } catch (e: CancellationException) {
            throw e
        } catch (e: DesktopTorrentEngineUnavailable) {
            if (e.httpStatusCode == 404) {
                throw DesktopTorrentEngineUnavailable("Torrent disappeared from the local engine; retry this source", e, 404)
            }
            if (e.httpStatusCode != null && e.httpStatusCode !in 500..599 &&
                e.httpStatusCode !in listOf(408, 425, 429)) throw e
            if (!backend.isHealthy()) throw DesktopTorrentEngineUnavailable("Torrent engine stopped while fetching metadata", e)
        } catch (e: IOException) {
            if (!backend.isHealthy()) throw DesktopTorrentEngineUnavailable("Torrent engine stopped while fetching metadata", e)
        }
        if (files.isEmpty()) delay(pollMs)
    }
    files
}

/** Reads one file of a torrent through the engine shared by every download of that torrent. */
internal suspend fun downloadTorrentFile(item: DownloadItem, engines: DownloadTorrentEngines,
    settings: () -> P2pSettingsUiState, copy: suspend (String, TorrServerFile) -> File): File {
    val hash = canonicalDownloadTorrentHash(checkNotNull(item.torrentHash) { "No downloadable media source" })
    val magnet = buildP2pMagnetUri(hash, item.torrentTrackers)
    val selectedSettings = settings()
    check(selectedSettings.p2pEnabled) { "Enable P2P in Settings before downloading torrents, or choose Direct files." }
    return engines.use(hash, selectedSettings) { backend ->
        // Adding a torrent the engine already has returns the existing one and its peers.
        val added = try { backend.addTorrent(magnet) } catch (e: DesktopTorrentEngineUnavailable) {
            if (hash.length == 64) throw DesktopTorrentEngineUnavailable(
                "This Torrent v2 source could not be added by the installed engine. Try a v1 source or update TorrServer.",
                e, e.httpStatusCode)
            throw e
        }
        val files = awaitDownloadTorrentMetadata(backend, added)
        val chosen = selectDownloadTorrentFile(files, item.torrentFileIdx, item.torrentFilename)
        copy(backend.getStreamUrl(magnet, chosen.id), chosen)
    }
}
internal fun selectDownloadTorrentFile(files: List<TorrServerFile>, index: Int?, filename: String?): TorrServerFile {
    val videos = files.filter { it.path.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts") && it.length > 0 }
    // TorrServer assigns IDs after sorting paths; an addon's fileIdx uses the torrent's own order.
    // Resolve the supplied filename first, then pass that file's native ID to /stream.
    val selected = if (!filename.isNullOrBlank()) {
        val path = filename.trim().replace('\\', '/')
        check(path.split('/').none { it == "." || it == ".." || it.isEmpty() }) { "Invalid torrent filename. Choose another source." }
        val exact = videos.filter { it.path.replace('\\', '/').equals(path, true) }
        val matches = if (exact.isNotEmpty()) exact else videos.filter {
            val candidate = it.path.replace('\\', '/')
            if ('/' in path) candidate.endsWith("/$path", true)
            else candidate.substringAfterLast('/').equals(path, true)
        }
        check(matches.size == 1) { "The requested torrent filename is missing or ambiguous. Choose another episode source." }
        matches.single()
    } else videos.singleOrNull()
    // File indices supplied by add-ons are in original torrent order, while TorrServer
    // assigns IDs after sorting paths. An index alone cannot disambiguate a season pack.
    check(selected != null && selected in videos) {
        "Multi-video torrent needs an exact filename. Choose the episode's source manually."
    }
    return selected
}

internal fun recoverFinalDownload(item: DownloadItem): File? {
    val file = downloadPath(item)
    if (!file.exists()) return null
    // Inspect the owner even on offline recovery. A file cannot create ownership for itself.
    prepareDownloadPath(item)
    if (item.destinationDirectory != null) {
        val marker = File(file.parentFile, ".starlit-owner")
        check(file.parentFile.toPath().toRealPath() == File(item.destinationDirectory).toPath().toRealPath().resolve(item.id))
        check(!Files.isSymbolicLink(file.parentFile.toPath()) && !Files.isSymbolicLink(marker.toPath()) && marker.readText() == item.id)
    }
    check(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(downloadProofFile(file).toPath()))
    var proof = readDesktopDownloadProof(file)
    if (proof == null && file.isFile && (item.destinationDirectory != null || file.name.contains("_${item.id}."))) {
        // One-time compatibility for downloads finished by older versions. Their existing
        // owner and durable length proof establish the legacy baseline; later accesses
        // can detect same-size changes using the newly saved digest. No legacy .part
        // file is ever resumed, and migration never changes the payload.
        val legacySize = readLegacyCompletedSize(file)
        if (legacySize != null && file.length() == legacySize) {
            val migrated = DesktopDownloadProof(item.id, item.fileName,
                downloadSha256Text("legacy-completed\u0000${item.id}\u0000${item.fileName}"),
                "-", "", legacySize, downloadSha256File(file))
            writeDesktopDownloadProof(file, migrated)
            proof = migrated
        }
    }
    check(proof != null && proof.ownerId == item.id && proof.fileName == item.fileName &&
        proof.completedSha256 != null && file.isFile && file.length() == proof.totalBytes &&
        proof.totalBytes > 0 && verifiedDesktopDownloadContent(file, proof)) {
        "Existing destination is missing its verified content proof or its bytes changed; file was preserved"
    }
    return file
}
private fun removeDownloadSidecars(directory: File) {
    if (!directory.exists()) return
    check(!Files.isSymbolicLink(directory.toPath()))
    check(directory.toPath().toRealPath() == directory.parentFile.toPath().toRealPath().resolve(directory.name))
    // Only manifests and numbered tracks are ours. Do not traverse any nested directory / junction.
    directory.listFiles().orEmpty().forEach { file ->
        if (file.name == "manifest.json" || Regex("[0-9]+\\.(srt|vtt|ass|ssa|sub|txt)").matches(file.name)) {
            check(!Files.isSymbolicLink(file.toPath()) && file.isFile)
            check(file.delete())
        }
    }
    directory.delete()
}
