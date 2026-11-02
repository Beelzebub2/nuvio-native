package com.nuvio.app.features.home

import com.nuvio.app.core.auth.LocalSettingsImport
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.HOME_CATALOG_SHARED_SYNC_PLATFORM
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.airing.AIRING_HIDDEN_TITLES_SYNC_KEY
import com.nuvio.app.features.airing.exportBuiltInAiringHiddenTitles
import com.nuvio.app.features.airing.applyBuiltInAiringHiddenTitles
import com.nuvio.app.features.airing.acknowledgeBuiltInAiringHiddenTitles
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = "",
    val key: String = "",
)

@Serializable
data class SyncHomeCatalogPayload(
    @SerialName("show_catalog_type") val showCatalogType: Boolean = true,
    @SerialName("hide_unreleased_content") val hideUnreleasedContent: Boolean = false,
    val items: List<SyncCatalogItem> = emptyList(),
)

@Serializable
private data class SupabaseHomeCatalogSettingsBlob(
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("settings_json") val settingsJson: JsonObject = buildJsonObject { },
)

private data class PullToken(
    val userId: String,
    val profileId: Int,
    val generation: Long,
)

private data class CachedSharedSettings(
    val token: PullToken,
    val settingsJson: JsonObject,
)

internal fun mergeHomeCatalogSettingsJson(
    remoteJson: JsonObject?,
    localJson: JsonObject,
): JsonObject = buildJsonObject {
    remoteJson?.forEach { (key, value) -> put(key, value) }
    localJson.forEach { (key, value) -> put(key, value) }
}

internal fun shouldSeedHiddenAiringTitles(remoteJson: JsonObject?, localTitles: JsonArray): Boolean =
    remoteJson?.containsKey(AIRING_HIDDEN_TITLES_SYNC_KEY) != true && localTitles.isNotEmpty()

object HomeCatalogSettingsSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("HomeCatalogSettingsSyncService")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val HIDE_UNRELEASED_CONTENT_KEY = "hide_unreleased_content"
    private const val SHOW_CATALOG_TYPE_KEY = "show_catalog_type"

    @Volatile
    var isSyncingFromRemote: Boolean = false

    @Volatile private var accountGeneration: Long = 0
    private var pushJob: Job? = null
    private val syncMutex = Mutex()

    @Volatile
    private var completedInitialPull: PullToken? = null

    @Volatile
    private var cachedSharedSettings: CachedSharedSettings? = null

    internal fun clearAccountState() {
        accountGeneration++
        pushJob?.cancel()
        pushJob = null
        completedInitialPull = null
        cachedSharedSettings = null
    }

    suspend fun pullFromServer(profileId: Int) = syncMutex.withLock {
        if (LocalSettingsImport.hasPendingImport(profileId)) return@withLock
        runCatching {
            val token = currentPullToken(profileId) ?: return@withLock
            if (currentPullToken() != token) return@withLock
            val localPayload = HomeCatalogSettingsRepository.exportToSyncPayload()
            val localHidden = exportBuiltInAiringHiddenTitles()
            if (currentPullToken() != token) return@withLock
            val remoteBlob = fetchRemoteBlob(profileId)
            if (currentPullToken() != token || LocalSettingsImport.hasPendingImport(profileId)) return@withLock
            cachedSharedSettings = CachedSharedSettings(token, remoteBlob?.settingsJson ?: buildJsonObject { })
            if (remoteBlob != null) {
                val remotePayload = decodePayloadPreservingLocalDefaults(remoteBlob.settingsJson, localPayload)
                    ?: error("Failed to parse remote home catalog settings")
                applyRemotePayload(remotePayload)
                val remoteHidden = remoteBlob.settingsJson[AIRING_HIDDEN_TITLES_SYNC_KEY] as? JsonArray
                if (remoteHidden != null && localHidden != null) {
                    applyBuiltInAiringHiddenTitles(remoteHidden, localHidden.titles)
                }
            }
            if (currentPullToken() != token) return@withLock
            markInitialPullComplete(token)
            val hidden = exportBuiltInAiringHiddenTitles()
            if (currentPullToken() != token) return@withLock
            // Older blobs have no hidden list. Seed existing local titles once;
            // an explicit remote [] is authoritative and must never be unioned.
            if (hidden != null && (hidden.pending || shouldSeedHiddenAiringTitles(remoteBlob?.settingsJson, hidden.titles))) {
                pushToRemoteLocked(token)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.e(error) { "pullFromServer - FAILED" }
        }
    }

    fun triggerPush() {
        val requestedToken = currentPullToken()
        if (requestedToken == null || !hasCompletedInitialPull(requestedToken)) {
            log.d { "triggerPush — skipped before initial home catalog pull completed" }
            return
        }
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            if (isSyncingFromRemote) return@launch
            if (currentPullToken() != requestedToken) return@launch
            pushToRemote(requestedToken)
        }
    }

    internal suspend fun pushCurrentProfileToRemote(): Boolean {
        val token = currentPullToken() ?: return false
        return pushToRemote(token)
    }

    private suspend fun pushToRemote(token: PullToken): Boolean = syncMutex.withLock {
        pushToRemoteLocked(token)
    }

    private suspend fun pushToRemoteLocked(token: PullToken): Boolean = runCatching {
        if (currentPullToken() != token) return false
        val payload = HomeCatalogSettingsRepository.exportToSyncPayload()
        val hidden = exportBuiltInAiringHiddenTitles()
        if (currentPullToken() != token) return false
        val jsonElement = mergedSharedPayloadJson(token, payload, hidden?.titles)
        val params = buildJsonObject {
            put("p_profile_id", token.profileId)
            put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
            put("p_settings_json", jsonElement)
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_home_catalog_settings", params)
        if (currentPullToken() != token) return false
        cachedSharedSettings = CachedSharedSettings(token = token, settingsJson = jsonElement)
        hidden?.let { acknowledgeBuiltInAiringHiddenTitles(it.titles) }
        true
    }.onFailure {
        if (it is CancellationException) throw it
        log.e(it) { "pushToRemote failed" }
    }.getOrDefault(false)

    private fun currentPullToken(profileId: Int = ProfileRepository.activeProfileId): PullToken? {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return null
        return PullToken(
            userId = authState.userId,
            profileId = profileId,
            generation = accountGeneration,
        )
    }

    private fun hasCompletedInitialPull(token: PullToken): Boolean =
        completedInitialPull == token

    private fun markInitialPullComplete(token: PullToken) {
        completedInitialPull = token
    }

    private fun applyRemotePayload(
        payload: SyncHomeCatalogPayload,
    ) {
        isSyncingFromRemote = true
        try {
            HomeCatalogSettingsRepository.applyFromRemote(payload)
        } finally {
            isSyncingFromRemote = false
        }
    }

    private suspend fun fetchRemoteBlob(
        profileId: Int,
    ): SupabaseHomeCatalogSettingsBlob? {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
        }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_home_catalog_settings", params)
        return result.decodeList<SupabaseHomeCatalogSettingsBlob>().firstOrNull()
    }

    private fun decodePayloadPreservingLocalDefaults(
        settingsJson: JsonObject,
        localPayload: SyncHomeCatalogPayload,
    ): SyncHomeCatalogPayload? = runCatching {
        val decoded = json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settingsJson)
        decoded.copy(
            showCatalogType = if (settingsJson.containsKey(SHOW_CATALOG_TYPE_KEY)) {
                decoded.showCatalogType
            } else {
                localPayload.showCatalogType
            },
            hideUnreleasedContent = if (settingsJson.containsKey(HIDE_UNRELEASED_CONTENT_KEY)) {
                decoded.hideUnreleasedContent
            } else {
                localPayload.hideUnreleasedContent
            },
        )
    }.getOrNull()

    private fun mergedSharedPayloadJson(
        token: PullToken,
        payload: SyncHomeCatalogPayload,
        hiddenTitles: JsonArray?,
    ): JsonObject {
        val localJson = buildJsonObject {
            json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject.forEach { (key, value) -> put(key, value) }
            hiddenTitles?.let { put(AIRING_HIDDEN_TITLES_SYNC_KEY, it) }
        }
        val remoteJson = cachedSharedSettings
            ?.takeIf { cached -> cached.token == token }
            ?.settingsJson
        return mergeHomeCatalogSettingsJson(remoteJson = remoteJson, localJson = localJson)
    }
}
