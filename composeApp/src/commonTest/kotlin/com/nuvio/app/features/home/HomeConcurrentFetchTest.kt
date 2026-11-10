package com.nuvio.app.features.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeConcurrentFetchTest {
    @Test
    fun aStalledRowCannotBlockCompletedRows() = runBlocking {
        val slowRelease = CompletableDeferred<Unit>()
        val healthyPublished = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        val job = launch {
            fetchHomeRowsIndependently(
                requests = listOf(0, 1, 2, 3, 4),
                maxConcurrent = 2,
                timeoutMs = 10_000L,
                fetch = { request ->
                    if (request == 0) slowRelease.await()
                    request
                },
            ) { request, result ->
                published.add(result.getOrThrow())
                if (request == 4) healthyPublished.complete(Unit)
            }
        }
        withTimeout(5_000L) { healthyPublished.await() }
        assertTrue(0 !in published)
        assertEquals(setOf(1, 2, 3, 4), published.toSet())
        slowRelease.complete(Unit)
        job.join()
        assertEquals(5, published.size)
    }

    @Test
    fun aTimedOutRowReportsAnErrorWithoutBlockingHealthyRows() = runBlocking {
        val completed = mutableMapOf<Int, Result<Int>>()
        fetchHomeRowsIndependently(
            requests = listOf(0, 1),
            maxConcurrent = 2,
            timeoutMs = 250L,
            fetch = { request ->
                if (request == 0) CompletableDeferred<Unit>().await()
                request
            },
        ) { request, result -> completed[request] = result }
        assertTrue(completed[0]?.isFailure == true)
        assertEquals(1, completed[1]?.getOrThrow())
    }

    @Test
    fun parentCancellationStopsPendingRequests() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            fetchHomeRowsIndependently(
                requests = listOf(0, 1, 2),
                maxConcurrent = 1,
                timeoutMs = 100_000L,
                fetch = {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    it
                },
            ) { _, _ -> }
        }
        started.await()
        job.cancel()
        withTimeout(3_000L) { job.join() }
        assertTrue(job.isCancelled)
    }
}
