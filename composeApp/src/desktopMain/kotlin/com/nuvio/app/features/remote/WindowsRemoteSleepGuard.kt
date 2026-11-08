package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.loadSystemLibrary
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch

/**
 * Prevent automatic Windows system sleep while an authenticated phone is
 * controlling this PC. The display may still turn off; explicit user sleep,
 * lid-close, shutdown and thermal/battery policy still take precedence.
 *
 * SetThreadExecutionState is thread-scoped. Both setting and clearing the
 * request MUST happen on the same dedicated thread, not the Compose UI thread.
 */
internal class WindowsRemoteSleepGuard(
    private val setState: (Int) -> Int = { flags -> kernel32.SetThreadExecutionState(flags) },
) : RemoteConnectionLifetime {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
    }

    companion object {
        internal const val ES_CONTINUOUS: Int = Int.MIN_VALUE
        internal const val ES_SYSTEM_REQUIRED: Int = 0x00000001
        private val kernel32 by lazy { loadSystemLibrary("kernel32", Kernel32::class.java) }
    }

    private val lock = Any()
    private var release: CountDownLatch? = null

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
        // Never block the UI or authentication coroutine while Windows clears
        // the execution request on its owning worker thread.
        signal?.countDown()
    }
}
