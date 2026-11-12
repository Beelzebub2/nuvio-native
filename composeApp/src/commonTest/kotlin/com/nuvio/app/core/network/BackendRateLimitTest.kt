package com.nuvio.app.core.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendRateLimitTest {
    @Test
    fun `retry after supports seconds and HTTP dates`() {
        assertEquals(30_000L, retryAfterDelayMillis("30", nowEpochMs = 0L))
        assertEquals(
            10_000L,
            retryAfterDelayMillis(
                retryAfterHeader = "Wed, 21 Oct 2015 07:28:00 GMT",
                nowEpochMs = 1_445_412_470_000L,
            ),
        )
        assertEquals(1_000L, retryAfterDelayMillis("invalid", nowEpochMs = 0L))
    }

    @Test
    fun `server supplied cooldown is capped for long seconds HTTP dates and numeric overflow`() {
        assertEquals(300_000L, retryAfterDelayMillis("3600", nowEpochMs = 0L))
        assertEquals(300_000L, retryAfterDelayMillis(Long.MAX_VALUE.toString(), nowEpochMs = 0L))
        assertEquals(300_000L, retryAfterDelayMillis("999999999999999999999999", nowEpochMs = 0L))
        assertEquals(0L, retryAfterDelayMillis("00000000000000000000000", nowEpochMs = 0L))
        assertEquals(30_000L, retryAfterDelayMillis("00000000000000000000030", nowEpochMs = 0L))
        assertEquals(
            300_000L,
            retryAfterDelayMillis("Wed, 21 Oct 2015 07:28:00 GMT", nowEpochMs = 0L),
        )
        assertEquals(0L, retryAfterDelayMillis("Wed, 21 Oct 2015 07:28:00 GMT", nowEpochMs = 1_445_412_480_000L))
    }

    @Test
    fun `retry delay uses bounded fallback and adds jitter`() {
        assertEquals(1_250L, backendRetryDelayMillis(0, null, nowEpochMs = 0L, jitterMs = 250L))
        assertEquals(30_500L, backendRetryDelayMillis(10, null, nowEpochMs = 0L, jitterMs = 500L))
        assertEquals(60_250L, backendRetryDelayMillis(0, "60", nowEpochMs = 0L, jitterMs = 250L))
        assertEquals(300_000L, backendRetryDelayMillis(0, "3600", nowEpochMs = 0L, jitterMs = 1_000L))
    }

    @Test
    fun `automatic retries are limited to reads`() {
        assertTrue(isSafeBackendRetryRequest("GET", "/rest/v1/plugins"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_pull_library_delta"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/get_my_member_access"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_push_watch_progress"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/verify_profile_pin"))
        assertFalse(isSafeBackendRetryRequest("DELETE", "/rest/v1/library"))
    }

    @Test
    fun `cooldown uses retry after for service unavailable responses`() {
        assertTrue(shouldApplyBackendCooldown(429, null))
        assertTrue(shouldApplyBackendCooldown(503, "10"))
        assertFalse(shouldApplyBackendCooldown(503, null))
        assertFalse(shouldApplyBackendCooldown(500, "10"))
    }

    @Test
    fun `coordinator delays requests until the longest cooldown expires`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val coordinator = BackendRateLimitCoordinator(
            currentTimeMillis = { now },
            pause = { waitMs ->
                waits += waitMs
                now += waitMs
            },
        )

        coordinator.record("2")
        coordinator.record("5")
        coordinator.record("1")
        coordinator.awaitPermission()

        assertEquals(listOf(5_000L), waits)
    }

    @Test
    fun `coordinator never pauses longer than maximum cooldown from a single response`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val coordinator = BackendRateLimitCoordinator(
            currentTimeMillis = { now },
            pause = { duration -> waits += duration; now += duration },
        )

        coordinator.record("999999999999999999999999")
        coordinator.awaitPermission()

        assertEquals(listOf(300_000L), waits)
    }
}
