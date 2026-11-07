package com.nuvio.app.features.myanimelist

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MalListEditingTest {
    private fun entry(id: Int, status: MalStatus = MalStatus.WATCHING, score: Int = 0, episodes: Int = 0, total: Int = 12) =
        MalAnimeListEntry(MalAnime(id, "Anime $id"), null, total, MalListStatus(status, score, episodes))

    @Test fun patchOnlyContainsChangedFields() {
        val before = MalListStatus(MalStatus.WATCHING, 7, 3)
        assertNull(malListPatch(before, before))
        assertEquals(MalListPatch(score = 9), malListPatch(before, before.copy(score = 9)))
        assertEquals(
            MalListPatch(status = MalStatus.COMPLETED, watchedEpisodes = 12),
            malListPatch(before, MalListStatus(MalStatus.COMPLETED, 7, 12)),
        )
        assertEquals(MalListPatch(score = 0), malListPatch(before, before.copy(score = 0)))
    }

    @Test fun episodesFollowMyAnimeListConventions() {
        val planned = MalListStatus(MalStatus.PLAN_TO_WATCH, 0, 0)
        assertEquals(MalListStatus(MalStatus.WATCHING, 0, 1), planned.withWatchedEpisodes(1, 12))

        val watching = MalListStatus(MalStatus.WATCHING, 8, 11)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 8, 12), watching.withWatchedEpisodes(12, 12))
        // Clamped to the episode count, and never below zero.
        assertEquals(12, watching.withWatchedEpisodes(40, 12).watchedEpisodes)
        assertEquals(0, watching.withWatchedEpisodes(-3, 12).watchedEpisodes)
        // Unknown totals (still airing) never auto-complete.
        assertEquals(MalListStatus(MalStatus.WATCHING, 8, 500), watching.withWatchedEpisodes(500, 0))

        // Finishing a rewatch ends it; lowering progress on a completed title leaves it completed.
        val rewatch = MalListStatus(MalStatus.COMPLETED, 10, 11, isRewatching = true)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 10, 12, isRewatching = false), rewatch.withWatchedEpisodes(12, 12))
        val done = MalListStatus(MalStatus.COMPLETED, 10, 12)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 10, 11), done.withWatchedEpisodes(11, 12))
        // Dropped titles keep their status while progress changes.
        assertEquals(MalStatus.DROPPED, MalListStatus(MalStatus.DROPPED, 0, 2).withWatchedEpisodes(3, 12).status)
    }

    @Test fun completingFillsEpisodesAndOtherStatusesKeepProgress() {
        val watching = MalListStatus(MalStatus.WATCHING, 6, 4)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 6, 24), watching.withStatus(MalStatus.COMPLETED, 24))
        assertEquals(MalListStatus(MalStatus.COMPLETED, 6, 4), watching.withStatus(MalStatus.COMPLETED, 0))
        assertEquals(MalListStatus(MalStatus.ON_HOLD, 6, 4), watching.withStatus(MalStatus.ON_HOLD, 24))
        val rewatch = MalListStatus(MalStatus.COMPLETED, 9, 3, isRewatching = true)
        assertSame(rewatch, rewatch.withStatus(MalStatus.COMPLETED, 12))
        assertFalse(rewatch.withStatus(MalStatus.DROPPED, 12).isRewatching)
    }

    @Test fun factsDescribeTypeSeasonAndLength() {
        val entry = entry(1, total = 10).copy(mediaType = "tv", startSeason = "spring", startYear = 2016)
        assertEquals(listOf("TV", "Spring 2016", "10 eps"), malEntryFacts(entry))
        assertEquals(listOf("Movie", "2019", "1 ep"), malEntryFacts(entry(2, total = 1).copy(mediaType = "movie", startYear = 2019)))
        assertEquals(listOf("OVA"), malEntryFacts(entry(3, total = 0).copy(mediaType = "ova")))
        assertEquals("Masterpiece", malScoreLabel(10))
        assertEquals("No score", malScoreLabel(0))
    }

    @Test fun rapidEditsAreShownAtOnceAndSentAsOneRequest() = runTest {
        val sent = mutableListOf<Pair<Int, MalListPatch>>()
        val editor = MalListEditor(this, send = { id, patch ->
            sent += id to patch
            MalListStatus(patch.status ?: MalStatus.WATCHING, patch.score ?: 0, patch.watchedEpisodes ?: 0)
        }, delete = {}, debounceMillis = 400)
        editor.show(listOf(entry(5, episodes = 2)), append = false)

        repeat(3) { editor.edit(5) { it.listStatus.withWatchedEpisodes(it.listStatus.watchedEpisodes + 1, it.totalEpisodes) } }
        assertEquals(5, editor.entries.single().listStatus.watchedEpisodes)
        assertTrue(editor.isSaving(5))
        advanceTimeBy(300)
        assertTrue(sent.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(5 to MalListPatch(watchedEpisodes = 5)), sent)
        assertFalse(editor.isSaving(5))
        assertFalse(editor.hasPendingWork)
    }

    @Test fun failedSaveRestoresTheLastSavedValuesAndReports() = runTest {
        val failures = mutableListOf<String>()
        val editor = MalListEditor(this, send = { _, _ -> throw IOException("MyAnimeList returned HTTP 500") },
            delete = {}, onFailure = { failures += it })
        editor.show(listOf(entry(7, score = 6)), append = false)

        editor.edit(7) { it.listStatus.copy(score = 9) }
        assertEquals(9, editor.entries.single().listStatus.score)
        advanceUntilIdle()
        assertEquals(6, editor.entries.single().listStatus.score)
        assertEquals(listOf("MyAnimeList returned HTTP 500"), failures)
        assertFalse(editor.isSaving(7))
    }

    @Test fun editMadeWhileSendingIsSentAfterwardsAgainstTheNewServerState() = runTest {
        val sent = mutableListOf<MalListPatch>()
        var server = MalListStatus(MalStatus.WATCHING, 0, 0)
        val editor = MalListEditor(this, send = { _, patch ->
            sent += patch
            kotlinx.coroutines.delay(1_000)
            server = MalListStatus(patch.status ?: server.status, patch.score ?: server.score, patch.watchedEpisodes ?: server.watchedEpisodes)
            server
        }, delete = {}, debounceMillis = 100)
        editor.show(listOf(entry(9)), append = false)

        editor.edit(9) { it.listStatus.copy(score = 8) }
        advanceTimeBy(150) // first request in flight
        editor.edit(9) { it.listStatus.copy(watchedEpisodes = 1) }
        advanceTimeBy(500) // the first response hasn't arrived; the local edit stays visible
        assertEquals(MalListStatus(MalStatus.WATCHING, 8, 1), editor.entries.single().listStatus)

        advanceUntilIdle()
        assertEquals(listOf(MalListPatch(score = 8), MalListPatch(watchedEpisodes = 1)), sent)
        assertEquals(MalListStatus(MalStatus.WATCHING, 8, 1), editor.entries.single().listStatus)
        assertFalse(editor.hasPendingWork)
    }

    @Test fun reloadKeepsUnsavedEditsAndSkipsRepeatedTitles() = runTest {
        val editor = MalListEditor(this, send = { _, patch -> MalListStatus(MalStatus.WATCHING, patch.score ?: 0, 0) }, delete = {})
        editor.show(listOf(entry(1), entry(2)), append = false)
        editor.edit(1) { it.listStatus.copy(score = 10) }

        // A refresh arrives before the save went out: the edit must still be visible and sent.
        editor.show(listOf(entry(1), entry(3)), append = false)
        assertEquals(10, editor.entries.first { it.anime.id == 1 }.listStatus.score)
        editor.show(listOf(entry(3), entry(4)), append = true)
        assertEquals(listOf(1, 3, 4), editor.entries.map { it.anime.id })

        advanceUntilIdle()
        assertEquals(10, editor.entries.first { it.anime.id == 1 }.listStatus.score)
    }

    @Test fun flushSendsWaitingEditsEvenAfterTheScreenScopeEnds() = runTest {
        val sent = mutableListOf<MalListPatch>()
        val screen = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val editor = MalListEditor(screen, send = { _, patch -> sent += patch; MalListStatus(MalStatus.DROPPED, 0, 0) }, delete = {})
        editor.show(listOf(entry(4)), append = false)
        editor.edit(4) { it.listStatus.withStatus(MalStatus.DROPPED, it.totalEpisodes) }

        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        editor.flush { save -> launch { save() } }
        advanceUntilIdle()
        assertEquals(listOf(MalListPatch(status = MalStatus.DROPPED)), sent)
    }

    @Test fun removeDeletesOnSuccessAndKeepsTheTitleOnFailure() = runTest {
        var fail = true
        val deleted = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        var removed = 0
        val editor = MalListEditor(this, send = { _, _ -> error("unused") }, delete = { id ->
            if (fail) throw IOException("offline")
            deleted += id
        }, onFailure = { failures += it })
        editor.show(listOf(entry(1), entry(2)), append = false)

        editor.remove(1) { removed++ }
        runCurrent()
        assertEquals(listOf(1, 2), editor.entries.map { it.anime.id })
        assertEquals(listOf("offline"), failures)
        assertEquals(0, removed)

        fail = false
        editor.remove(1) { removed++ }
        advanceUntilIdle()
        assertEquals(listOf(2), editor.entries.map { it.anime.id })
        assertEquals(listOf(1), deleted)
        assertEquals(1, removed)
        assertFalse(editor.hasPendingWork)
    }
}
