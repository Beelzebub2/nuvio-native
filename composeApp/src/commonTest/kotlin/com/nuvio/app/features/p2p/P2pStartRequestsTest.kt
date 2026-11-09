package com.nuvio.app.features.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class P2pStartRequestsTest {
    @Test fun replacementCancelsActiveAndQueuedStartsBeforeNativeWork() = runTest {
        val starts = P2pStartRequests()
        val nativeMutex = Mutex()
        val cleanup = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val first = async {
            starts.run {
                nativeMutex.withLock {
                    entered += 1
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.await() } }
                }
            }
        }
        runCurrent()
        val obsolete = async { starts.run { nativeMutex.withLock { entered += 2 } } }
        runCurrent()
        val latest = async { starts.run { nativeMutex.withLock { entered += 3 } } }
        runCurrent()
        cleanup.complete(Unit)
        latest.await()
        first.join()
        obsolete.join()
        assertTrue(first.isCancelled)
        assertTrue(obsolete.isCancelled)
        assertEquals(listOf(1, 3), entered)
    }

    @Test fun stopRevokesARequestStillWaitingForTheEngine() = runTest {
        val starts = P2pStartRequests()
        val nativeMutex = Mutex(locked = true)
        var nativeCalls = 0
        val queued = async { starts.run { nativeMutex.withLock { nativeCalls++ } } }
        runCurrent()
        starts.cancel()
        nativeMutex.unlock()
        queued.join()
        assertTrue(queued.isCancelled)
        assertEquals(0, nativeCalls)
        assertEquals("next", starts.run { "next" })
    }

    @Test fun obsoleteCleanupCannotForgetTheLatestStart() = runTest {
        val starts = P2pStartRequests()
        val finishCleanup = CompletableDeferred<Unit>()
        val first = async {
            starts.run {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { finishCleanup.await() } }
            }
        }
        runCurrent()
        val latest = async { starts.run { awaitCancellation() } }
        runCurrent()
        finishCleanup.complete(Unit)
        first.join()
        starts.cancel()
        latest.join()
        assertTrue(latest.isCancelled, "The old finally block must not erase the new cancellation handle")
    }
}
