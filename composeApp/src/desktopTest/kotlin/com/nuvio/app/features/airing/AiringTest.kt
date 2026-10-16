package com.nuvio.app.features.airing

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.Instant

class AiringTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private fun event(id: Int, episode: Int, at: String) =
        AiringEvent(id, episode, Instant.parse(at).epochSecond, "Anime $id", isAdult = false)

    @Test fun `new and legacy settings hide adult anime by default and explicit opt-in persists`() {
        assertTrue(AiringSettings().hideNsfw)
        val legacy = Json.decodeFromString<AiringSettings>("""{"zone":"UTC","airType":"broadcast"}""")
        assertTrue(legacy.hideNsfw)
        val optIn = AiringSettings(hideNsfw = false)
        assertFalse(Json.decodeFromString<AiringSettings>(Json.encodeToString(optIn)).hideNsfw)
    }

    @Test fun `all airing selectors enforce adult flags without title guessing`() {
        val normal = event(1, 1, "2026-10-04T10:00:00Z").copy(name = "Normal Romance")
        val adult = event(2, 1, "2026-10-04T10:00:00Z").copy(isAdult = true, name = "Quiet Meadow")
        val hentai = event(3, 1, "2026-10-04T10:00:00Z").copy(genres = listOf("hEnTaI"))
        val unknown = event(4, 1, "2026-10-04T10:00:00Z").copy(isAdult = null)
        val conflicting = event(2, 2, "2026-10-04T11:00:00Z")
        val events = listOf(normal, adult, hentai, unknown, conflicting)
        val hidden = AiringSettings()
        val day = now.atZone(java.time.ZoneId.of(hidden.zone)).toLocalDate()
        assertEquals(listOf(1), selectAiringEvents(events, hidden, now, AiringWindow.Now).map { it.mediaId })
        assertEquals(listOf(1), selectScheduledEvents(events, hidden, day).map { it.mediaId })
        val enabled = hidden.copy(hideNsfw = false)
        assertEquals(setOf(1, 2, 3, 4), selectAiringEvents(events, enabled, now, AiringWindow.Now).map { it.mediaId }.toSet())
        assertEquals(setOf(1, 2, 3, 4), selectScheduledEvents(events, enabled, day).map { it.mediaId }.toSet())
    }

    @Test fun `fresh adult evidence blocks retained safe episodes for same anime`() = runBlocking {
        val previousSafe = event(31, 1, "2026-10-04T09:00:00Z")
        val freshAdult = event(31, 2, "2026-10-04T10:00:00Z").copy(isAdult = true)
        val otherSafe = event(32, 1, "2026-10-04T10:00:00Z")
        val repository = AiringRepository(AiringSettings(),
            AiringSnapshot(listOf(previousSafe), savedAt = 1, hideNsfw = true),
            fetch = { _, _ -> listOf(freshAdult, otherSafe) }, now = { now })
        repository.refresh(force = true)
        assertEquals(listOf(32), repository.state.value.snapshot.events.map { it.mediaId })
    }

    @Test fun `AniList isAdult metadata is parsed and safe classification is explicit`() {
        val page = Json.parseToJsonElement("""{"airingSchedules":[
          {"episode":1,"airingAt":1791110000,"media":{"id":12,"title":{"english":"Test"},"isAdult":true}},
          {"episode":1,"airingAt":1791110000,"media":{"id":13,"title":{"english":"Safe"},"isAdult":false}},
          {"episode":1,"airingAt":1791110000,"media":{"id":14,"title":{"english":"Unrated"}}}
        ]}""").jsonObject
        val parsed = parseAniListSchedule(page)
        assertEquals(listOf(true, false, null), parsed.map { it.isAdult })
        assertEquals(listOf(13), filterAiringContent(parsed, AiringSettings()).map { it.mediaId })
        assertFalse(hasClassifiedAniListMedia(page))
        val safePage = Json.parseToJsonElement("""{"airingSchedules":[
          {"episode":1,"airingAt":1791110000,"media":null},
          {"episode":1,"airingAt":1791110000,"media":{"id":13,"isAdult":false}},
          {"episode":null,"airingAt":1791110000,"media":{"id":12}}
        ]}""").jsonObject
        assertTrue(hasClassifiedAniListMedia(safePage))
    }

    @Test fun `Tsuzuki routing preserves explicit adult opt-in across gateway fallback`() {
        val date = java.time.LocalDate.parse("2026-10-15")
        val hidden = tsuzukiScheduleRequest(date, 3, AiringSettings(airType = "sub"))
        assertEquals("/release-schedule?start=2026-10-15&days=3&airType=sub", hidden.gatewayPath)
        assertFalse(hidden.directUrl.contains("includeAdult"))
        val included = tsuzukiScheduleRequest(date, 3, AiringSettings(airType = "dub", hideNsfw = false))
        assertEquals("/release-schedule-adult?start=2026-10-15&days=3&airType=dub", included.gatewayPath)
        assertTrue(included.directUrl.endsWith("&includeAdult=1"))
    }

    @Test fun `Tsuzuki default adult exclusion marks safe items and does not override explicit adult flags`() {
        val payload = Json.parseToJsonElement("""{"episodes":[
          {"mediaId":21,"episode":1,"airingAt":1791110000,"airType":"sub","title":"Safe","exact":true},
          {"mediaId":22,"episode":1,"airingAt":1791110000,"airType":"sub","title":"Flagged","exact":true,"isAdult":true}
        ]}""").jsonObject
        assertNull(parseTsuzukiSchedule(payload, "sub").first().isAdult)
        val defaultFeed = parseTsuzukiSchedule(payload, "sub", sourceExcludesAdult = true)
        assertEquals(listOf(false, true), defaultFeed.map { it.isAdult })
        assertEquals(listOf(21), filterAiringContent(defaultFeed, AiringSettings()).map { it.mediaId })
    }

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
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(old), hideNsfw = true),
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
        val cached = AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z")), savedAt = 1000, hideNsfw = true)
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
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z")), hideNsfw = true),
            fetch = { settings, _ -> requested = settings; emptyList() }, saveSettings = { persisted = it }, now = { now })
        val settings = AiringSettings(airType = "dub", historyDays = 7)
        repository.updateSettings(settings)
        assertEquals(settings, requested)
        assertEquals(settings, persisted)
        assertTrue(repository.state.value.snapshot.events.isEmpty())
        assertEquals("dub", repository.state.value.snapshot.airType)
    }

    @Test fun `legacy cache and a different NSFW policy never reuse unclassified snapshots`() {
        val cached = AiringSnapshot(listOf(event(9, 1, "2026-10-04T09:00:00Z")), savedAt = now.toEpochMilli())
        val old = AiringRepository(AiringSettings(), cached, fetch = { _, _ -> emptyList() }, now = { now })
        assertTrue(old.state.value.snapshot.events.isEmpty())
        val allowed = cached.copy(hideNsfw = false)
        val hidden = AiringRepository(AiringSettings(), allowed, fetch = { _, _ -> emptyList() }, now = { now })
        assertTrue(hidden.state.value.snapshot.events.isEmpty())
    }

    @Test fun `NSFW setting switch clears the previous feed before fetching replacement`() = runBlocking {
        val adult = event(5, 1, "2026-10-04T09:00:00Z").copy(isAdult = true)
        val old = AiringSnapshot(listOf(adult), now.toEpochMilli(), hideNsfw = false)
        val fetching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved: AiringSettings? = null
        val repository = AiringRepository(AiringSettings(hideNsfw = false), old,
            fetch = { _, _ -> fetching.complete(Unit); release.await(); listOf(adult) },
            saveSettings = { saved = it }, now = { now })
        val change = launch { repository.updateSettings(AiringSettings(hideNsfw = true)) }
        fetching.await()
        assertTrue(repository.state.value.settings.hideNsfw)
        assertTrue(repository.state.value.snapshot.events.isEmpty())
        assertEquals(true, saved?.hideNsfw)
        release.complete(Unit)
        change.join()
        assertTrue(repository.state.value.snapshot.events.isEmpty())
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
