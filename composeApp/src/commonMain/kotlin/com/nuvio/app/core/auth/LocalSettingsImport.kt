package com.nuvio.app.core.auth

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.isDesktop
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.AccountDownloadStorageReader
import com.nuvio.app.core.storage.AnonymousWorkspaceStorage
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.ProviderCredentialSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonBackupCodec
import com.nuvio.app.features.downloads.DownloadDesktopFeatures
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.validateImportModel
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.library.LibraryStoragePayloadCodec
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsStorage
import com.nuvio.app.features.watched.StoredWatchedPayload
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.StoredWatchProgressPayload
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.features.watchlater.WatchLaterRepository
import com.nuvio.app.features.watchlater.PersonalMediaRepository
import com.nuvio.app.features.watchlater.PersonalMediaEntry
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.StoredPluginsState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.search.SearchHistoryStorage
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
internal data class LocalSettingsSnapshot(
    val settings: String,
    val addons: String,
    val plugins: String,
    val home: String,
    val libraryDisplay: String,
    val p2p: P2pSettingsUiState,
    val introDbApiKey: String?,
    val appLanguage: String? = null,
    // Device-local download records and destination are transferred explicitly, never cloud-synced.
    val downloads: String? = null,
    val downloadFolder: String? = null,
    val downloadProfileId: Int? = null,
    // Data from the selected guest profile; older exports can recover it from the
    // preserved anonymous preference backup instead.
    val library: String? = null,
    val watchedHistory: String? = null,
    val watchProgress: String? = null,
    val collections: String? = null,
    val searchHistory: String? = null,
    val searchHistoryEnabled: Boolean? = null,
    val watchLater: String? = null,
    val personalMedia: String? = null,
)

@Serializable
private data class SavedAccountDownloads(
    val downloads: String? = null,
    val folder: String? = null,
)

/** Explicit, device-local transfer. Pending uploads block pulls, including after an app restart. */
object LocalSettingsImport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    val isAvailable: Boolean get() = AnonymousWorkspaceStorage.loadValue("import") != null

    private fun accountDownloadsKey(serverUrl: String, userId: String, profileId: Int): String =
        "account-downloads:${serverUrl.trim().trimEnd('/')}:$userId:$profileId"

    private fun currentAccountDownloadsKey(profileId: Int): String? {
        val account = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        if (account.isAnonymous || account.userId.isBlank() || profileId !in 1..MAX_PROFILES) return null
        return accountDownloadsKey(ServerConfigurationRepository.active.value.backendUrl, account.userId, profileId)
    }

    /** Account cleanup removes device preferences, so preserve each local profile before it runs. */
    private fun saveAccountDownloadsBeforeSignOut() {
        val activeProfile = ProfileRepository.activeProfileId
        val folder = AccountDownloadStorageReader.loadSelectedFolder()
        (1..MAX_PROFILES).forEach { profileId ->
            val key = currentAccountDownloadsKey(profileId) ?: return
            val downloads = if (profileId == activeProfile) DownloadsRepository.exportLocalImportSnapshot()
                else AccountDownloadStorageReader.loadPayload(profileId)
            require(downloads == null || DownloadsCodec.isValid(downloads)) { "Saved account downloads are damaged" }
            val previous = AnonymousWorkspaceStorage.loadValue(key)?.let {
                json.decodeFromString<SavedAccountDownloads>(it)
            }
            // A previously saved inactive profile may not have been reopened since the last
            // account wipe. A missing live payload is not permission to erase its backup.
            if (downloads != null || (folder != null && (previous != null || profileId == activeProfile))) {
                AnonymousWorkspaceStorage.saveValue(key, json.encodeToString(SavedAccountDownloads(
                    downloads = downloads ?: previous?.downloads,
                    folder = folder ?: previous?.folder,
                )))
            }
        }
    }

    /** Restore once for the same account and profile; never overwrite even an explicitly empty queue. */
    internal fun restoreAccountDownloadsIfMissing(profileId: Int = ProfileRepository.activeProfileId): Boolean {
        val key = currentAccountDownloadsKey(profileId) ?: return false
        if (ProfileRepository.activeProfileId != profileId || DownloadsStorage.loadPayload() != null) return false
        val saved = AnonymousWorkspaceStorage.loadValue(key) ?: return false
        val snapshot = json.decodeFromString<SavedAccountDownloads>(saved)
        require(snapshot.downloads == null || DownloadsCodec.isValid(snapshot.downloads)) {
            "Saved account downloads are damaged"
        }
        if (snapshot.downloads == null) {
            // A folder-only recovery is still pending when the drive is unplugged.
            val folder = snapshot.folder ?: return false
            if (!isDesktop || !AccountDownloadStorageReader.isUsableFolder(folder)) return false
        }
        DownloadsRepository.mergeAnonymousDownloadRecords(snapshot.downloads, snapshot.folder)
        if (snapshot.downloads == null &&
            AccountDownloadStorageReader.loadSelectedFolder() != snapshot.folder) return false
        AnonymousWorkspaceStorage.saveValue(key, null)
        return true
    }

    /** A remotely deleted account must not leave provider headers or download metadata in local backups. */
    internal fun forgetAccountDownloadBackups(serverUrl: String, userId: String) {
        if (userId.isBlank()) return
        (1..MAX_PROFILES).forEach { profileId ->
            AnonymousWorkspaceStorage.saveValue(accountDownloadsKey(serverUrl, userId, profileId), null)
        }
    }

    internal fun saveAnonymousWorkspace(userId: String) {
        val snapshot = captureCurrentProfile()
        AnonymousWorkspaceStorage.save(userId)
        AnonymousWorkspaceStorage.saveValue("import", json.encodeToString(snapshot))
    }

    private fun captureCurrentProfile(): LocalSettingsSnapshot {
        P2pSettingsRepository.ensureLoaded()
        val plugins = if (AppFeaturePolicy.pluginsEnabled) PluginRepository.exportLocalSnapshot() else ""
        return LocalSettingsSnapshot(
            settings = ProfileSettingsSync.exportLocalSettings(),
            addons = AddonRepository.exportBackup(),
            plugins = plugins,
            home = HomeCatalogSettingsStorage.loadPayload().orEmpty(),
            libraryDisplay = LibraryDisplaySettingsStorage.loadPayload().orEmpty(),
            p2p = P2pSettingsRepository.uiState.value,
            introDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey(),
            appLanguage = ThemeSettingsStorage.loadSelectedAppLanguage(),
            downloads = DownloadsRepository.exportLocalImportSnapshot(),
            downloadFolder = if (isDesktop) DownloadDesktopFeatures.directory() else null,
            downloadProfileId = ProfileRepository.activeProfileId,
            library = LibraryStorage.loadPayload(ProfileRepository.activeProfileId),
            watchedHistory = WatchedStorage.loadPayload(ProfileRepository.activeProfileId),
            watchProgress = WatchProgressStorage.loadPayload(ProfileRepository.activeProfileId),
            collections = CollectionStorage.loadPayload(),
            searchHistory = SearchHistoryStorage.loadPayload(),
            searchHistoryEnabled = SearchHistoryStorage.loadEnabled(),
            watchLater = WatchLaterRepository.exportLocalImportSnapshot(),
            personalMedia = PersonalMediaRepository.exportLocalImportSnapshot(),
        )
    }

    /** Older snapshots lack downloads; their full anonymous backup still holds the original keys. */
    private fun withSavedGuestDownloads(payload: String): String {
        val snapshot = decodeValidatedSnapshot(payload)
        val sourceProfileId = snapshot.downloadProfileId ?: savedGuestProfileId() ?: 1
        val downloads = snapshot.downloads ?: (
            AnonymousWorkspaceStorage.loadSavedPreference("nuvio_downloads", "downloads_$sourceProfileId")
                ?: AnonymousWorkspaceStorage.loadSavedPreference("nuvio_downloads", "downloads_payload_$sourceProfileId")
            )
        require(downloads == null || DownloadsCodec.isValid(downloads)) { "Saved anonymous downloads are damaged" }
        val guestLibrary = snapshot.library ?: savedGuestPreference(
            "nuvio_library", "library_$sourceProfileId", "library_payload_$sourceProfileId",
        )
        val guestWatched = snapshot.watchedHistory ?: savedGuestPreference(
            "nuvio_watched", "watched_$sourceProfileId", "watched_payload_$sourceProfileId",
        )
        val guestProgress = snapshot.watchProgress ?: savedGuestPreference(
            "nuvio_watch_progress", "watch_progress_$sourceProfileId", "watch_progress_payload_$sourceProfileId",
        )
        val guestCollections = snapshot.collections ?: savedGuestPreference(
            "nuvio_collections", "collections_$sourceProfileId", "collections_payload_$sourceProfileId",
        )
        val guestSearches = snapshot.searchHistory ?: savedGuestPreference(
            "nuvio_search_history", "search_history_$sourceProfileId", "search_history_payload_$sourceProfileId",
        )
        val guestWatchLater = snapshot.watchLater ?: savedGuestPreference(
            "nuvio_watch_later", "items_$sourceProfileId",
        )
        val guestPersonalMedia = snapshot.personalMedia ?: savedGuestPreference(
            "nuvio_personal_media", "items_$sourceProfileId",
        )
        return json.encodeToString(snapshot.copy(
            downloads = downloads,
            downloadFolder = snapshot.downloadFolder
                ?: AnonymousWorkspaceStorage.loadSavedPreference("desktop_download_preferences", "folder"),
            downloadProfileId = sourceProfileId,
            library = guestLibrary,
            watchedHistory = guestWatched,
            watchProgress = guestProgress,
            collections = guestCollections,
            searchHistory = guestSearches,
            watchLater = guestWatchLater,
            personalMedia = guestPersonalMedia,
        ))
    }

    private fun savedGuestPreference(store: String, vararg keys: String): String? {
        keys.forEach { key ->
            val value = AnonymousWorkspaceStorage.loadSavedPreference(store, key)
            if (value != null) return value
        }
        return null
    }

    private fun savedGuestProfileId(): Int? {
        val locations = listOf("nuvio_profiles" to "profiles", "nuvio_profile_cache" to "profile_payload")
        locations.forEach { (store, key) ->
            val saved = AnonymousWorkspaceStorage.loadSavedPreference(store, key) ?: return@forEach
            val activeId = runCatching {
                json.parseToJsonElement(saved).jsonObject["activeProfileIndex"]?.jsonPrimitive?.intOrNull
            }.getOrNull()
            if (activeId != null && activeId in 1..com.nuvio.app.features.profiles.MAX_PROFILES) return activeId
        }
        return null
    }

    private fun pendingKey(profileId: Int = ProfileRepository.activeProfileId): String? {
        val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        if (auth.isAnonymous) return null
        return "pending:${ServerConfigurationRepository.active.value.backendUrl}:${auth.userId}:$profileId"
    }

    fun hasPendingImport(profileId: Int = ProfileRepository.activeProfileId): Boolean =
        pendingKey(profileId)?.let { AnonymousWorkspaceStorage.loadValue(it) } != null

    fun hasDamagedPendingImport(profileId: Int = ProfileRepository.activeProfileId): Boolean =
        pendingKey(profileId)?.let { AnonymousWorkspaceStorage.loadValue("damaged:$it") } != null

    private fun decodeValidatedSnapshot(payload: String): LocalSettingsSnapshot =
        json.decodeFromString<LocalSettingsSnapshot>(payload).also { snapshot ->
            ProfileSettingsSync.validateLocalSettings(snapshot.settings)
            AddonBackupCodec.decode(snapshot.addons)
            if (snapshot.plugins.isNotEmpty()) json.decodeFromString<StoredPluginsState>(snapshot.plugins)
            snapshot.downloads?.let { require(DownloadsCodec.isValid(it)) { "Saved downloads metadata is damaged" } }
            snapshot.library?.takeIf(String::isNotBlank)?.let(LibraryStoragePayloadCodec::decodeStrict)
            snapshot.watchedHistory?.takeIf(String::isNotBlank)?.let { payload ->
                json.decodeFromString<StoredWatchedPayload>(payload)
            }
            snapshot.watchProgress?.takeIf(String::isNotBlank)?.let { payload ->
                json.decodeFromString<StoredWatchProgressPayload>(payload)
            }
            snapshot.collections?.takeIf(String::isNotBlank)?.let { payload ->
                require(validateImportModel(json.decodeFromString<List<Collection>>(payload)) == null) {
                    "Saved anonymous collections are invalid"
                }
            }
            snapshot.searchHistory?.takeIf(String::isNotBlank)?.let { payload ->
                json.decodeFromString<List<String>>(payload)
            }
            snapshot.watchLater?.takeIf(String::isNotBlank)?.let { payload ->
                json.decodeFromString<List<LibraryItem>>(payload)
            }
            snapshot.personalMedia?.takeIf(String::isNotBlank)?.let { payload ->
                json.decodeFromString<List<PersonalMediaEntry>>(payload)
            }
        }

    /** Replaces the current profile's preferences, addons and plugins; leaves the guest backup intact. */
    suspend fun importIntoCurrentProfile(): Result<Boolean> = runCatching {
        mutex.withLock {
            val key = checkNotNull(pendingKey()) { "Sign in to an account first" }
            check(ProfileRepository.state.value.activeProfile != null) { "Select an account profile first" }
            val profile = ProfileRepository.state.value.activeProfile!!
            check(!profile.usesPrimaryAddons && !profile.usesPrimaryPlugins) { "Import into a profile with its own addons and plugins" }
            val payload = checkNotNull(AnonymousWorkspaceStorage.loadValue("import")) { "No saved anonymous settings" }
            val completePayload = withSavedGuestDownloads(payload)
            AnonymousWorkspaceStorage.saveValue(key, completePayload)
            AnonymousWorkspaceStorage.saveValue("damaged:$key", null)
            SyncManager.cancelAccountSync()
            ProfileSettingsSync.clearAccountState()
            markApplied(key, false)
            applyPending(key)
            uploadPending(key)
        }
    }

    internal suspend fun retryPendingImport(profileId: Int): Boolean = mutex.withLock {
        if (ProfileRepository.activeProfileId != profileId) return@withLock false
        val key = pendingKey(profileId) ?: return@withLock true
        val payload = AnonymousWorkspaceStorage.loadValue(key) ?: return@withLock true
        // Old pending imports predate watch/library fields; the durable guest backup
        // still contains those values, even after an interrupted sign-in/import.
        val completePayload = runCatching { withSavedGuestDownloads(payload) }.getOrDefault(payload)
        val valid = runCatching { decodeValidatedSnapshot(completePayload) }.isSuccess
        if (!valid) {
            // Preserve the damaged record for recovery, but let normal account sync resume.
            AnonymousWorkspaceStorage.saveValue("damaged:$key", payload)
            AnonymousWorkspaceStorage.saveValue(key, null)
            markApplied(key, false)
            return@withLock true
        }
        if (completePayload != payload) {
            AnonymousWorkspaceStorage.saveValue(key, completePayload)
            markApplied(key, false)
        }
        applyPending(key)
        uploadPending(key)
    }

    private fun isApplied(key: String): Boolean = AuthStorage.loadImportedSettingsScope().orEmpty().lines().contains(key)

    private fun markApplied(key: String, applied: Boolean) {
        val keys = AuthStorage.loadImportedSettingsScope().orEmpty().lines().filter { it.isNotBlank() }.toMutableSet()
        if (applied) keys.add(key) else keys.remove(key)
        AuthStorage.saveImportedSettingsScope(keys.joinToString("\n").ifEmpty { null })
    }

    internal fun savePendingBeforeSignOut() {
        saveAccountDownloadsBeforeSignOut()
        val key = pendingKey() ?: return
        if (hasPendingImport() && isApplied(key)) {
            AnonymousWorkspaceStorage.saveValue(key, json.encodeToString(captureCurrentProfile()))
        }
    }

    private fun applyPending(key: String) {
        val payload = AnonymousWorkspaceStorage.loadValue(key) ?: return
        if (isApplied(key)) return
        check(pendingKey() == key) { "Account profile changed during import" }
        val snapshot = decodeValidatedSnapshot(payload)
        ProfileSettingsSync.applyLocalSettings(snapshot.settings)
        PlayerSettingsStorage.saveIntroDbApiKey(snapshot.introDbApiKey.orEmpty())
        snapshot.appLanguage?.let(ThemeSettingsStorage::saveSelectedAppLanguage)
        ThemeSettingsRepository.onProfileChanged()
        com.nuvio.app.features.player.PlayerSettingsRepository.onProfileChanged()
        HomeCatalogSettingsStorage.savePayload(snapshot.home)
        HomeCatalogSettingsRepository.onProfileChanged()
        LibraryDisplaySettingsStorage.savePayload(snapshot.libraryDisplay)
        LibraryDisplaySettingsRepository.onProfileChanged()
        with(snapshot.p2p) {
            P2pSettingsRepository.setP2pEnabled(p2pEnabled)
            P2pSettingsRepository.setEnableUpload(enableUpload)
            P2pSettingsRepository.setHideTorrentStats(hideTorrentStats)
            P2pSettingsRepository.setTorrentProfile(torrentProfile)
            P2pSettingsRepository.setCacheSize(cacheSize)
        }
        AddonRepository.replaceLocalBackup(snapshot.addons)
        if (AppFeaturePolicy.pluginsEnabled && snapshot.plugins.isNotEmpty()) PluginRepository.importLocalSnapshot(snapshot.plugins)
        if (snapshot.downloads != null || snapshot.downloadFolder != null) {
            DownloadsRepository.mergeAnonymousDownloadRecords(snapshot.downloads, snapshot.downloadFolder)
        }
        snapshot.library?.takeIf(String::isNotBlank)?.let(LibraryRepository::importAnonymousLibrary)
        snapshot.watchedHistory?.takeIf(String::isNotBlank)?.let(WatchedRepository::importAnonymousHistory)
        snapshot.watchProgress?.takeIf(String::isNotBlank)?.let(WatchProgressRepository::importAnonymousProgress)
        snapshot.collections?.takeIf(String::isNotBlank)?.let(CollectionRepository::importAnonymousCollections)
        snapshot.searchHistory?.takeIf(String::isNotBlank)?.let(SearchHistoryRepository::importAnonymousHistory)
        snapshot.searchHistoryEnabled?.let(SearchHistoryRepository::setEnabled)
        snapshot.watchLater?.takeIf(String::isNotBlank)?.let(WatchLaterRepository::importAnonymousItems)
        snapshot.personalMedia?.takeIf(String::isNotBlank)?.let(PersonalMediaRepository::importAnonymousItems)
        markApplied(key, true)
    }

    private suspend fun uploadPending(key: String): Boolean {
        try {
            check(pendingKey() == key)
            // Older independent addon/plugin uploads must finish before the imported state wins.
            if (withTimeoutOrNull(20_000L) {
                    AddonRepository.awaitPendingCloudPushes()
                    if (AppFeaturePolicy.pluginsEnabled) PluginRepository.awaitPendingCloudPushes()
                    true
                } != true
            ) return false
            check(pendingKey() == key)
            val profileId = ProfileRepository.activeProfileId
            check(ProfileSettingsSync.pushCurrentProfileToRemote())
            check(pendingKey() == key)
            ProviderCredentialSync.pushCurrentProfileToRemote()
            check(pendingKey() == key)
            val addons = AddonRepository.uiState.value.addons
            SupabaseProvider.client.postgrest.rpc("sync_push_addons", buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId()
                put("p_addons", buildJsonArray { addons.forEachIndexed { index, addon -> add(buildJsonObject {
                    put("url", addon.manifestUrl)
                    put("name", addon.userSetName.orEmpty())
                    put("enabled", addon.enabled)
                    put("sort_order", index)
                }) } })
            })
            check(pendingKey() == key)
            if (AppFeaturePolicy.pluginsEnabled) SupabaseProvider.client.postgrest.rpc("sync_push_plugins", buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId()
                put("p_plugins", buildJsonArray { PluginRepository.uiState.value.repositories.forEachIndexed { index, repo -> add(buildJsonObject {
                    put("url", repo.manifestUrl)
                    put("name", repo.name)
                    put("enabled", true)
                    put("sort_order", index)
                }) } })
            })
            check(pendingKey() == key)
            check(HomeCatalogSettingsSyncService.pushCurrentProfileToRemote())
            check(pendingKey() == key)
            val snapshot = decodeValidatedSnapshot(checkNotNull(AnonymousWorkspaceStorage.loadValue(key)))
            snapshot.library?.takeIf(String::isNotBlank)?.let { guest ->
                check(LibraryRepository.reconcileGuestImportWithServer(profileId, guest))
            }
            check(pendingKey() == key)
            check(LibraryRepository.flushPendingToServer(profileId))
            check(pendingKey() == key)
            snapshot.watchedHistory?.takeIf(String::isNotBlank)?.let { guest ->
                check(WatchedRepository.reconcileAnonymousHistoryWithServer(profileId, guest))
            }
            check(pendingKey() == key)
            check(WatchedRepository.flushPendingToServer(profileId))
            check(pendingKey() == key)
            snapshot.watchProgress?.takeIf(String::isNotBlank)?.let { guest ->
                check(WatchProgressRepository.reconcileAnonymousProgressWithServer(profileId, guest))
            }
            check(pendingKey() == key)
            check(WatchProgressRepository.flushPendingToServer(profileId))
            check(pendingKey() == key)
            snapshot.collections?.takeIf(String::isNotBlank)?.let { guest ->
                check(CollectionSyncService.mergeGuestCollectionsToRemote(profileId, guest))
            }
            check(pendingKey() == key)
            AnonymousWorkspaceStorage.saveValue(key, null)
            markApplied(key, false)
            AddonRepository.refreshAll()
            ProfileSettingsSync.startObserving()
            return true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // The local import remains authoritative until all uploads succeed.
            return false
        }
    }
}
