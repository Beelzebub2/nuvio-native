package com.nuvio.app.features.p2p

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
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

class TorrServerRecoveryIntegrationTest {
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
        try {
            other.restart(settings)
            val otherPid = assertNotNull(other.processId)
            engine.restart(settings)
            val oldPid = assertNotNull(engine.processId)
            val sentinel = File(dir, "owned/user-file.txt").apply { writeText("retain") }
            engine.restart(settings)
            assertNotEquals(oldPid, engine.processId)
            assertFalse(ProcessHandle.of(oldPid).map { it.isAlive }.orElse(false))
            assertTrue(ProcessHandle.of(otherPid).map { it.isAlive }.orElse(false))
            assertTrue(sentinel.isFile && sentinel.readText() == "retain")
        } finally {
            engine.stop()
            other.stop()
            dir.deleteRecursively()
        }
    }
}
