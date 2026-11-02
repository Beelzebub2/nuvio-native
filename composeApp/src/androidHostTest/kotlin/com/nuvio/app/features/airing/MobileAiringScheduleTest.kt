package com.nuvio.app.features.airing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileAiringScheduleTest {
    @Test
    fun `mobile schedule exposes today and the next six distinct days`() {
        val dates = mobileAiringScheduleDays(LocalDate.of(2026, 11, 2))
        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 11, 2), dates.first())
        assertEquals(LocalDate.of(2026, 11, 8), dates.last())
    }

    @Test
    fun `day picker shows exactly the requested day while recent shows historical episodes`() {
        val now = Instant.parse("2026-11-02T09:00:00Z")
        val zone = ZoneId.of("Europe/Lisbon")
        fun episode(id: Int, day: Int) = AiringEvent(
            mediaId = id, episode = 1,
            airingAt = LocalDate.of(2026, 11, day).atTime(8, 0).atZone(zone).toEpochSecond(),
            name = "Show $id", isAdult = false,
        )
        val snapshot = AiringSnapshot(
            events = listOf(episode(1, 1), episode(2, 2), episode(3, 4)),
        )
        val settings = AiringSettings(historyDays = 2)
        assertEquals(listOf(2, 1), mobileAiringVisibleEvents(snapshot, settings, now, null).map { it.mediaId })
        assertEquals(listOf(3), mobileAiringVisibleEvents(snapshot, settings, now, "2026-11-04").map { it.mediaId })
        assertEquals(listOf(2), mobileAiringVisibleEvents(snapshot, settings, now, "2026-11-02").map { it.mediaId })
    }

    @Test
    fun `builtin home hero keeps safe distinct airing entries when addon hero is empty`() {
        val now = Instant.parse("2026-11-02T09:00:00Z")
        val tomorrow = now.plusSeconds(86400L)
        val events = listOf(
            AiringEvent(1, 3, now.minusSeconds(3600L).epochSecond, "Show", poster = "https://img/1", isAdult = false),
            AiringEvent(1, 4, tomorrow.epochSecond, "Show", poster = "https://img/1", isAdult = false),
            AiringEvent(2, 1, tomorrow.epochSecond, "Hidden", poster = "https://img/2", isAdult = false),
            AiringEvent(3, 1, tomorrow.epochSecond, "Adult", poster = "https://img/3", isAdult = true),
            AiringEvent(4, 1, tomorrow.epochSecond, "Missing artwork", isAdult = false),
            AiringEvent(5, 1, tomorrow.epochSecond, "Next", poster = "https://img/5", isAdult = false),
        )
        val state = AiringState(
            settings = AiringSettings(hidden = listOf(HiddenAiringTitle(2, "Hidden"))),
            snapshot = AiringSnapshot(events = events), now = now,
        )
        val items = com.nuvio.app.features.home.homeAiringHeroItems(state)
        assertEquals(listOf("nativeairing:1", "nativeairing:5"), items.map { it.id })
        assertTrue(items.all { it.banner != null || it.poster != null })
    }
}
