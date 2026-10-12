package com.nuvio.app.features.myanimelist

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MalCredentialLifecycleTest {
    @Test fun canceledOrReplacedAccountCannotCommitWhileEncryptionIsBlocking(): Unit = runBlocking {
        for (cancel in listOf(true, false)) {
            val lock = Any()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var current = true
            var saved = false
            val job = launch(Dispatchers.Default) {
                try {
                    commitProtectedMalCredential("fake-test-token", lock, { current }, { saved = true }, {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        "protected-test-value"
                    })
                } catch (e: CancellationException) { throw e }
                catch (_: IllegalStateException) { /* A replaced account must reject the commit. */ }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                synchronized(lock) { if (cancel) job.cancel() else current = false }
            } finally { release.countDown() }
            job.join()
            assertFalse(saved, "Canceled or replaced sign-in must never persist credentials")
        }
    }
}
