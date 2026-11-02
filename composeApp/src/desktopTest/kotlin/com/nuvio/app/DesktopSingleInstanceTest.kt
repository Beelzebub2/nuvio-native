package com.nuvio.app

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun reopeningRoutesToExistingWindowInsteadOfStartingAnotherInstance() {
        val directory = temporary.newFolder("primary").toPath()
        val instance = assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
        try {
            val latch = CountDownLatch(1)
            var received: List<String>? = null
            instance.setActivationHandler {
                received = it
                latch.countDown()
            }

            assertNull(DesktopSingleInstance.tryStart(arrayOf("stremio://detail/movie/tt12345"), directory))
            assertTrue(latch.await(3, TimeUnit.SECONDS), "The running app should receive the activation")
            assertEquals(listOf("stremio://detail/movie/tt12345"), received)
        } finally {
            instance.close()
        }
    }

    @Test
    fun repeatedLaunchBeforeWindowIsReadyIsDeliveredWhenItAppears() {
        val directory = temporary.newFolder("starting").toPath()
        val instance = assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
        try {
            assertNull(DesktopSingleInstance.tryStart(arrayOf("nuvio://open/movie/123"), directory))
            val latch = CountDownLatch(1)
            var received: List<String>? = null
            instance.setActivationHandler {
                received = it
                latch.countDown()
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("nuvio://open/movie/123"), received)
        } finally {
            instance.close()
        }
    }

    @Test
    fun activationIgnoresUnsupportedLaunchLinks() {
        val directory = temporary.newFolder("links").toPath()
        val instance = assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
        try {
            val latch = CountDownLatch(1)
            var received: List<String>? = null
            instance.setActivationHandler {
                received = it
                latch.countDown()
            }

            assertNull(
                DesktopSingleInstance.tryStart(
                    arrayOf("file:///private/data", "https://untrusted.example", "STREMIO://detail/movie/test"),
                    directory,
                )
            )
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("STREMIO://detail/movie/test"), received)
        } finally {
            instance.close()
        }
    }

    @Test
    fun untrustedLocalSocketCannotActivateWindowWithoutToken() {
        val directory = temporary.newFolder("auth").toPath()
        val instance = assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
        try {
            val port = Files.readAllLines(directory.resolve("endpoint"), StandardCharsets.UTF_8)[0].toInt()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply {
                    writeUTF("incorrect-token")
                    writeInt(0)
                    flush()
                }
            }
            val latch = CountDownLatch(1)
            var activationCount = 0
            instance.setActivationHandler {
                activationCount++
                latch.countDown()
            }
            assertNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, activationCount)
        } finally {
            instance.close()
        }
    }

    @Test
    fun closedInstanceReleasesLockForFreshLaunch() {
        val directory = temporary.newFolder("restart").toPath()
        assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory)).close()

        val restarted = assertNotNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
        restarted.close()
    }

    @Test
    fun lockWithoutResponsiveEndpointStillPreventsSecondWindow() {
        val directory = temporary.newFolder("unresponsive").toPath()
        FileChannel.open(
            directory.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                assertNull(DesktopSingleInstance.tryStart(emptyArray(), directory))
            }
        }
    }
}
