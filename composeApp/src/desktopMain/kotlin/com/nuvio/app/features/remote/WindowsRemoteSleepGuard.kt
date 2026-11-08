package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.loadSystemLibrary
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Prevent automatic Windows system sleep while an authenticated phone is
 * controlling this PC. The display may still turn off; explicit user sleep,
 * lid-close, shutdown and thermal/battery policy still take precedence.
 *
 * SetThreadExecutionState is thread-scoped. Both setting and clearing the
 * request MUST happen on the same dedicated thread, not the Compose UI thread.
 */
internal class WindowsRemoteSleepGuard(
    private val powerOnDisplay: () -> Unit = { WindowsRemoteDisplayWake.powerOn(); Unit },
    private val setState: (Int) -> Int = { flags -> kernel32.SetThreadExecutionState(flags) },
) : RemoteConnectionLifetime {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
    }

    companion object {
        internal const val ES_CONTINUOUS: Int = Int.MIN_VALUE
        internal const val ES_SYSTEM_REQUIRED: Int = 0x00000001
        internal const val ES_DISPLAY_REQUIRED: Int = 0x00000002
        private const val DISPLAY_WAKE_THROTTLE_NS = 1_000_000_000L
        private val kernel32 by lazy { loadSystemLibrary("kernel32", Kernel32::class.java) }
    }

    private val lock = Any()
    private var release: CountDownLatch? = null
    private val lastDisplayWakeNanos = AtomicLong(0L)

    override fun authenticated(host: Boolean) {
        if (!host) return
        synchronized(lock) {
            if (release != null) return
            val signal = CountDownLatch(1)
            val worker = Thread({
                var acquired = false
                try {
                    acquired = setState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED) != 0
                    if (acquired) signal.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    if (acquired) runCatching { setState(ES_CONTINUOUS) }
                }
            }, "starlit-remote-system-awake").apply { isDaemon = true }
            release = signal
            try {
                worker.start()
            } catch (error: Exception) {
                release = null
                throw error
            }
        }
    }

    override fun disconnected() {
        val signal = synchronized(lock) {
            release.also { release = null }
        }
        lastDisplayWakeNanos.set(0L)
        // Never block the UI or authentication coroutine while Windows clears
        // the execution request on its owning worker thread.
        signal?.countDown()
    }

    override fun playbackRequested() {
        // Pairing, periodic state snapshots and playback selection that has
        // not been accepted never reach here. Do not make the display stay on
        // merely because an authenticated phone is connected.
        val session = synchronized(lock) { release } ?: return
        val now = System.nanoTime()
        val previous = lastDisplayWakeNanos.get()
        if (previous != 0L && now - previous in 0 until DISPLAY_WAKE_THROTTLE_NS) return
        if (!lastDisplayWakeNanos.compareAndSet(previous, now)) return
        Thread({
            // The job may have been queued just before sign-out/disconnect.
            if (synchronized(lock) { release === session }) {
                runCatching {
                    // A one-shot reset of the display idle timer, without
                    // ES_CONTINUOUS: preserve normal screen timeout afterward.
                    setState(ES_DISPLAY_REQUIRED)
                    // A monitor already in DPMS off may also need an explicit
                    // power-on command. Do not inject fake mouse/keyboard input.
                    powerOnDisplay()
                }
            }
        }, "starlit-remote-display-wake").apply { isDaemon = true }.start()
    }
}
