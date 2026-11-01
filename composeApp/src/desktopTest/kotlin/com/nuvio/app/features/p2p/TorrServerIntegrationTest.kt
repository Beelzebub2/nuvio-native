package com.nuvio.app.features.p2p

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class TorrServerIntegrationTest {
    @Test fun bundledWindowsEngineAcceptsTheDesktopPreferences() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = File(System.getProperty("user.dir"))
        val binary = listOf(
            File(root, "src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
            File(root, "composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
        ).firstOrNull { it.isFile && it.length() > 1_000_000 } ?: error("Bundled engine missing")
        val dir = Files.createTempDirectory("nuvio-owned-engine-test").toFile()
        val backend = TorrServerBackend(dir, File(dir, "cache"), binary)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            backend.restart(P2pSettingsUiState(enableUpload = false, cacheSize = P2pCacheSize.NONE,
                torrentProfile = P2pTorrentProfile.BALANCED))
            val url = backend.getStreamUrl("magnet:", 1).substringBefore("/stream")
            val response = client.send(HttpRequest.newBuilder(URI("$url/settings"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"action":"get"}""")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val applied = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals(JsonPrimitive(268435456L), applied["CacheSize"])
            assertEquals(JsonPrimitive(false), applied["UseDisk"])
            assertEquals(JsonPrimitive(true), applied["DisableUpload"])
            assertEquals(JsonPrimitive(100), applied["ConnectionsLimit"])
            assertEquals(JsonPrimitive(50), applied["PreloadCache"])
            assertEquals(JsonPrimitive(95), applied["ReaderReadAHead"])
        } finally {
            backend.stop()
            client.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
