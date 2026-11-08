package com.nuvio.app.features.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRemoteSleepGuardTest {
    @Test
    fun authorizedRemotePlaybackRequestsOneShotMonitorWakeWithoutKeepingDisplayAlwaysOn() {
        val systemAwake = CountDownLatch(1)
        val monitorWake = CountDownLatch(1)
        val released = CountDownLatch(1)
        val wakeCount = AtomicInteger(0)
        val oneShotCount = AtomicInteger(0)
        val callerThread = Thread.currentThread()
        val guard = WindowsRemoteSleepGuard(
            powerOnDisplay = {
                wakeCount.incrementAndGet()
                monitorWake.countDown()
            },
            setState = { flags ->
                when (flags) {
                    WindowsRemoteSleepGuard.ES_CONTINUOUS or WindowsRemoteSleepGuard.ES_SYSTEM_REQUIRED ->
                        systemAwake.countDown()
                    WindowsRemoteSleepGuard.ES_DISPLAY_REQUIRED -> {
                        oneShotCount.incrementAndGet()
                        assertFalse(Thread.currentThread() === callerThread,
                            "Display timer reset must be asynchronous")
                    }
                    WindowsRemoteSleepGuard.ES_CONTINUOUS -> released.countDown()
                    else -> throw AssertionError("Unexpected Windows execution flags: $flags")
                }
                1
            },
        )
        guard.playbackRequested()
        assertEquals(0, wakeCount.get(), "Unauthenticated requests must not light the PC display")
        guard.authenticated(host = false)
        guard.playbackRequested()
        assertEquals(0, wakeCount.get(), "A client-side remote must never wake the local display")
        guard.authenticated(host = true)
        assertTrue(systemAwake.await(3, TimeUnit.SECONDS))
        assertEquals(0, oneShotCount.get(), "Pairing/connection alone must leave the display timer untouched")
        guard.playbackRequested()
        assertTrue(monitorWake.await(3, TimeUnit.SECONDS))
        assertEquals(1, oneShotCount.get())
        guard.playbackRequested()
        assertEquals(1, wakeCount.get(), "Repeated playback events must be throttled")
        guard.disconnected()
        assertTrue(released.await(3, TimeUnit.SECONDS))
        guard.playbackRequested()
        assertEquals(1, wakeCount.get(), "Disconnected phones cannot wake the display")
    }
    @Test
    fun preventsOnlySystemSleepDuringAnAuthenticatedDesktopSessionAndReleasesIt() {
        val enabled = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        val calls = AtomicInteger()
        val owningThread = arrayOfNulls<Thread>(1)
        val guard = WindowsRemoteSleepGuard { flags ->
            calls.incrementAndGet()
            when (flags) {
                WindowsRemoteSleepGuard.ES_CONTINUOUS or WindowsRemoteSleepGuard.ES_SYSTEM_REQUIRED -> {
                    owningThread[0] = Thread.currentThread()
                    enabled.countDown()
                }
                WindowsRemoteSleepGuard.ES_CONTINUOUS -> {
                    assertEquals(owningThread[0], Thread.currentThread())
                    disabled.countDown()
                }
            }
            1
        }
        guard.authenticated(host = false)
        assertEquals(0, calls.get(), "A phone must never change its Windows power state")
        guard.authenticated(host = true)
        assertTrue(enabled.await(3, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        guard.authenticated(host = true)
        assertEquals(1, calls.get(), "A second remote state update cannot add a wake request")
        guard.disconnected()
        assertTrue(disabled.await(3, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
        assertFalse(owningThread[0] === Thread.currentThread(), "JNA must run off the UI/test thread")
    }

    @Test
    fun reconnectsAfterThePreviousRequestWasReleased() {
        val acquired = CountDownLatch(2)
        val released = CountDownLatch(2)
        val guard = WindowsRemoteSleepGuard { flags ->
            if (flags == WindowsRemoteSleepGuard.ES_CONTINUOUS) released.countDown()
            else acquired.countDown()
            1
        }
        try {
            guard.authenticated(host = true)
            assertTrue(acquired.await(3, TimeUnit.SECONDS).not(),
                "The second request should not have started before the first disconnect")
        } finally {
            guard.disconnected()
        }
        // A separate handshake can request a new wake lease, even if the old
        // worker is still unwinding on another thread.
        guard.authenticated(host = true)
        assertTrue(acquired.await(3, TimeUnit.SECONDS))
        guard.disconnected()
        assertTrue(released.await(3, TimeUnit.SECONDS))
    }
}
