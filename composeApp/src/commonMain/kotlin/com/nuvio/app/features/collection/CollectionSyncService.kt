package com.nuvio.app.features.collection

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.LocalSettingsImport
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object CollectionSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("CollectionSyncService")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val PUSH_DEBOUNCE_MS = 1500L

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null
    private var observeJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        observeLocalChangesAndPush()
    }

    suspend fun pullFromServer(profileId: Int) {
        if (ProfileRepository.activeProfileId != profileId) return
        if (LocalSettingsImport.hasPendingImport(profileId)) return
        runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_collections", params)
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val blobs = result.decodeList<SupabaseCollectionBlob>()
            val blob = blobs.firstOrNull()

            if (blob == null) {
                log.i { "pullFromServer — no remote collections found" }
                return
            }

            val remoteCollectionsJson = if (blob.collectionsJson == JsonNull) {
                JsonArray(emptyList())
            } else {
                blob.collectionsJson
            }
            val remoteJson = remoteCollectionsJson.toString()
            val localJson = CollectionRepository.exportToJson()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching

            if (remoteJson == localJson) {
                log.d { "pullFromServer — remote matches local, no update needed" }
                return
            }

            val remoteCollections = runCatching {
                json.decodeFromString<List<Collection>>(remoteJson)
            }.getOrNull()

            if (remoteCollections != null) {
                if (ProfileRepository.activeProfileId != profileId) return@runCatching
                isSyncingFromRemote = true
                CollectionRepository.applyFromRemote(remoteCollections, remoteCollectionsJson)
                isSyncingFromRemote = false
                log.i { "pullFromServer — applied ${remoteCollections.size} collections from remote" }
            } else {
                log.w { "pullFromServer — failed to parse remote collections JSON" }
            }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }

    fun triggerPush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            val profileId = ProfileRepository.activeProfileId
            delay(500)
            if (ProfileRepository.activeProfileId != profileId) return@launch
            if (isSyncingFromRemote) return@launch
            val authState = AuthRepository.state.value
            if (authState !is AuthState.Authenticated || authState.isAnonymous) return@launch
            if (LocalSettingsImport.hasPendingImport(profileId)) return@launch
            pushToRemote(profileId)
        }
    }

    /** Upload an additive guest import without deleting remote collections from another device. */
    internal suspend fun mergeGuestCollectionsToRemote(
        profileId: Int,
        guestPayload: String,
    ): Boolean {
        val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return false
        if (auth.isAnonymous || ProfileRepository.activeProfileId != profileId) return false
        return try {
            val guest = json.decodeFromString<List<Collection>>(guestPayload)
            val params = buildJsonObject { put("p_profile_id", profileId) }
            val remote = SupabaseProvider.client.postgrest
                .rpc("sync_pull_collections", params)
                .decodeList<SupabaseCollectionBlob>()
                .firstOrNull()?.collectionsJson
            val remoteCollections = if (remote == null || remote == JsonNull) emptyList()
                else json.decodeFromJsonElement<List<Collection>>(remote)
            // Preserve account collections made locally while offline as well as
            // collections already uploaded by other devices.
            val localCollections = json.decodeFromString<List<Collection>>(
                CollectionRepository.exportToJson(),
            )
            val combined = mergeGuestCollections(
                mergeGuestCollections(remoteCollections, localCollections),
                guest,
            )
            val combinedJson = CollectionJsonPreserver.merge(
                json = json,
                rawCollectionsJson = remote ?: JsonArray(emptyList()),
                collections = combined,
            )
            val current = AuthRepository.state.value as? AuthState.Authenticated
            if (current == null || current.userId != auth.userId || current.isAnonymous ||
                ProfileRepository.activeProfileId != profileId) return false
            SupabaseProvider.client.postgrest.rpc("sync_push_collections", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", combinedJson)
                putSyncOriginClientId()
            })
            if (AuthRepository.state.value == current && ProfileRepository.activeProfileId == profileId) {
                CollectionRepository.applyFromRemote(combined, combinedJson)
            }
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Could not merge guest collections into account; keeping pending import" }
            false
        }
    }

    private suspend fun pushToRemote(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (LocalSettingsImport.hasPendingImport(profileId)) return@runCatching
            val collectionsJson = CollectionRepository.exportToJson()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val jsonElement = runCatching {
                json.parseToJsonElement(collectionsJson)
            }.getOrDefault(JsonArray(emptyList()))

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", jsonElement)
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_collections", params)
            log.d { "pushToRemote — success" }
        }.onFailure { e ->
            log.e(e) { "pushToRemote — FAILED" }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        observeJob = scope.launch {
            CollectionRepository.localChangeEvents
                .debounce(PUSH_DEBOUNCE_MS)
                .collect {
                    val profileId = ProfileRepository.activeProfileId
                    if (isSyncingFromRemote) return@collect
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    pushToRemote(profileId)
                }
        }
    }
}
