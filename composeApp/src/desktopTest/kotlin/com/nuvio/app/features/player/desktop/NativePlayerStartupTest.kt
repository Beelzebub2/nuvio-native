package com.nuvio.app.features.player.desktop

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativePlayerStartupTest {
    @Test(timeout = 3000) fun `native bridge first touch happens off the launch thread`() {
        val caller = Thread.currentThread().name
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var loaderThread: String? = null
        val started = System.nanoTime()
        val worker = preloadNativePlayerBridgeAsync {
            loaderThread = Thread.currentThread().name
            entered.countDown()
            finish.await()
        }
        try {
            assertNotNull(worker)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNotEquals(caller, loaderThread)
            assertTrue("DLL loading must not hold up launch", worker!!.isAlive)
            println("Native preload returned without awaiting the blocked initializer in ${(System.nanoTime() - started) / 1_000_000}ms")
        } finally {
            finish.countDown()
            worker?.join(1000)
        }
    }
}
