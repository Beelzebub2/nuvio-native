package com.nuvio.app.features.p2p

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Cancel obsolete starts before they can wait behind native engine work. */
internal class P2pStartRequests {
    private val lock = SynchronizedObject()
    private var latest: Job? = null

    suspend fun <T> run(start: suspend () -> T): T = coroutineScope {
        val job = coroutineContext.job
        coroutineContext.ensureActive()
        val previous = synchronized(lock) {
            val previous = latest
            latest = job
            previous
        }
        previous?.cancel(CancellationException("A newer P2P request replaced this start"))
        try {
            coroutineContext.ensureActive()
            start()
        } finally {
            synchronized(lock) { if (latest === job) latest = null }
        }
    }

    fun cancel() {
        val previous = synchronized(lock) { latest.also { latest = null } }
        previous?.cancel(CancellationException("P2P stream stopped"))
    }
}
