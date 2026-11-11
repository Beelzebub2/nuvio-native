package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.DefaultContinueWatchingLimit
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.continueWatchingProgressEntries
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType
import com.nuvio.app.features.watching.domain.resumeProgressForSeries
import com.nuvio.app.features.watching.domain.shouldStoreProgress
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ContinueWatchingLimit = DefaultContinueWatchingLimit
private const val WatchProgressSnapshotConflictToleranceMs = 1_000L

/** A deleted row's stable server key and minimal identity, without its playback metadata. */
@Serializable
internal data class PendingWatchProgressDelete(
    val progressKey: String,
    val contentId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    fun matches(progressKey: String, contentId: String, season: Int?, episode: Int?): Boolean =
        this.progressKey == progressKey ||
            (this.progressKey == buildWatchProgressKey(this.contentId, seasonNumber, episodeNumber) &&
                this.contentId.isNotBlank() && this.contentId == contentId &&
                seasonNumber == season && episodeNumber == episode)

    /** The sync adapter's delete RPC consumes the resolved key only. */
    fun asDeleteEntry(): WatchProgressEntry = WatchProgressEntry(
        contentType = "",
        parentMetaId = contentId,
        parentMetaType = "",
        videoId = "",
        title = "",
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = 0L,
        progressKey = progressKey,
    )
}

internal fun WatchProgressEntry.asPendingDelete(): PendingWatchProgressDelete = PendingWatchProgressDelete(
    progressKey = resolvedProgressKey(),
    contentId = parentMetaId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
)

internal fun canQueueRemoteProgressDelete(
    usesNativeProgress: Boolean,
    isAuthenticated: Boolean,
    isAnonymous: Boolean,
): Boolean = usesNativeProgress && isAuthenticated && !isAnonymous

/** Resuming a deleted row reuses its original opaque server identity. */
internal fun Collection<PendingWatchProgressDelete>.restoreIdentityForUpsert(
    entry: WatchProgressEntry,
): WatchProgressEntry {
    val syntheticKey = buildWatchProgressKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
    if (entry.resolvedProgressKey() != syntheticKey) return entry
    val pending = filter { delete ->
        delete.contentId == entry.parentMetaId &&
            delete.seasonNumber == entry.seasonNumber &&
            delete.episodeNumber == entry.episodeNumber
    }.singleOrNull() ?: return entry
    return entry.copy(progressKey = pending.progressKey)
}

internal fun Collection<PendingWatchProgressDelete>.normalizedPendingDeletes(
    entries: Collection<WatchProgressEntry>,
): List<PendingWatchProgressDelete> {
    val liveKeys = entries.mapTo(mutableSetOf(), WatchProgressEntry::resolvedProgressKey)
    return filter { it.progressKey.isNotBlank() && it.progressKey !in liveKeys }
        .distinctBy(PendingWatchProgressDelete::progressKey)
}

/** A legacy locally generated key can resolve to an opaque server key on first sync. */
internal fun Collection<PendingWatchProgressDelete>.reconcileWithProgressSnapshot(
    serverEntries: Collection<ProgressSyncRecord>,
): List<PendingWatchProgressDelete> {
    val serverKeys = serverEntries.mapTo(mutableSetOf(), ProgressSyncRecord::resolvedProgressKey)
    val uniqueRemoteKeyByIdentity = serverEntries
        .groupBy { Triple(it.contentId, it.season, it.episode) }
        .mapNotNull { (identity, rows) ->
            rows.map(ProgressSyncRecord::resolvedProgressKey)
                .distinct()
                .singleOrNull()
                ?.let { identity to it }
        }.toMap()
    return map { pending ->
        val syntheticKey = buildWatchProgressKey(
            pending.contentId,
            pending.seasonNumber,
            pending.episodeNumber,
        )
        if (pending.progressKey in serverKeys || pending.progressKey != syntheticKey) {
            pending
        } else {
            val resolvedKey = uniqueRemoteKeyByIdentity[
                Triple(pending.contentId, pending.seasonNumber, pending.episodeNumber)
            ]
            if (resolvedKey == null) pending else pending.copy(progressKey = resolvedKey)
        }
    }.distinctBy(PendingWatchProgressDelete::progressKey)
}

@Serializable
internal data class StoredWatchProgressPayload(
    val entries: List<WatchProgressEntry> = emptyList(),
    val lastSuccessfulPushEpochMs: Long = 0L,
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
    val dirtyProgressKeys: Set<String> = emptySet(),
    val pendingDeletes: List<PendingWatchProgressDelete> = emptyList(),
)

internal object WatchProgressCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEntries(payload: String): List<WatchProgressEntry> =
        decodePayload(payload).entries

    fun decodePayload(payload: String): StoredWatchProgressPayload =
        runCatching {
            json.decodeFromString<StoredWatchProgressPayload>(payload).let { storedPayload ->
                val migratedEntries = storedPayload.entries
                    .map { entry -> entry.normalizedCompletion().withResolvedProgressKey() }
                    .newestByProgressKey()
                    .values
                    .sortedWith(watchProgressEntryFreshnessComparator.reversed())
                storedPayload.copy(
                    entries = migratedEntries,
                    dirtyProgressKeys = storedPayload.dirtyProgressKeys.intersect(
                        migratedEntries.mapTo(mutableSetOf()) { entry -> entry.resolvedProgressKey() },
                    ),
                    pendingDeletes = storedPayload.pendingDeletes.normalizedPendingDeletes(migratedEntries),
                )
            }
        }.getOrDefault(StoredWatchProgressPayload())

    fun encodeEntries(entries: Collection<WatchProgressEntry>): String =
        encodePayload(
            entries = entries,
            lastSuccessfulPushEpochMs = 0L,
            deltaCursorEventId = 0L,
            deltaInitialized = false,
        )

    fun encodePayload(
        entries: Collection<WatchProgressEntry>,
        lastSuccessfulPushEpochMs: Long,
        deltaCursorEventId: Long,
        deltaInitialized: Boolean,
        dirtyProgressKeys: Set<String> = emptySet(),
        pendingDeletes: Collection<PendingWatchProgressDelete> = emptyList(),
    ): String =
        json.encodeToString(
            StoredWatchProgressPayload(
                entries = entries
                    .newestByProgressKey()
                    .values
                    .sortedWith(watchProgressEntryFreshnessComparator.reversed()),
                lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                deltaCursorEventId = deltaCursorEventId,
                deltaInitialized = deltaInitialized,
                dirtyProgressKeys = dirtyProgressKeys,
                pendingDeletes = pendingDeletes.normalizedPendingDeletes(entries),
            ),
        )
}

internal fun shouldStoreWatchProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean = shouldStoreProgress(positionMs = positionMs, durationMs = durationMs)

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean = isProgressComplete(
    positionMs = positionMs,
    durationMs = durationMs,
    isEnded = isEnded,
)

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? {
    val candidates = newestByProgressKey().values.toList()
    val normalizedMetaId = metaId.trim()
    if (normalizedMetaId.isEmpty()) return null
    val contentCandidates = candidates.filter { entry ->
        entry.parentMetaId.trim() == normalizedMetaId
    }
    val seed = contentCandidates.firstOrNull() ?: return null
    val hasSeriesCandidate = contentCandidates.any { entry ->
        entry.parentMetaType.isSeriesLikeWatchingContentType()
    }
    val requestedType = if (hasSeriesCandidate) "series" else seed.parentMetaType

    return resumeProgressForSeries(
        content = WatchingContentRef(type = requestedType, id = normalizedMetaId),
        progressRecords = candidates.map(WatchProgressEntry::toDomainProgressRecord),
    )?.let { record ->
        candidates.firstOrNull { entry -> entry.resolvedProgressKey() == record.identityKey }
    }
}

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> {
    val selectionEntries = filter { entry ->
        entry.isEffectivelyCompleted || entry.shouldTreatAsInProgressForContinueWatching()
    }.newestByProgressKey().values.toList()
    val domainEntries = continueWatchingProgressEntries(
        progressRecords = selectionEntries.map(WatchProgressEntry::toDomainProgressRecord),
        limit = limit,
    )
    val identityKeys = domainEntries.map { record -> record.identityKey }.toSet()
    return selectionEntries
        .filter { entry -> entry.resolvedProgressKey() in identityKeys }
        .filter { entry -> entry.shouldTreatAsInProgressForContinueWatching() }
        .sortedByDescending { it.lastUpdatedEpochMs }
}

internal fun WatchProgressEntry.shouldTreatAsInProgressForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (entry.isEffectivelyCompleted) return false

    val hasStartedPlayback = entry.lastPositionMs > 0L ||
        entry.normalizedProgressPercent?.let { it > 0f } == true
    if (!hasStartedPlayback) return false

    return entry.source != WatchProgressSourceTraktHistory &&
        entry.source != WatchProgressSourceTraktShowProgress
}

internal fun WatchProgressEntry.shouldUseAsCompletedSeedForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (isMalformedNextUpSeedContentId(entry.parentMetaId)) return false
    return entry.isEffectivelyCompleted
}

internal fun shouldReplaceProgressSnapshotEntry(
    existing: WatchProgressEntry,
    candidate: WatchProgressEntry,
): Boolean {
    val normalizedExisting = existing.normalizedCompletion()
    val normalizedCandidate = candidate.normalizedCompletion()
    val existingInProgress = normalizedExisting.shouldTreatAsInProgressForContinueWatching()
    val candidateInProgress = normalizedCandidate.shouldTreatAsInProgressForContinueWatching()
    if (existingInProgress != candidateInProgress) {
        val inProgressEntry = if (candidateInProgress) normalizedCandidate else normalizedExisting
        val completedEntry = if (candidateInProgress) normalizedExisting else normalizedCandidate
        val inProgressIsCurrentEnough =
            inProgressEntry.lastUpdatedEpochMs >= completedEntry.lastUpdatedEpochMs - WatchProgressSnapshotConflictToleranceMs
        return if (candidateInProgress) inProgressIsCurrentEnough else !inProgressIsCurrentEnough
    }
    return normalizedCandidate.lastUpdatedEpochMs > normalizedExisting.lastUpdatedEpochMs
}

internal fun shouldCascadeCompletedProgressToWatchedHistory(
    entry: WatchProgressEntry,
    providerOwnsCompletedHistory: Boolean,
): Boolean = !providerOwnsCompletedHistory && entry.normalizedCompletion().isCompleted

internal fun String?.isSeriesTypeForContinueWatching(): Boolean =
    equals("series", ignoreCase = true) || equals("tv", ignoreCase = true)

/** Explicit addon content types only; `tv` means a regular episodic series here. */
internal fun String?.isExplicitLiveMetaType(): Boolean = when (this?.trim()?.lowercase()) {
    "live", "live-tv", "live_tv", "livetv", "iptv", "channel", "m3u" -> true
    else -> false
}

/** Drop late live callbacks from inactive profiles: their opt-out setting is no longer loaded. */
internal fun shouldSkipLivePlaybackProgress(
    metaType: String?,
    sessionProfileId: Int,
    activeProfileId: Int,
    hideLiveContent: Boolean,
): Boolean = metaType.isExplicitLiveMetaType() &&
    (sessionProfileId != activeProfileId || hideLiveContent)

internal fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase()) {
        "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
        else -> false
    }
}

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    normalizedCompletion().let { entry ->
        WatchingProgressRecord(
            content = WatchingContentRef(
                type = entry.parentMetaType,
                id = entry.parentMetaId,
            ),
            videoId = entry.videoId,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
            lastPositionMs = entry.lastPositionMs,
            isCompleted = entry.isEffectivelyCompleted,
            episodeTitle = entry.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail,
            identityKey = entry.resolvedProgressKey(),
        )
    }
