package com.nuvio.app.features.watching.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressSnapshotPagingTest {
    @Test
    fun `progress snapshots collect more than one thousand records in bounded pages`() = runBlocking {
        val requestedLimits = mutableListOf<Int>()
        val keys = (0 until 1001).map { it.toString().padStart(4, '0') }
        val result = collectProgressSnapshotPages(pageSize = 200) { afterKey, limit ->
            requestedLimits += limit
            val first = afterKey?.let { keys.indexOf(it) + 1 } ?: 0
            val page = keys.drop(first).take(limit)
            ProgressSnapshotPage(
                items = page,
                nextCursor = page.lastOrNull()?.takeIf { first + page.size < keys.size },
            )
        }
        assertEquals(keys, result)
        assertEquals(6, requestedLimits.size)
        assertTrue(requestedLimits.all { it == 200 })
    }

    @Test
    fun `empty snapshots stop after one request`() = runBlocking {
        var calls = 0
        val result = collectProgressSnapshotPages<String>(pageSize = 200) { after, _ ->
            assertEquals(null, after)
            calls++
            ProgressSnapshotPage(items = emptyList(), nextCursor = null)
        }
        assertTrue(result.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `repeated cursors and invalid responses cannot cause endless pulls`() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectProgressSnapshotPages(pageSize = 200) { _, _ ->
                    ProgressSnapshotPage(items = listOf("a"), nextCursor = "repeated")
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectProgressSnapshotPages(pageSize = 2) { _, _ ->
                    ProgressSnapshotPage(items = listOf("a", "b", "c"), nextCursor = null)
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                collectProgressSnapshotPages<String>(pageSize = 0) { _, _ ->
                    ProgressSnapshotPage(emptyList(), null)
                }
            }
        }
    }

    @Test
    fun `only a missing paged RPC enables legacy fallback`() {
        assertTrue(isMissingProgressSnapshotPageRpc(IllegalStateException("PGRST202: function missing")))
        assertTrue(isMissingProgressSnapshotPageRpc(
            IllegalStateException("Could not find the function public.sync_pull_watch_progress_page"),
        ))
        assertFalse(isMissingProgressSnapshotPageRpc(IllegalStateException("401 Unauthorized")))
        assertFalse(isMissingProgressSnapshotPageRpc(IllegalStateException("Connection reset")))
        assertFalse(isMissingProgressSnapshotPageRpc(IllegalStateException("Invalid progress pagination")))
    }
}
