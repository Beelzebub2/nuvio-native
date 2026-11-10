package com.nuvio.app.features.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Keep network work bounded, but process each completion immediately on one
 * coroutine so UI state, row ordering and the cache are updated serially.
 */
internal suspend fun <T, R> fetchHomeRowsIndependently(
    requests: List<T>,
    maxConcurrent: Int,
    timeoutMs: Long,
    fetch: suspend (T) -> R,
    onCompleted: (T, Result<R>) -> Unit,
) = coroutineScope {
    require(maxConcurrent > 0 && timeoutMs > 0L)
    val semaphore = Semaphore(maxConcurrent)
    val results = Channel<Pair<T, Result<R>>>(Channel.UNLIMITED)
    requests.forEach { request ->
        launch {
            val result = semaphore.withPermit {
                runCatching { withTimeout(timeoutMs) { fetch(request) } }
                    .onFailure { if (it is CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }
            }
            results.send(request to result)
        }
    }
    repeat(requests.size) {
        val (request, result) = results.receive()
        onCompleted(request, result)
    }
    results.close()
}
