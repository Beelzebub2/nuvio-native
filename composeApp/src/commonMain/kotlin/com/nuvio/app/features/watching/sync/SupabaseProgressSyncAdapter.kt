package com.nuvio.app.features.watching.sync

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.resolvedProgressKey
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object SupabaseProgressSyncAdapter : ProgressSyncAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun getDeltaCursor(profileId: Int): Long {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        return SupabaseProvider.client.postgrest
            .rpc("sync_get_watch_progress_delta_cursor", params)
            .decodeAs<Long>()
    }

    override suspend fun pullDelta(
        profileId: Int,
        sinceEventId: Long,
        limit: Int,
    ): List<ProgressDeltaEvent> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_since_event_id", sinceEventId)
            put("p_limit", limit)
        }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_watch_progress_delta", params)
        return result.decodeList<WatchProgressDeltaSyncEntry>().map { event ->
            ProgressDeltaEvent(
                eventId = event.eventId,
                operation = event.operation,
                progressKey = event.progressKey,
                contentId = event.contentId,
                contentType = event.contentType,
                videoId = event.videoId,
                season = event.season,
                episode = event.episode,
                position = event.position,
                duration = event.duration,
                lastWatched = event.lastWatched,
            )
        }
    }

    override suspend fun pull(
        profileId: Int,
        sinceLastWatched: Long?,
        limit: Int?,
    ): List<ProgressSyncRecord> {
        // The server's new keyset RPC bounds memory/response size for long
        // histories. Older deployments retain the unchanged legacy RPC.
        val serverEntries = if (sinceLastWatched == null && limit == null) {
            try {
                collectProgressSnapshotPages(PROGRESS_SNAPSHOT_PAGE_SIZE) { afterKey, pageSize ->
                    val params = buildJsonObject {
                        put("p_profile_id", profileId)
                        put("p_limit", pageSize)
                        if (afterKey != null) put("p_after_key", afterKey)
                    }
                    val page = SupabaseProvider.client.postgrest
                        .rpc("sync_pull_watch_progress_page", params)
                        .decodeAs<WatchProgressPageResponse>()
                    ProgressSnapshotPage(items = page.items, nextCursor = page.nextCursor)
                }
            } catch (error: Throwable) {
                if (error is CancellationException || !isMissingProgressSnapshotPageRpc(error)) throw error
                pullLegacySnapshot(profileId, sinceLastWatched, limit)
            }
        } else {
            pullLegacySnapshot(profileId, sinceLastWatched, limit)
        }
        return serverEntries.map { entry ->
            ProgressSyncRecord(
                progressKey = entry.progressKey,
                contentId = entry.contentId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.season,
                episode = entry.episode,
                position = entry.position,
                duration = entry.duration,
                lastWatched = entry.lastWatched,
            )
        }
    }

    private suspend fun pullLegacySnapshot(
        profileId: Int,
        sinceLastWatched: Long?,
        limit: Int?,
    ): List<WatchProgressSyncEntry> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            if (sinceLastWatched != null) {
                put("p_since_last_watched", sinceLastWatched)
            }
            if (limit != null) {
                put("p_limit", limit)
            }
        }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_watch_progress", params)
        return result.decodeList<WatchProgressSyncEntry>()
    }

    override suspend fun push(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        val syncEntries = entries.map { entry ->
            WatchProgressSyncEntry(
                contentId = entry.parentMetaId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                position = entry.lastPositionMs,
                duration = entry.durationMs,
                lastWatched = entry.lastUpdatedEpochMs,
                progressKey = entry.resolvedProgressKey(),
            )
        }
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_entries", json.encodeToJsonElement(syncEntries))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_watch_progress", params)
    }

    override suspend fun delete(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        val progressKeys = entries.map { entry -> entry.resolvedProgressKey() }
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_keys", json.encodeToJsonElement(progressKeys))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_delete_watch_progress", params)
    }

}

@Serializable
private data class WatchProgressPageResponse(
    val items: List<WatchProgressSyncEntry> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
private data class WatchProgressSyncEntry(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
    @SerialName("progress_key") val progressKey: String = "",
)

@Serializable
private data class WatchProgressDeltaSyncEntry(
    @SerialName("event_id") val eventId: Long,
    val operation: String,
    @SerialName("progress_key") val progressKey: String,
    @SerialName("content_id") val contentId: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
)
