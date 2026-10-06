package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.InetAddress
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import java.io.IOException

internal interface DesktopTorrentBackend {
    suspend fun restart(settings: P2pSettingsUiState)
    suspend fun stop()
    suspend fun addTorrent(magnetLink: String): String
    suspend fun getTorrentStats(hash: String): TorrServerStats
    fun getStreamUrl(magnetLink: String, fileIdx: Int): String
}

internal class DesktopTorrentEngineUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The torrent library binds TCP and UDP to the same port; TCP autoselection alone can collide on Windows. */
internal fun availableTorrentPeerPort(): Int {
    repeat(20) {
        try {
            ServerSocket(0).use { tcp ->
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = false
                    udp.bind(InetSocketAddress(tcp.localPort))
                    return tcp.localPort
                }
            }
        } catch (_: SocketException) {
            // Choose another port if UDP is occupied or excluded by Windows.
        }
    }
    throw DesktopTorrentEngineUnavailable("Cannot find a free TCP/UDP torrent port")
}

internal suspend fun sendTorrServerRequest(client: HttpClient, request: HttpRequest): HttpResponse<String> =
    try {
        runInterruptible(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
    } catch (e: IOException) {
        throw DesktopTorrentEngineUnavailable("Torrent engine is not responding", e)
    }

internal class TorrServerBackend(
    configurationDirectory: File = DesktopStorage.rootDir.resolve("torrserver").toFile(),
    cacheDirectory: File = DesktopStorage.cacheDir.resolve("torrents").toFile(),
    binaryFile: File? = null,
) : DesktopTorrentBackend {
    private val binary = TorrServerBinary(configurationDirectory, binaryFile)
    private val api = TorrServerApi(binary, cacheDirectory)
    val processId: Long? get() = binary.processId

    override suspend fun restart(settings: P2pSettingsUiState) {
        stop()
        binary.start()
        api.configure(settings)
    }
    override suspend fun stop() = withContext(Dispatchers.IO) { binary.stop() }
    override suspend fun addTorrent(magnetLink: String): String = api.addTorrent(magnetLink)
    override suspend fun getTorrentStats(hash: String): TorrServerStats = api.getTorrentStats(hash)
    override fun getStreamUrl(magnetLink: String, fileIdx: Int): String = api.getStreamUrl(magnetLink, fileIdx)
}

private class TorrServerBinary(
    private val configurationDirectory: File,
    private val suppliedBinary: File?,
) {
    private val log = Logger.withTag("TorrServerBinary")
    private val healthClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    @Volatile private var process: Process? = null
    val processId: Long? get() = process?.takeIf { it.isAlive }?.pid()
    private var port = 0
    private var resolvedBinary: File? = null

    val baseUrl: String get() = "http://127.0.0.1:$port"

    suspend fun start() = withContext(Dispatchers.IO) {
        port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        val binaryFile = resolveBinaryFile()
        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true)
        }

        val configDir = configurationDirectory.also { it.mkdirs() }
        val processBuilder = ProcessBuilder(
            binaryFile.absolutePath,
            "--port",
            port.toString(),
            "--ip",
            "127.0.0.1",
            "--torrentaddr",
            ":${availableTorrentPeerPort()}",
            "--path",
            configDir.absolutePath,
        )
        processBuilder.directory(configDir)
        processBuilder.redirectErrorStream(true)

        log.d { "Starting TorrServer on port $port from ${binaryFile.absolutePath}" }
        process = processBuilder.start()

        val proc = process!!
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    log.d { "[server] $line" }
                }
            } catch (_: Exception) {
            }
        }.apply {
            name = "nuvio-torrserver-output"
            isDaemon = true
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            if (isRunning()) {
                log.d { "TorrServer started successfully" }
                return@withContext
            }
            if (!isProcessAlive(process)) {
                val exitCode = process?.exitValue() ?: -1
                process = null
                throw DesktopTorrentEngineUnavailable("TorrServer process died on startup (exit code $exitCode)")
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        throw DesktopTorrentEngineUnavailable("TorrServer failed to start within ${STARTUP_TIMEOUT_MS / 1000}s")
    }

    suspend fun isRunning(): Boolean =
        try {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/echo"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = runInterruptible(Dispatchers.IO) { healthClient.send(request, HttpResponse.BodyHandlers.discarding()) }
            response.statusCode() in 200..299
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    fun stop() {
        process?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(3_000L, TimeUnit.MILLISECONDS) && isProcessAlive(proc)) {
                    proc.destroyForcibly().waitFor(3_000L, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        process = null
        log.d { "TorrServer stopped" }
    }

    private fun isProcessAlive(proc: Process?): Boolean =
        proc?.isAlive == true

    private fun resolveBinaryFile(): File {
        resolvedBinary?.takeIf(File::isFile)?.let { return it }
        return findBinaryFile().also { resolvedBinary = it }
    }

    private fun findBinaryFile(): File {
        suppliedBinary?.let { return it }
        configuredBinaryPath()?.let { configured ->
            val file = File(configured)
            if (file.exists()) return file
            throw P2pStreamingException("Configured TorrServer binary was not found at ${file.absolutePath}")
        }

        val platform = DesktopTorrServerPlatform.current()
        localBinaryCandidates(platform)
            .firstOrNull(File::exists)
            ?.let { return it }

        extractBundledBinary(platform)?.let { return it }

        throw P2pStreamingException(
            "TorrServer desktop binary not found for ${platform.resourceDir}. " +
                "Set -Dnuvio.torrserver.binary=/absolute/path/to/TorrServer or " +
                "NUVIO_TORRSERVER_BINARY, or bundle /torrserver/${platform.resourceDir}/${platform.binaryName}.",
        )
    }

    private fun configuredBinaryPath(): String? =
        System.getProperty("nuvio.torrserver.binary")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("NUVIO_TORRSERVER_BINARY")?.takeIf { it.isNotBlank() }

    private fun localBinaryCandidates(platform: DesktopTorrServerPlatform): List<File> =
        listOf(
            File("composeApp/build/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
            File("build/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
            File("composeApp/src/desktopMain/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
            File("composeApp/src/desktopMain/torrserver/${platform.resourceDir}/${platform.binaryName}"),
            File("composeApp/src/desktopMain/resources/torrserver/${platform.resourceDir}/${platform.binaryName}"),
            File("vendor/TorrServer/dist/${platform.distFileName}"),
            File("vendor/TorrServer/dist/${platform.binaryName}"),
        )

    private fun extractBundledBinary(platform: DesktopTorrServerPlatform): File? {
        val resource = "/torrserver/${platform.resourceDir}/${platform.binaryName}"
        val input = P2pStreamingEngine::class.java.getResourceAsStream(resource) ?: return null
        val dir = DesktopStorage.rootDir.resolve("torrserver/bin/${platform.resourceDir}").toFile().apply { mkdirs() }
        val file = File(dir, platform.binaryName)
        val tempFile = File(dir, "${platform.binaryName}.tmp")
        input.use { source ->
            tempFile.outputStream().use { target -> source.copyTo(target) }
        }
        runCatching {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        file.setExecutable(true)
        return file
    }

    companion object {
        private const val STARTUP_TIMEOUT_MS = 30_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 200L
    }
}

private data class DesktopTorrServerPlatform(
    val resourceDir: String,
    val distFileName: String,
    val binaryName: String,
) {
    companion object {
        fun current(): DesktopTorrServerPlatform {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
            val os = when {
                osName.contains("mac") -> DesktopTorrServerOs(resourceName = "macos", distName = "darwin")
                osName.contains("win") -> DesktopTorrServerOs(resourceName = "windows", distName = "windows")
                osName.contains("linux") -> DesktopTorrServerOs(resourceName = "linux", distName = "linux")
                else -> throw P2pStreamingException("Unsupported desktop OS for TorrServer: $osName")
            }
            val archName = when (arch) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64", "x64" -> "amd64"
                "x86", "i386", "i686" -> "386"
                else -> throw P2pStreamingException("Unsupported desktop architecture for TorrServer: $arch")
            }
            val extension = if (os.distName == "windows") ".exe" else ""
            return DesktopTorrServerPlatform(
                resourceDir = "${os.resourceName}-$archName",
                distFileName = "TorrServer-${os.distName}-$archName$extension",
                binaryName = "TorrServer$extension",
            )
        }
    }
}

private data class DesktopTorrServerOs(
    val resourceName: String,
    val distName: String,
)

internal data class TorrServerFile(
    val id: Int,
    val path: String,
    val length: Long,
)

internal data class TorrServerStats(
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val seeds: Int,
    val preloadedBytes: Long,
    val loadedSize: Long,
    val torrentSize: Long,
    val files: List<TorrServerFile>,
)

private class TorrServerApi(
    private val binary: TorrServerBinary,
    private val cacheDirectory: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private val baseUrl: String get() = binary.baseUrl

    suspend fun configure(settings: P2pSettingsUiState) = withContext(Dispatchers.IO) {
        val current = readSettingsWhenReady()
        val cacheDir = cacheDirectory.also { it.mkdirs() }
        val configured = desktopTorrentSettings(current, settings, cacheDir.absolutePath)
        postJson("/settings", buildJsonObject {
            put("action", "set")
            put("sets", configured)
        })
    }

    private suspend fun readSettingsWhenReady(): JsonObject {
        repeat(3) { attempt ->
            try {
                return postJson("/settings", buildJsonObject { put("action", "get") })
                    ?: throw DesktopTorrentEngineUnavailable("Cannot read torrent engine settings")
            } catch (e: DesktopTorrentEngineUnavailable) {
                if (attempt == 2) throw e
                delay(500L)
            }
        }
        error("Unreachable")
    }

    suspend fun addTorrent(magnetLink: String): String {
        val response = postJson("/torrents", buildJsonObject {
            put("action", "add")
            put("link", magnetLink)
            put("save_to_db", false)
        })
        return response?.stringOrNull("hash")?.takeIf { it.isNotBlank() }
            ?: throw DesktopTorrentEngineUnavailable("Torrent engine did not add the torrent")
    }

    suspend fun getTorrentStats(hash: String): TorrServerStats {
        val stats = postJson("/torrents", buildJsonObject {
            put("action", "get")
            put("hash", hash)
        }) ?: throw DesktopTorrentEngineUnavailable("Torrent engine returned no status")
        return parseTorrServerStats(stats)
    }

    fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
        val encodedLink = URLEncoder.encode(magnetLink, Charsets.UTF_8.name())
        return "$baseUrl/stream?link=$encodedLink&index=$fileIdx&play"
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonObject? {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            // Applying settings rebuilds the torrent client and can wait for old uTP sockets to close.
            .timeout(Duration.ofSeconds(if (path == "/settings" && body.stringOrNull("action") == "set") 45 else 8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = sendTorrServerRequest(client, request)
        if (response.statusCode() !in 200..299) {
            throw DesktopTorrentEngineUnavailable("Torrent engine request failed (HTTP ${response.statusCode()})")
        }
        // Settings writes return a successful empty body, not a JSON object.
        val raw = response.body().orEmpty()
        if (raw.isBlank()) return null
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw DesktopTorrentEngineUnavailable("Torrent engine returned invalid status", e)
        }
    }
}

internal fun parseTorrServerStats(stats: JsonObject): TorrServerStats {
    val files = stats.arrayOrEmpty("file_stats").mapIndexed { index, file ->
        val obj = file.jsonObject
        TorrServerFile(
            id = obj.intOrDefault("id", index + 1),
            path = obj.stringOrNull("path").orEmpty(),
            length = obj.longOrDefault("length", 0L),
        )
    }
    return TorrServerStats(
        downloadSpeed = stats.longOrDefault("download_speed", 0L),
        uploadSpeed = stats.longOrDefault("upload_speed", 0L),
        peers = stats.intOrDefault("active_peers", 0),
        seeds = stats.intOrDefault("connected_seeders", 0),
        preloadedBytes = stats.longOrDefault("preloaded_bytes", 0L),
        loadedSize = stats.longOrDefault("loaded_size", 0L),
        torrentSize = stats.longOrDefault("torrent_size", 0L),
        files = files,
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrDefault(key: String, default: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.longOrDefault(key: String, default: Long): Long =
    // TorrServer reports rate fields (download_speed/upload_speed) as floats, so
    // longOrNull returns null for them; fall back to the double value truncated.
    this[key]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default

private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())
