package com.nuvio.app.features.airing

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AiringProgressTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private fun event(id: Int) = AiringEvent(id, 1, now.minusSeconds(3600).epochSecond, "Anime $id", isAdult = false)

    @Test fun `first safe batch is visible and controls work while the week is loading`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val safe = event(1)
        val ecchi = event(2).copy(genres = listOf("Ecchi"))
        val tagged = event(3).copy(hasAdultTags = true)
        val saved = mutableListOf<AiringSnapshot>()
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ -> error("Must use progressive fetch") },
            fetchUpdates = { _, _, emit ->
                emit(listOf(safe, ecchi, tagged, ecchi.copy(episode = 2, genres = emptyList())))
                arrived.complete(Unit)
                finish.await()
                listOf(safe, ecchi, tagged, event(4))
            }, saveSnapshot = { saved += it }, now = { now })
        val refresh = launch { repository.refresh() }
        arrived.await()
        assertEquals(listOf(1), repository.state.value.snapshot.events.map { it.mediaId })
        assertTrue(repository.state.value.loading)
        assertFalse(repository.state.value.snapshot.complete)
        assertFalse(saved.single().complete)
        withTimeout(1000) {
            repository.hide(safe)
            repository.updateSettings(repository.state.value.settings.copy(historyDays = 5))
        }
        assertTrue(repository.state.value.loading)
        assertTrue(selectAiringEvents(repository.state.value.snapshot.events, repository.state.value.settings, now, AiringWindow.Now).isEmpty())
        finish.complete(Unit)
        refresh.join()
        assertTrue(repository.state.value.snapshot.complete)
        assertFalse(repository.state.value.loading)
        assertEquals(setOf(1, 4), repository.state.value.snapshot.events.map { it.mediaId }.toSet())
        assertEquals(listOf(1), repository.state.value.settings.hidden.map { it.mediaId })
        assertEquals(5, repository.state.value.settings.historyDays)
    }

    @Test fun `partial cache survives a backfill error and does not suppress retry as fresh`() = runBlocking {
        var saved: AiringSnapshot? = null
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ -> emptyList() },
            fetchUpdates = { _, _, emit -> emit(listOf(event(1))); error("Future pages unavailable") },
            saveSnapshot = { saved = it }, now = { now })
        repository.refresh()
        assertEquals(listOf(1), repository.state.value.snapshot.events.map { it.mediaId })
        assertFalse(repository.state.value.snapshot.complete)
        assertEquals("Future pages unavailable", repository.state.value.error)
        var fetches = 0
        val restored = AiringRepository(AiringSettings(), saved!!,
            fetch = { _, _ -> fetches++; listOf(event(1), event(2)) }, now = { now.plusSeconds(90) })
        restored.refresh()
        assertEquals(1, fetches)
        assertTrue(restored.state.value.snapshot.complete)
    }

    @Test fun `source policy changes cancel old backfill and reject late partial results`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val adult = event(2).copy(isAdult = true)
        var rejected = false
        lateinit var repository: AiringRepository
        repository = AiringRepository(AiringSettings(hideNsfw = false), fetch = { _, _ -> emptyList() },
            fetchUpdates = { settings, _, emit ->
                if (!settings.hideNsfw) {
                    emit(listOf(adult)); arrived.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            emit(listOf(adult))
                            rejected = repository.state.value.snapshot.events.none { it.isAdult == true }
                        }
                    }
                }
                listOf(event(1), adult)
            }, now = { now })
        val refresh = launch { repository.refresh() }
        arrived.await()
        withTimeout(1500) { repository.updateSettings(AiringSettings()) }
        refresh.join()
        assertTrue(rejected)
        assertTrue(repository.state.value.settings.hideNsfw)
        assertEquals(listOf(1), repository.state.value.snapshot.events.map { it.mediaId })
    }

    @Test fun `cache restoration is deferred off the calling thread and fresh data avoids network`() = runBlocking {
        val caller = Thread.currentThread().name
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var loads = 0
        val cached = AiringSnapshot(listOf(event(1)), now.toEpochMilli(), hideNsfw = true,
            contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION)
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ -> error("Fresh cache must not refetch") },
            loadSaved = {
                loads++
                assertNotEquals(caller, Thread.currentThread().name)
                entered.complete(Unit); finish.await()
                AiringSettings(historyDays = 5) to cached
            }, now = { now })
        assertEquals(0, loads)
        assertTrue(repository.state.value.snapshot.events.isEmpty())
        val refresh = launch { repository.refresh() }
        entered.await()
        assertTrue(repository.state.value.loading)
        finish.complete(Unit); refresh.join()
        assertEquals(listOf(1), repository.state.value.snapshot.events.map { it.mediaId })
        assertEquals(5, repository.state.value.settings.historyDays)
        assertFalse(repository.state.value.loading)
    }
}
