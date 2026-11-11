package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watching.sync.ProgressDeltaEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatchProgressIdentityTest {
    @Test
    fun `updated progress keeps provider anime classification and opaque server identity`() {
        val prior = entry(progressKey = "opaque/provider-episode").copy(mediaCategory = "anime")
        val playback = entry(progressKey = null, lastUpdatedEpochMs = 12L)
        val merged = listOf(prior).resolveIdentityForUpsert(playback)
        assertEquals("anime", merged.mediaCategory)
        assertEquals("opaque/provider-episode", merged.progressKey)
    }

    @Test
    fun `provider hidden content changes progress ui state without changing entries`() {
        val entry = entry(
            parentMetaId = "show",
            videoId = "show:1:2",
            progressKey = "show-episode",
            lastUpdatedEpochMs = 10L,
        )
        val visible = projectWatchProgressUiState(
            source = WatchProgressSource.SIMKL,
            entries = listOf(entry),
            providerSnapshot = TrackingProgressSnapshot(),
            hasLoadedNuvioRemoteProgress = false,
        )
        val hidden = projectWatchProgressUiState(
            source = WatchProgressSource.SIMKL,
            entries = listOf(entry),
            providerSnapshot = TrackingProgressSnapshot(hiddenContentIds = setOf("show")),
            hasLoadedNuvioRemoteProgress = false,
        )

        assertEquals(visible.entries, hidden.entries)
        assertNotEquals(visible, hidden)
    }

    @Test
    fun `provider change during metadata batch schedules one follow up`() {
        val coordinator = MetadataResolutionRetryCoordinator()

        assertTrue(coordinator.requestForProviders("provider-a"))
        val firstResolution = coordinator.beginResolution("provider-a")
        assertTrue(!coordinator.requestForProviders("provider-b"))

        assertTrue(coordinator.finishResolution(firstResolution, "provider-b"))
        assertTrue(!coordinator.requestForProviders("provider-b"))

        val followUpResolution = coordinator.beginResolution("provider-b")
        assertTrue(!coordinator.finishResolution(followUpResolution, "provider-b"))
    }

    @Test
    fun `provider observed before metadata fetch does not schedule redundant follow up`() {
        val coordinator = MetadataResolutionRetryCoordinator()
        val resolution = coordinator.beginResolution(providerFingerprint = null)

        assertTrue(!coordinator.requestForProviders("provider-a"))
        coordinator.providersObservedBeforeFetch(
            resolutionGeneration = resolution,
            providerFingerprint = "provider-a",
        )

        assertTrue(!coordinator.finishResolution(resolution, "provider-a"))
        assertTrue(!coordinator.requestForProviders("provider-a"))
    }

    @Test
    fun `completion from replaced metadata batch cannot schedule retry`() {
        val coordinator = MetadataResolutionRetryCoordinator()
        val replacedResolution = coordinator.beginResolution("provider-a")
        val activeResolution = coordinator.beginResolution("provider-b")

        assertTrue(!coordinator.finishResolution(replacedResolution, "provider-c"))
        assertTrue(!coordinator.finishResolution(activeResolution, "provider-b"))
    }

    @Test
    fun `legacy payload derives episode progress key and preserves completed position`() {
        val decoded = WatchProgressCodec.decodeEntries(
            """
            {
              "entries": [{
                "contentType": "series",
                "parentMetaId": "show",
                "parentMetaType": "series",
                "videoId": "show:1:2",
                "title": "Show",
                "seasonNumber": 1,
                "episodeNumber": 2,
                "lastPositionMs": 940000,
                "durationMs": 1000000,
                "lastUpdatedEpochMs": 100
              }]
            }
            """.trimIndent(),
        )

        assertEquals("show_s1e2", decoded.single().progressKey)
        assertEquals(940000L, decoded.single().lastPositionMs)
        assertTrue(decoded.single().isCompleted)
    }

    @Test
    fun `custom progress key round trips byte for byte`() {
        val customKey = "  Remote/KEY:S1E2  "
        val decoded = WatchProgressCodec.decodeEntries(
            WatchProgressCodec.encodeEntries(
                listOf(entry(progressKey = customKey)),
            ),
        )

        assertEquals(customKey, decoded.single().progressKey)
        assertEquals(customKey, decoded.single().resolvedProgressKey())
    }

    @Test
    fun `key-only delta delete preserves its opaque identity`() {
        val event = ProgressDeltaEvent(
            eventId = 7L,
            operation = "delete",
            progressKey = "  Remote/Delete-Key  ",
        )

        assertEquals("  Remote/Delete-Key  ", event.resolvedProgressKey())
        assertEquals("", event.videoId)
    }

    @Test
    fun `codec keeps aliases sharing video id and deduplicates only equal keys`() {
        val sharedVideoId = "shared-video"
        val olderSameKey = entry(
            parentMetaId = "show-a",
            videoId = sharedVideoId,
            progressKey = "opaque-a",
            lastUpdatedEpochMs = 10L,
            lastPositionMs = 100L,
        )
        val newerSameKey = olderSameKey.copy(
            lastUpdatedEpochMs = 20L,
            lastPositionMs = 200L,
        )
        val otherAlias = entry(
            parentMetaId = "show-b",
            videoId = sharedVideoId,
            progressKey = "opaque-b",
            lastUpdatedEpochMs = 15L,
        )

        val decoded = WatchProgressCodec.decodeEntries(
            WatchProgressCodec.encodeEntries(listOf(olderSameKey, otherAlias, newerSameKey)),
        )

        assertEquals(setOf("opaque-a", "opaque-b"), decoded.map { it.resolvedProgressKey() }.toSet())
        assertEquals(200L, decoded.single { it.resolvedProgressKey() == "opaque-a" }.lastPositionMs)
    }

    @Test
    fun `same-key completion tie is independent of input order`() {
        val incomplete = entry(
            progressKey = "opaque",
            lastUpdatedEpochMs = 100L,
            lastPositionMs = 900L,
        )
        val completed = incomplete.copy(isCompleted = true)

        val forward = listOf(incomplete, completed).newestByProgressKey().getValue("opaque")
        val reversed = listOf(completed, incomplete).newestByProgressKey().getValue("opaque")

        assertTrue(forward.isCompleted)
        assertEquals(forward, reversed)
    }

    @Test
    fun `partial metadata enrichment keeps existing nonblank artwork`() {
        val current = entry().copy(
            title = "Existing title",
            logo = "existing-logo",
            poster = "existing-poster",
            background = null,
        )

        val enriched = enrichWatchProgressEntry(
            current = current,
            meta = MetaDetails(
                id = "show",
                type = "series",
                name = "",
                logo = null,
                poster = "",
                background = "new-background",
            ),
        )

        assertEquals("Existing title", enriched.title)
        assertEquals("existing-logo", enriched.logo)
        assertEquals("existing-poster", enriched.poster)
        assertEquals("new-background", enriched.background)
    }

    @Test
    fun `placeholder id title still requests metadata when artwork exists`() {
        val placeholder = entry().copy(
            title = "show",
            poster = "poster",
            background = "background",
        )
        val enriched = placeholder.copy(title = "Resolved Show")

        assertTrue(placeholder.needsRemoteMetadataEnrichment())
        assertTrue(!enriched.needsRemoteMetadataEnrichment())
    }

    @Test
    fun `video id compatibility projection rejects ambiguous aliases and contextual lookup resolves them`() {
        val older = entry(
            parentMetaId = "show-a",
            videoId = "shared-video",
            progressKey = "opaque-a",
            lastUpdatedEpochMs = 10L,
        )
        val newer = entry(
            parentMetaId = "show-b",
            videoId = "shared-video",
            progressKey = "opaque-b",
            lastUpdatedEpochMs = 20L,
        )
        val state = WatchProgressUiState(entries = listOf(older, newer))

        assertTrue("shared-video" !in state.byVideoId)
        assertEquals(
            "opaque-a",
            state.progressForVideo(
                videoId = "shared-video",
                parentMetaId = "show-a",
            )?.resolvedProgressKey(),
        )
        assertEquals(setOf("opaque-a", "opaque-b"), state.byProgressKey.keys)
    }

    @Test
    fun `contextual lookup rejects episodes that remain ambiguous within the same parent`() {
        val episodeOne = entry(
            parentMetaId = "show",
            videoId = "shared-video",
            progressKey = "episode-one",
            lastUpdatedEpochMs = 10L,
        ).copy(episodeNumber = 1)
        val episodeTwo = episodeOne.copy(
            progressKey = "episode-two",
            episodeNumber = 2,
            lastUpdatedEpochMs = 20L,
        )
        val state = WatchProgressUiState(entries = listOf(episodeOne, episodeTwo))

        assertEquals(
            null,
            state.progressForVideo(
                videoId = "shared-video",
                parentMetaId = "show",
            ),
        )
        assertEquals(
            null,
            state.progressForVideo(
                videoId = "shared-video",
                parentMetaId = "show",
                seasonNumber = 1,
            ),
        )
        assertEquals(
            "episode-two",
            state.progressForVideo(
                videoId = "shared-video",
                parentMetaId = "show",
                seasonNumber = 1,
                episodeNumber = 2,
            )?.resolvedProgressKey(),
        )
    }

    @Test
    fun `optimistic update reuses exact opaque key before a fresher playback alias`() {
        val exactPlayback = entry(
            videoId = "provider-video-a",
            progressKey = "remote-exact-key",
            lastUpdatedEpochMs = 10L,
        )
        val otherPlayback = exactPlayback.copy(
            videoId = "provider-video-b",
            progressKey = "remote-other-key",
            lastUpdatedEpochMs = 20L,
        )
        val candidate = exactPlayback.copy(
            progressKey = null,
            lastUpdatedEpochMs = 30L,
        )

        val resolved = listOf(otherPlayback, exactPlayback).resolveIdentityForUpsert(candidate)

        assertEquals("remote-exact-key", resolved.progressKey)
    }

    @Test
    fun `snapshot keeps distinct keys that share a playback video id`() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = listOf(
                record(contentId = "show-a", progressKey = "opaque-a", lastWatched = 10L),
                record(contentId = "show-b", progressKey = "opaque-b", lastWatched = 20L),
            ),
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
        )

        assertEquals(setOf("opaque-a", "opaque-b"), merged.keys)
        assertEquals(2, merged.values.count { it.videoId == "shared-video" })
    }

    @Test
    fun `snapshot same-key winner is newest and independent of input order`() {
        val older = record(
            contentId = "show",
            progressKey = "opaque",
            videoId = "older-video",
            lastWatched = 10L,
            position = 100L,
        )
        val newer = older.copy(
            videoId = "newer-video",
            lastWatched = 20L,
            position = 200L,
        )

        val forward = mergeSnapshot(listOf(older, newer)).getValue("opaque")
        val reversed = mergeSnapshot(listOf(newer, older)).getValue("opaque")

        assertEquals("newer-video", forward.videoId)
        assertEquals(forward, reversed)
    }

    @Test
    fun `snapshot preserves unsynced local position when timestamps tie`() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = listOf(
                record(
                    contentId = "show",
                    progressKey = "opaque",
                    lastWatched = 100L,
                    position = 100L,
                ),
            ),
            localEntries = listOf(
                entry(
                    progressKey = "opaque",
                    lastUpdatedEpochMs = 100L,
                    lastPositionMs = 200L,
                ),
            ),
            dirtyProgressKeys = setOf("opaque"),
        )

        assertEquals(200L, merged.getValue("opaque").lastPositionMs)
    }

    @Test
    fun `offline delete remains absent when an old snapshot is pulled before retry succeeds`() {
        val pendingDelete = entry(progressKey = "opaque", lastUpdatedEpochMs = 100L).asPendingDelete()
        val server = listOf(record(contentId = "show", progressKey = "opaque", lastWatched = 100L))
        val restoredOutbox = WatchProgressCodec.decodePayload(WatchProgressCodec.encodePayload(
            entries = emptyList(),
            lastSuccessfulPushEpochMs = 0L,
            deltaCursorEventId = 0L,
            deltaInitialized = true,
            pendingDeletes = listOf(pendingDelete),
        )).pendingDeletes

        val duringFailedRetry = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = server,
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
            pendingDeletes = restoredOutbox,
        )
        val afterAcknowledgedDeletion = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = emptyList(),
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
            pendingDeletes = emptyList(),
        )

        assertTrue(duringFailedRetry.isEmpty())
        assertTrue(afterAcknowledgedDeletion.isEmpty())
    }

    @Test
    fun `offline synthetic delete resolves unique opaque remote key on reconnect`() {
        val pending = entry(progressKey = null).asPendingDelete()
        val remote = record(contentId = "show", progressKey = "opaque/server", lastWatched = 100L)

        val reconciled = listOf(pending).reconcileWithProgressSnapshot(listOf(remote))
        val blocked = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = listOf(remote),
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
            pendingDeletes = reconciled,
        )

        assertEquals("show_s1e2", pending.progressKey)
        assertEquals("opaque/server", reconciled.single().progressKey)
        assertEquals("opaque/server", reconciled.single().asDeleteEntry().resolvedProgressKey())
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `ambiguous remote keys do not redirect pending deletion to a guessed key`() {
        val pending = entry(progressKey = null).asPendingDelete()
        val remote = listOf(
            record(contentId = "show", progressKey = "opaque/a", lastWatched = 100L),
            record(contentId = "show", progressKey = "opaque/b", lastWatched = 200L),
        )

        assertEquals(listOf(pending), listOf(pending).reconcileWithProgressSnapshot(remote))
    }

    @Test
    fun `opaque tombstone filters only its key when multiple records share an identity`() {
        val deleted = entry(progressKey = "opaque/a").asPendingDelete()
        val server = listOf(
            record(contentId = "show", progressKey = "opaque/a", lastWatched = 100L),
            record(contentId = "show", progressKey = "opaque/b", lastWatched = 200L),
        )

        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = server,
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
            pendingDeletes = listOf(deleted),
        )

        assertEquals(setOf("opaque/b"), merged.keys)
    }

    @Test
    fun `delta upsert with equal timestamp and higher position wins`() {
        val current = entry(
            progressKey = "opaque",
            lastUpdatedEpochMs = 100L,
            lastPositionMs = 100L,
        )
        val event = deltaEvent(
            operation = "upsert",
            progressKey = "opaque",
            lastWatched = 100L,
            position = 200L,
        )

        val decision = WatchProgressRepository.decideWatchProgressDeltaEvent(
            current = current,
            event = event,
            isLocalDirty = false,
        )

        assertEquals(WatchProgressDeltaDecisionType.UPSERT, decision.type)
        assertEquals(200L, decision.updatedEntry?.lastPositionMs)
    }

    @Test
    fun `delta delete preserves a local update made during the pull`() {
        val decision = WatchProgressRepository.decideWatchProgressDeltaEvent(
            current = entry(progressKey = "opaque", lastUpdatedEpochMs = 300L),
            event = ProgressDeltaEvent(
                eventId = 1L,
                operation = "delete",
                progressKey = "opaque",
            ),
            isLocalDirty = true,
        )

        assertEquals(WatchProgressDeltaDecisionType.PRESERVE_LOCAL, decision.type)
    }

    @Test
    fun `delta upsert cannot resurrect a queued remote delete`() {
        val decision = WatchProgressRepository.decideWatchProgressDeltaEvent(
            current = null,
            event = deltaEvent(operation = "upsert", progressKey = "opaque", lastWatched = 200L),
            isLocalDirty = false,
            isPendingDelete = true,
        )

        assertEquals(WatchProgressDeltaDecisionType.IGNORE, decision.type)
        assertEquals(null, decision.updatedEntry)
    }

    @Test
    fun `new playback after a delete is not suppressed by the recent write deduplicator`() {
        val deduplicator = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        val played = entry(progressKey = "opaque")

        assertTrue(deduplicator.shouldSend(profileId = 1, entry = played, nowEpochMs = 100L))
        deduplicator.forget(profileId = 1, progressKeys = listOf("opaque"))
        assertTrue(deduplicator.shouldSend(profileId = 1, entry = played, nowEpochMs = 200L))
    }

    @Test
    fun `delta delete removes a clean remote-loaded entry even without a push watermark`() {
        val decision = WatchProgressRepository.decideWatchProgressDeltaEvent(
            current = entry(progressKey = "opaque", lastUpdatedEpochMs = 100L),
            event = ProgressDeltaEvent(
                eventId = 1L,
                operation = "delete",
                progressKey = "opaque",
            ),
            isLocalDirty = false,
        )

        assertEquals(WatchProgressDeltaDecisionType.DELETE, decision.type)
    }

    @Test
    fun `causally newer clean delta applies an authoritative rewind`() {
        val decision = WatchProgressRepository.decideWatchProgressDeltaEvent(
            current = entry(
                progressKey = "opaque",
                lastUpdatedEpochMs = 200L,
                lastPositionMs = 800L,
            ),
            event = deltaEvent(
                operation = "upsert",
                progressKey = "opaque",
                lastWatched = 100L,
                position = 100L,
            ),
            isLocalDirty = false,
        )

        assertEquals(WatchProgressDeltaDecisionType.UPSERT, decision.type)
        assertEquals(100L, decision.updatedEntry?.lastPositionMs)
        assertTrue(decision.clearsDirtyProgress)
    }

    @Test
    fun `authoritative snapshot drops a clean cached row when watermark is zero`() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = emptyList(),
            localEntries = listOf(entry(progressKey = "opaque")),
            dirtyProgressKeys = emptySet(),
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `snapshot migrates dirty legacy identity to sole opaque server key`() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = listOf(
                record(
                    contentId = "show",
                    progressKey = "server/opaque",
                    lastWatched = 10L,
                ),
            ),
            localEntries = listOf(
                entry(
                    parentMetaId = "show",
                    progressKey = "show_s1e2",
                    lastUpdatedEpochMs = 20L,
                ),
            ),
            dirtyProgressKeys = setOf("show_s1e2"),
        )

        assertEquals(setOf("server/opaque"), merged.keys)
        assertEquals(20L, merged.getValue("server/opaque").lastUpdatedEpochMs)
    }

    private fun mergeSnapshot(records: List<ProgressSyncRecord>): Map<String, WatchProgressEntry> =
        WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = records,
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
        )

    private fun record(
        contentId: String,
        progressKey: String,
        videoId: String = "shared-video",
        lastWatched: Long,
        position: Long = 100L,
    ): ProgressSyncRecord =
        ProgressSyncRecord(
            contentId = contentId,
            contentType = "series",
            videoId = videoId,
            season = 1,
            episode = 2,
            position = position,
            duration = 1_000L,
            lastWatched = lastWatched,
            progressKey = progressKey,
        )

    private fun deltaEvent(
        operation: String,
        progressKey: String,
        lastWatched: Long = 100L,
        position: Long = 100L,
    ): ProgressDeltaEvent =
        ProgressDeltaEvent(
            eventId = 1L,
            operation = operation,
            progressKey = progressKey,
            contentId = "show",
            contentType = "series",
            videoId = "show:1:2",
            season = 1,
            episode = 2,
            position = position,
            duration = 1_000L,
            lastWatched = lastWatched,
        )

    private fun entry(
        parentMetaId: String = "show",
        videoId: String = "show:1:2",
        progressKey: String? = null,
        lastUpdatedEpochMs: Long = 10L,
        lastPositionMs: Long = 100L,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = videoId,
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 2,
            lastPositionMs = lastPositionMs,
            durationMs = 1_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            progressKey = progressKey,
        )
}
