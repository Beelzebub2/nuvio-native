package com.nuvio.app.features.airing

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.Instant

class AiringTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private fun event(id: Int, episode: Int, at: String) = AiringEvent(id, episode, Instant.parse(at).epochSecond, "Anime $id")

    @Test fun `local midnight partitions today and history without duplicate anime`() {
        val settings = AiringSettings(zone = "Europe/Lisbon", historyDays = 2)
        val events = listOf(
            event(1, 1, "2026-10-03T22:59:00Z"), // Yesterday in Lisbon.
            event(2, 1, "2026-10-03T23:01:00Z"), // Today in Lisbon.
            event(2, 2, "2026-10-04T10:00:00Z"),
            event(2, 3, "2026-10-04T13:00:00Z"),
            event(3, 1, "2026-10-04T14:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectAiringEvents(events, settings, now, AiringWindow.Now).map { it.mediaId })
        assertEquals(2, selectAiringEvents(events, settings, now, AiringWindow.Now).first().episode)
        assertEquals(listOf(2, 2, 2, 3), selectScheduledEvents(events, settings, now.atZone(java.time.ZoneId.of(settings.zone)).toLocalDate()).map { it.mediaId })
    }

    @Test fun `DST transition uses local dates and estimated breaks stay hidden`() {
        val transition = Instant.parse("2026-10-25T02:00:00Z")
        val events = listOf(
            event(1, 1, "2026-10-24T23:30:00Z"),
            event(2, 1, "2026-10-25T00:30:00Z"),
            event(3, 1, "2026-10-25T01:30:00Z").copy(isBreak = true),
            event(4, 1, "2026-10-25T01:30:00Z").copy(estimated = true),
            event(5, 1, "2026-10-25T01:30:00Z").copy(airType = "dub"),
        )
        assertEquals(setOf(1, 2), selectAiringEvents(events, AiringSettings(includeEstimated = false), transition, AiringWindow.Now).map { it.mediaId }.toSet())
    }

    @Test fun `Tsuzuki breaks override prior scheduled episodes`() {
        val payload = Json.parseToJsonElement("""{"episodes":[
          {"mediaId":21,"episode":3,"airingAt":1791110000,"airType":"sub","title":{"english":"One Piece"},"exact":true,"note":{"kind":"break"}},
          {"mediaId":21,"episode":4,"airingAt":1791110000,"airType":"sub","title":"One Piece","exact":false},
          {"mediaId":21,"episode":5,"airingAt":1791110000,"airType":"dub","title":"One Piece","exact":true}
        ]}""").jsonObject
        val events = parseTsuzukiSchedule(payload, "sub")
        assertEquals(2, events.size)
        assertTrue(events.first().isBreak)
        assertTrue(events.last().estimated)
    }

    @Test fun `native episode IDs preserve anime numbering despite TVDB fields`() {
        val episodes = Json.parseToJsonElement("""{"2":{"title":{"en":"Second"},"seasonNumber":8,"episodeNumber":91}}""").jsonObject
        val mapping = Json.parseToJsonElement("""{"kitsu_id":12,"mal_id":21}""").jsonObject
        val aired = event(21, 2, "2026-10-04T10:00:00Z")
        val future = event(21, 3, "2026-10-04T13:00:00Z")
        val broken = event(21, 4, "2026-10-04T10:00:00Z").copy(isBreak = true)
        val meta = buildAiringDetails(aired, listOf(aired, future, broken), mapping, episodes, now)
        assertEquals("kitsu:12:2", meta.videos.single { it.episode == 2 }.id)
        assertEquals(1, meta.videos.single { it.episode == 2 }.season)
        assertFalse(meta.videos.single { it.episode == 3 }.available)
        assertFalse(meta.videos.single { it.episode == 4 }.available)
        assertEquals("kitsu:12:2", meta.defaultVideoId)
        assertEquals("nativeairing:21", meta.id)
    }

    @Test fun `refresh retains visible data then replaces corrected episodes and saves`() = runBlocking {
        val old = event(1, 1, "2026-10-04T09:00:00Z")
        val corrected = old.copy(episode = 1, airingAt = now.epochSecond - 60)
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var saved: AiringSnapshot? = null
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(old)),
            fetch = { _, _ -> started.complete(Unit); proceed.await(); listOf(corrected) },
            saveSnapshot = { saved = it }, now = { now })
        val job = launch { repository.refresh(force = true) }
        started.await()
        assertTrue(repository.state.value.loading)
        assertEquals(old, repository.state.value.snapshot.events.single())
        proceed.complete(Unit); job.join()
        assertEquals(corrected, repository.state.value.snapshot.events.single())
        assertEquals(repository.state.value.snapshot, saved)
        assertFalse(repository.state.value.loading)
    }

    @Test fun `source failure retains disk snapshot and cancellation ends loading`() = runBlocking {
        val cached = AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z")), savedAt = 1000)
        val repository = AiringRepository(AiringSettings(), cached, fetch = { _, _ -> error("Provider unavailable") }, now = { now })
        repository.refresh(force = true)
        assertEquals(cached, repository.state.value.snapshot)
        assertEquals("Provider unavailable", repository.state.value.error)
        assertFalse(repository.state.value.loading)
        val cancelled = AiringRepository(AiringSettings(), cached, fetch = { _, _ -> throw CancellationException() }, now = { now })
        try { cancelled.refresh(force = true); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(cancelled.state.value.loading)
    }

    @Test fun `source changes clear unrelated snapshot and query new provider`() = runBlocking {
        var requested: AiringSettings? = null
        var persisted: AiringSettings? = null
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z"))),
            fetch = { settings, _ -> requested = settings; emptyList() }, saveSettings = { persisted = it }, now = { now })
        val settings = AiringSettings(airType = "dub", historyDays = 7)
        repository.updateSettings(settings)
        assertEquals(settings, requested)
        assertEquals(settings, persisted)
        assertTrue(repository.state.value.snapshot.events.isEmpty())
        assertEquals("dub", repository.state.value.snapshot.airType)
    }

    @Test fun `live gateway schedule and anime metadata smoke`() = runBlocking {
        assumeTrue(System.getProperty("nuvio.airingSmoke") == "true")
        val client = AiringClient()
        val events = client.schedule(AiringSettings(), Instant.now())
        assertTrue("Live schedule should contain episodes", events.isNotEmpty())
        val meta = client.details(21, events, AiringSettings())
        assertNotNull(meta)
        assertTrue("Exact mapping should support Kitsu addon streams", meta!!.videos.any { it.id.startsWith("kitsu:12:") })
    }
}
