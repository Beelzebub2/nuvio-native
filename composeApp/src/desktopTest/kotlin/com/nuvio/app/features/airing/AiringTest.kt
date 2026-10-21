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
            AiringSnapshot(listOf(previousSafe), savedAt = 1, hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION),
            fetch = { _, _ -> listOf(freshAdult, otherSafe) }, now = { now })
        repository.refresh(force = true)
        assertEquals(listOf(32), repository.state.value.snapshot.events.map { it.mediaId })
    }

    @Test fun `AniList isAdult metadata is parsed and safe classification is explicit`() {
        val page = Json.parseToJsonElement("""{"airingSchedules":[
          {"episode":1,"airingAt":1791110000,"media":{"id":12,"title":{"english":"Test"},"isAdult":true,"genres":[],"tags":[]}},
          {"episode":1,"airingAt":1791110000,"media":{"id":13,"title":{"english":"Safe"},"isAdult":false,"genres":[],"tags":[]}},
          {"episode":1,"airingAt":1791110000,"media":{"id":14,"title":{"english":"Unrated"}}}
        ]}""").jsonObject
        val parsed = parseAniListSchedule(page)
        assertEquals(listOf(true, false, null), parsed.map { it.isAdult })
        assertEquals(listOf(13), filterAiringContent(parsed, AiringSettings()).map { it.mediaId })
        assertFalse(hasClassifiedAniListMedia(page))
        val safePage = Json.parseToJsonElement("""{"airingSchedules":[
          {"episode":1,"airingAt":1791110000,"media":null},
          {"episode":1,"airingAt":1791110000,"media":{"id":13,"isAdult":false,"genres":[],"tags":[]}},
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

    @Test fun `Tsuzuki adult exclusion alone does not classify Ecchi metadata`() {
        val payload = Json.parseToJsonElement("""{"episodes":[
          {"mediaId":21,"episode":1,"airingAt":1791110000,"airType":"sub","title":"Safe","exact":true},
          {"mediaId":22,"episode":1,"airingAt":1791110000,"airType":"sub","title":"Flagged","exact":true,"isAdult":true,"genres":[],"tags":[]}
        ]}""").jsonObject
        assertNull(parseTsuzukiSchedule(payload, "sub").first().isAdult)
        val defaultFeed = parseTsuzukiSchedule(payload, "sub")
        assertEquals(listOf(null, true), defaultFeed.map { it.isAdult })
        assertTrue(filterAiringContent(defaultFeed, AiringSettings()).isEmpty())
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
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(old), hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION),
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
        val cached = AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z")), savedAt = 1000, hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION)
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
        val repository = AiringRepository(AiringSettings(), AiringSnapshot(listOf(event(1, 1, "2026-10-04T09:00:00Z")), hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION),
            fetch = { settings, _ -> requested = settings; emptyList() }, saveSettings = { persisted = it }, now = { now })
        val settings = AiringSettings(airType = "dub", historyDays = MAX_AIRING_HISTORY_DAYS)
        repository.updateSettings(settings)
        assertEquals(settings, requested)
        assertEquals(settings, persisted)
        assertTrue(repository.state.value.snapshot.events.isEmpty())
        assertEquals("dub", repository.state.value.snapshot.airType)
    }

    @Test fun `history beyond five days is rejected`() = runBlocking {
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ -> emptyList() }, now = { now })
        try { repository.updateSettings(AiringSettings(historyDays = 6)); fail("Six days must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `hidden titles leave recent and scheduled feeds and can be shown again`() = runBlocking {
        val hiddenEvent = event(21, 3, "2026-10-04T09:00:00Z")
        val keptEvent = event(22, 5, "2026-10-04T10:00:00Z")
        var persisted: AiringSettings? = null
        val repository = AiringRepository(AiringSettings(zone = "UTC"), AiringSnapshot(listOf(hiddenEvent, keptEvent), now.toEpochMilli(), hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION),
            fetch = { _, _ -> error("Hiding must not refetch") }, saveSettings = { persisted = it }, now = { now })
        repository.hide(hiddenEvent)
        val settings = repository.state.value.settings
        assertEquals(listOf(21), settings.hidden.map { it.mediaId })
        assertEquals(settings, persisted)
        val recent = selectAiringEvents(listOf(hiddenEvent, keptEvent), settings, now, AiringWindow.Now)
        assertEquals(listOf(22), recent.map { it.mediaId })
        val today = now.atZone(java.time.ZoneId.of("UTC")).toLocalDate()
        assertEquals(listOf(22), selectScheduledEvents(listOf(hiddenEvent, keptEvent), settings, today).map { it.mediaId })

        repository.showAgain(21)
        assertTrue(repository.state.value.settings.hidden.isEmpty())
        repository.hide(hiddenEvent)
        repository.hide(keptEvent)
        repository.showAllAgain()
        assertTrue(repository.state.value.settings.hidden.isEmpty())
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
        val old = AiringSnapshot(listOf(adult), now.toEpochMilli(), hideNsfw = false, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION)
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

    @Test fun `Ecchi and adult tags are hidden in every selector and restored cache`() {
        val safe = event(1, 1, "2026-10-04T10:00:00Z")
        val ecchi = event(218217, 1, "2026-10-04T10:00:00Z").copy(genres = listOf("Comedy", "Ecchi", "Fantasy", "Romance"))
        val tagged = event(208025, 1, "2026-10-04T10:00:00Z").copy(hasAdultTags = true)
        val feed = listOf(safe, ecchi, tagged, ecchi.copy(episode = 2, genres = emptyList()))
        val settings = AiringSettings(zone = "UTC")
        val snapshot = AiringSnapshot(feed, now.toEpochMilli(), hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION)
        val repository = AiringRepository(settings, snapshot, fetch = { _, _ -> feed }, now = { now })
        assertEquals(listOf(1), repository.state.value.snapshot.events.map { it.mediaId })
        assertEquals(listOf(1), selectAiringEvents(feed, settings, now, AiringWindow.Now).map { it.mediaId })
        assertEquals(listOf(1), selectScheduledEvents(feed, settings, now.atZone(java.time.ZoneOffset.UTC).toLocalDate()).map { it.mediaId })
        assertEquals(4, filterAiringContent(feed, settings.copy(hideNsfw = false)).size)
        val legacy = snapshot.copy(contentPolicyVersion = 0)
        assertTrue(AiringRepository(settings, legacy, fetch = { _, _ -> feed }, now = { now }).state.value.snapshot.events.isEmpty())
    }

    @Test fun `presentation settings reuse the complete feed without refetching`() = runBlocking {
        var requests = 0
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ -> requests++; listOf(event(1, 1, "2026-10-04T10:00:00Z")) }, now = { now })
        repository.refresh()
        repository.updateSettings(repository.state.value.settings.copy(historyDays = 5))
        repository.updateSettings(repository.state.value.settings.copy(zone = "America/New_York", includeEstimated = false))
        repository.refresh(force = true)
        assertEquals(1, requests)
        assertEquals(5, repository.state.value.settings.historyDays)
    }

    @Test fun `concurrent forced refreshes coalesce and provider cooldown survives force`() = runBlocking {
        var instant = now
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = AiringRepository(AiringSettings(), fetch = { _, _ ->
            requests++; started.complete(Unit); finish.await(); throw AiringRateLimitException(now.plusSeconds(180).toEpochMilli())
        }, now = { instant })
        val first = launch { repository.refresh(force = true) }
        started.await()
        repeat(5) { repository.refresh(force = true) }
        finish.complete(Unit); first.join()
        instant = now.plusSeconds(90)
        repository.refresh(force = true)
        assertEquals(1, requests)
        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.loading)
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
    @Test fun `AniZip stills retain numeric anime episode identity and exclude specials`() {
        val extra = Json.parseToJsonElement("""{"mappings":{"anilist_id":176301,"kitsu_id":9999},"episodes":{
            "1":{"title":{"en":"Maomao and Maomao"},"image":"https://artworks.thetvdb.com/banners/v4/episode/10400908/screencap/677f93cd0265e.jpg","seasonNumber":2,"episodeNumber":25},
            "2":{"title":{"en":"Caravan"},"image":"https://images.test/10756303.jpg","seasonNumber":2,"episodeNumber":26},
            "S1":{"title":{"en":"Special"},"image":"https://images.test/special.jpg"}
        }}""").jsonObject
        val mapped = Json.parseToJsonElement("""{"kitsu_id":48649}""").jsonObject
        val record = event(176301, 2, "2026-10-04T10:00:00Z")
        val details = buildAiringDetails(record, listOf(record), mapped, extra.obj("episodes"), now)
        assertEquals(listOf("kitsu:48649:1", "kitsu:48649:2"), details.videos.map { it.id })
        assertEquals(listOf(1, 1), details.videos.map { it.season })
        assertEquals(listOf(1, 2), details.videos.map { it.episode })
        assertEquals(listOf("Maomao and Maomao", "Caravan"), details.videos.map { it.title })
        assertEquals(2, details.videos.mapNotNull { it.thumbnail }.distinct().size)
    }

}
