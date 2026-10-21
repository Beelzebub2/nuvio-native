package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.p2p.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Desktop
import java.io.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
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
private val torrentBackends = ConcurrentHashMap<String, DesktopTorrentBackend>()
private val shutdownHook by lazy {
    Runtime.getRuntime().addShutdownHook(Thread({
        transfers.values.forEach { it.cancel() }
        transferIo.values.forEach { it.close() }
        runBlocking { withTimeoutOrNull(8_000) { torrentBackends.values.map { backend -> async(Dispatchers.IO) { backend.stop() } }.awaitAll() } }
    }, "starlit-download-shutdown"))
    true
}
internal val legacyDownloadsDirectory: File get() = DesktopStorage.rootDir.resolve("downloads").toFile()

internal actual object DownloadsPlatformDownloader {
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
        if (localFileUri != null) return localFileUri.toLocalFileOrNull()?.takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString()
        if (!safeDownloadName(destinationFileName)) return null
        return File(legacyDownloadsDirectory, destinationFileName).takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString()
    }
    actual fun openDownloadsDirectory(): Boolean = openDownloadDirectory(File(requireNotNull(DownloadDesktopFeatures.directory())))
}

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
        if (!dir.exists()) {
            check(dir.mkdir()) { "Cannot create download directory. Check disk space and write access." }
            marker.writeText(item.id)
        }
        check(dir.toPath().toRealPath() == root.toPath().toRealPath().resolve(item.id)) { "Task directory must not redirect to another folder" }
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
    listOf(file, File(file.path + ".part"), File(file.path + ".validator")).forEach { owned ->
        check(!Files.isSymbolicLink(owned.toPath()))
        if (owned.exists()) check(owned.delete())
    }
    if (item.destinationDirectory != null) {
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

/** Writes only a validated continuous byte range; the final path never contains an incomplete file. */
internal suspend fun copyDesktopDownload(item: DownloadItem, url: String, io: DownloadIo,
    onProgress: (Long, Long?) -> Unit, expectedSize: Long? = null): File {
    val file = prepareDownloadPath(item)
    val part = File(file.path + ".part")
    val validatorFile = File(file.path + ".validator")
    require(!Files.isSymbolicLink(part.toPath()) && !Files.isSymbolicLink(validatorFile.toPath()))
    // A completed file from an interrupted final callback is safe to reuse only with its durable size proof.
    recoverFinalDownload(item)?.let { return it }
    check(!file.exists()) { "Destination file already exists; it was not overwritten" }
    var attempts = 0
    var entityRestarts = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            var offset = part.takeIf { it.isFile }?.length() ?: 0L
            var validator = validatorFile.takeIf { it.isFile }?.readLines()?.firstOrNull().orEmpty()
            // Without a stable entity validator a resumed source may be a different rendition.
            if (offset > 0 && validator.isBlank()) { check(part.delete()); offset = 0 }
            suspend fun response(start: Long): HttpResponse<InputStream> {
                val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET()
                item.sourceHeaders.forEach { (key, value) ->
                    if (key.lowercase() !in setOf("range", "if-range", "accept-encoding", "host", "content-length") && key.isNotBlank()) builder.header(key, value)
                }
                builder.header("Accept-Encoding", "identity")
                if (start > 0) { builder.header("Range", "bytes=$start-"); builder.header("If-Range", validator) }
                return io.send(builder.build())
            }
            var reply = response(offset)
            if (reply.statusCode() == 416) {
                io.close()
                if (part.exists()) check(part.delete())
                offset = 0
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
            } else { offset = 0; total = length.takeIf { it >= 0 } }
            check(expectedSize == null || total == null || expectedSize == total) { "Torrent file length does not match the selected media" }
            val entity = reply.headers().firstValue("ETag").orElse("").takeIf { !it.startsWith("W/") && it.isNotBlank() }
                ?: reply.headers().firstValue("Last-Modified").orElse("")
            if (offset > 0L && reply.statusCode() == 206 && entity != validator) {
                io.close()
                check(part.delete()) { "Cannot restart changed source: partial file is not writable" }
                validatorFile.delete()
                check(++entityRestarts <= 1) { "Source keeps changing; choose another file" }
                continue
            }
            validatorFile.writeText("$entity\n${total ?: -1}")
            var bytes = offset
            var lastProgress = 0L
            val lastRead = AtomicLong(System.nanoTime())
            val stalled = AtomicBoolean(false)
            coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        delay(5_000)
                        if (System.nanoTime() - lastRead.get() > 60_000_000_000L) { stalled.set(true); io.close(); break }
                    }
                }
                try {
                    val transferContext = currentCoroutineContext()
                    runInterruptible(Dispatchers.IO) {
                    reply.body().buffered().use { input ->
                        FileOutputStream(part, offset > 0).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            onProgress(bytes, total)
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
                                bytes += initial.size
                                onProgress(bytes, total)
                            }
                            while (true) {
                                transferContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                lastRead.set(System.nanoTime())

                                output.write(buffer, 0, count)
                                bytes += count
                                check(total == null || bytes <= total) { "Source sent more bytes than advertised" }
                                val now = System.nanoTime()
                                if (now - lastProgress >= 500_000_000L) { onProgress(bytes, total); lastProgress = now }
                            }
                            check(!stalled.get()) { "Source stopped sending data; retry to resume" }
                            check(bytes > 0 && (total == null || bytes == total) && (expectedSize == null || bytes == expectedSize)) { "Incomplete media file: received $bytes of $total bytes" }
                            output.fd.sync()
                        }
                    }
                    }
                } finally { watchdog.cancel(); io.close() }
            }
            currentCoroutineContext().ensureActive()
            // Known final size is also the recovery proof if the app exits before metadata is committed.
            validatorFile.writeText("$entity\n$bytes")
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

internal suspend fun downloadTorrent(item: DownloadItem, io: DownloadIo, progress: (Long, Long?) -> Unit): File {
    val backend = TorrServerBackend(
        configurationDirectory = DesktopStorage.rootDir.resolve("download-torrserver/${item.id}").toFile(),
        cacheDirectory = DesktopStorage.cacheDir.resolve("download-torrents/${item.id}").toFile())
    torrentBackends[item.id] = backend
    try {
        return downloadTorrentFile(item, backend, { P2pSettingsRepository.ensureLoaded(); P2pSettingsRepository.uiState.value },
            { url, size -> copyDesktopDownload(item, url, io, progress, size) })
    } finally { withContext(NonCancellable) { backend.stop(); torrentBackends.remove(item.id, backend) } }
}
internal suspend fun downloadTorrentFile(item: DownloadItem, backend: DesktopTorrentBackend,
    settings: () -> P2pSettingsUiState, copy: suspend (String, Long) -> File): File {
    val hash = checkNotNull(item.torrentHash) { "No downloadable media source" }
    require(Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}").matches(hash)) { "Invalid torrent hash" }
    val magnet = "magnet:?xt=urn:btih:$hash" + item.torrentTrackers.joinToString("") { "&tr=" + URLEncoder.encode(it, Charsets.UTF_8) }
    val selectedSettings = settings()
    check(selectedSettings.p2pEnabled) { "Enable P2P in Settings before downloading torrents, or choose Direct files." }
    backend.prepare(selectedSettings)
    val added = backend.addTorrent(magnet)
    try {
        val files = withTimeout(90_000) {
            var result: List<TorrServerFile>
            do { result = backend.getTorrentStats(added).files; if (result.isEmpty()) delay(1_000) } while (result.isEmpty())
            result
        }
        val chosen = selectDownloadTorrentFile(files, item.torrentFileIdx, item.torrentFilename)
        return copy(backend.getStreamUrl(magnet, chosen.id), chosen.length)
    } finally { withContext(NonCancellable) { runCatching { backend.dropTorrent(added) } } }
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
    } else if (index != null) files.singleOrNull { index >= 0 && index < Int.MAX_VALUE && it.id == index + 1 }
        else videos.singleOrNull()
    check(selected != null && selected in videos) { "Multi-file torrent needs an exact file index / filename. Choose the episode's source manually." }
    return selected
}

internal fun recoverFinalDownload(item: DownloadItem): File? {
    val file = downloadPath(item)
    if (!file.exists()) return null
    if (item.destinationDirectory != null) {
        val marker = File(file.parentFile, ".starlit-owner")
        check(file.parentFile.toPath().toRealPath() == File(item.destinationDirectory).toPath().toRealPath().resolve(item.id))
        check(!Files.isSymbolicLink(file.parentFile.toPath()) && !Files.isSymbolicLink(marker.toPath()) && marker.readText() == item.id)
    }
    val proof = File(file.path + ".validator")
    check(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(proof.toPath()))
    val expected = proof.takeIf { it.isFile }?.readLines()?.getOrNull(1)?.toLongOrNull()
    check(expected != null && file.isFile && file.length() == expected && expected > 0) { "Destination file already exists; it was not overwritten" }
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
