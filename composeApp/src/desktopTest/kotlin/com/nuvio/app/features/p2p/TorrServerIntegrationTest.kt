package com.nuvio.app.features.p2p

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class TorrServerIntegrationTest {
    @Test fun bundledWindowsEngineAcceptsTheDesktopPreferences() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = File(System.getProperty("user.dir"))
        val binary = listOf(
            File(root, "src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
            File(root, "composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
        ).firstOrNull { it.isFile && it.length() > 1_000_000 } ?: error("Bundled engine missing")
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val dir = Files.createTempDirectory("nuvio-owned-engine-test").toFile()
        val process = ProcessBuilder(binary.absolutePath, "--port", "$port", "--ip", "127.0.0.1", "--path", dir.absolutePath)
            .redirectOutput(File(dir, "output.log")).redirectError(File(dir, "error.log")).start()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        fun post(body: String): String {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/settings"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            return response.body()
        }
        try {
            var defaults: String? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (defaults == null && System.nanoTime() < deadline) {
                defaults = runCatching { post("""{"action":"get"}""") }.getOrNull()
                if (defaults == null) Thread.sleep(200)
            }
            val initial = Json.parseToJsonElement(defaults ?: error("Engine startup timed out")).jsonObject
            val configured = desktopTorrentSettings(initial, P2pSettingsUiState(
                enableUpload = false, cacheSize = P2pCacheSize.NONE, torrentProfile = P2pTorrentProfile.BALANCED,
            ), File(dir, "cache").absolutePath)
            post(buildJsonObject { put("action", "set"); put("sets", configured) }.toString())
            val applied = Json.parseToJsonElement(post("""{"action":"get"}""")).jsonObject
            listOf("CacheSize", "UseDisk", "DisableUpload", "ConnectionsLimit", "PreloadCache", "ReaderReadAHead").forEach {
                assertEquals(configured[it], applied[it], it)
            }
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
