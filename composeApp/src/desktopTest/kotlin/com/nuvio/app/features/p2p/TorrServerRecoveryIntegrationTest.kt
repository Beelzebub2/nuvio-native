package com.nuvio.app.features.p2p

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TorrServerRecoveryIntegrationTest {
    @Test fun portSelectionSkipsAnOccupiedUdpPortAndBoundsFailures() {
        DatagramSocket(0).use { occupied ->
            var tries = 0
            val free = availableTorrentPeerPort()
            val selected = availableTorrentPeerPort { if (tries++ == 0) occupied.localPort else free }
            assertEquals(free, selected)
            assertEquals(2, tries)
            assertFailsWith<DesktopTorrentEngineUnavailable> { availableTorrentPeerPort { occupied.localPort } }
        }
    }

    @Test fun prelaunchSettingsRetainOtherPreferencesAndUnknownFields() {
        val directory = Files.createTempDirectory("nuvio-settings-test").toFile()
        try {
            val file = File(directory, "settings.json")
            file.writeText("""{"BitTorr":{"StoreSettingsInJson":true,"EnableIPv6":true,"CacheSize":1},"Other":{"keep":"yes"}}""")
            prepareTorrServerSettingsFile(directory, File(directory, "cache"), P2pSettingsUiState(cacheSize = P2pCacheSize.NONE))
            val result = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals("yes", result["Other"]!!.jsonObject["keep"]!!.jsonPrimitive.content)
            assertEquals("true", result["BitTorr"]!!.jsonObject["EnableIPv6"]!!.jsonPrimitive.content)
            assertEquals("268435456", result["BitTorr"]!!.jsonObject["CacheSize"]!!.jsonPrimitive.content)
        } finally { directory.deleteRecursively() }
    }
    @Test fun theSelectedPeerPortAcceptsBothTcpAndUdp() {
        val port = availableTorrentPeerPort()
        ServerSocket(port).use {
            DatagramSocket(null).use { udp ->
                udp.bind(InetSocketAddress(port))
                assertTrue(udp.isBound)
            }
        }
    }

    @Test fun cancellationInterruptsAHungLocalHttpRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "torrserver-http-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/torrents") { exchange ->
            entered.countDown()
            try { release.await(10, TimeUnit.SECONDS) } finally { exchange.close() }
        }
        server.start()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            val call = async {
                sendTorrServerRequest(client, HttpRequest.newBuilder(
                    URI("http://127.0.0.1:${server.address.port}/torrents"))
                    .timeout(Duration.ofSeconds(30)).GET().build())
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(2_000L) { call.cancelAndJoin() }
            assertTrue(call.isCancelled)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            client.shutdownNow()
        }
    }

    @Test fun restartingTheBundledEngineLeavesOtherProcessesAndUserFilesAlone() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = File(System.getProperty("user.dir"))
        val binary = listOf(
            File(root, "src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
            File(root, "composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe"),
        ).firstOrNull { it.isFile && it.length() > 1_000_000 } ?: error("Bundled engine missing")
        val dir = Files.createTempDirectory("nuvio-engine-recovery-test").toFile()
        val engine = TorrServerBackend(File(dir, "owned"), File(dir, "owned-cache"), binary)
        val other = TorrServerBackend(File(dir, "other"), File(dir, "other-cache"), binary)
        val settings = P2pSettingsUiState(enableUpload = false, cacheSize = P2pCacheSize.NONE)
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
        try {
            other.restart(settings)
            val otherPid = assertNotNull(other.processId)
            val settingsFile = File(dir, "owned/settings.json").apply { parentFile.mkdirs(); writeBytes(ByteArray(1223)) }
            engine.restart(settings)
            val firstBackup = settingsFile.parentFile.listFiles()!!.single { it.name.startsWith("settings-corrupt-") }
            assertContentEquals(ByteArray(1223), firstBackup.readBytes())
            val oldPid = assertNotNull(engine.processId)
            assertTrue(engine.isHealthy())
            val missing = assertFailsWith<DesktopTorrentEngineUnavailable> { engine.getTorrentStats("0".repeat(40)) }
            assertEquals(404, missing.httpStatusCode)
            assertTrue(engine.isHealthy(), "A missing torrent must not be mistaken for a dead process")
            engine.prepare(settings)
            assertEquals(oldPid, engine.processId, "Preparing an unchanged engine must reuse its process")
            engine.prepare(settings.copy(hideTorrentStats = true))
            assertEquals(oldPid, engine.processId, "UI-only settings must not restart the engine")
            val sentinel = File(dir, "owned/user-file.txt").apply { writeText("retain") }
            engine.restart(settings)
            val restartedPid = assertNotNull(engine.processId)
            assertNotEquals(oldPid, restartedPid)
            assertFalse(ProcessHandle.of(oldPid).map { it.isAlive }.orElse(false))
            assertTrue(ProcessHandle.of(otherPid).map { it.isAlive }.orElse(false))
            assertTrue(sentinel.isFile && sentinel.readText() == "retain")
            engine.stop()
            val truncated = """{"BitTorr":{ """.toByteArray()
            settingsFile.writeBytes(truncated)
            engine.restart(settings)
            val secondBackup = settingsFile.parentFile.listFiles()!!.single {
                it.name.startsWith("settings-corrupt-") && it != firstBackup
            }
            assertContentEquals(truncated, secondBackup.readBytes())
            assertContentEquals(ByteArray(1223), firstBackup.readBytes())
            assertNotEquals(restartedPid, engine.processId)
            assertFalse(ProcessHandle.of(restartedPid).map { it.isAlive }.orElse(false))
            assertTrue(ProcessHandle.of(otherPid).map { it.isAlive }.orElse(false))
            assertTrue(sentinel.isFile && sentinel.readText() == "retain")
            val baseUrl = engine.getStreamUrl("magnet:", 1).substringBefore("/stream")
            val echo = client.send(HttpRequest.newBuilder(URI("$baseUrl/echo"))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, echo.statusCode())
            println("Bundled engine version: ${echo.body()}")
            val response = client.send(HttpRequest.newBuilder(URI("$baseUrl/settings"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"action":"get"}""")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val applied = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals("268435456", applied["CacheSize"]!!.jsonPrimitive.content)
            assertEquals("false", applied["UseDisk"]!!.jsonPrimitive.content)
            assertEquals("true", applied["DisableUpload"]!!.jsonPrimitive.content)
        } finally {
            engine.stop()
            other.stop()
            client.shutdownNow()
            assertFalse(engine.isHealthy())
            dir.deleteRecursively()
        }
    }
}
