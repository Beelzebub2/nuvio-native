package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.AnonymousWorkspaceStorage
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.downloads.DownloadDesktopFeatures
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.library.LibraryStoragePayloadCodec
import com.nuvio.app.features.library.StoredLibraryPayload
import com.nuvio.app.features.watched.StoredWatchedPayload
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.WatchProgressCodec
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.ProfilePushPayload
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertSame
import kotlin.test.assertNotEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonymousWorkspaceTest {
    // Importing a workspace applies its app language to the whole JVM; put it back so
    // later tests keep the default language.
    private val themeStore = DesktopStorage.store("nuvio_theme_settings")
    private var originalLocale: Locale = Locale.getDefault()
    private var originalAppLanguage: String? = null

    @BeforeTest
    fun rememberAppLanguage() {
        originalLocale = Locale.getDefault()
        originalAppLanguage = themeStore.getString("selected_app_language")
    }

    @AfterTest
    fun restoreAppLanguage() {
        val language = originalAppLanguage
        if (language == null) themeStore.remove("selected_app_language")
        else themeStore.putString("selected_app_language", language)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun unopenedAccountProfileDownloadsSurviveTwoAccountWipesAndDoNotResurrectExplicitlyClearedQueue() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        ServerConfigurationRepository.saveCustom(ServerConfiguration("", "", ServerCapabilities(true, false), true))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        try {
            val accountId = "multi-profile-account-backup"
            val backendUrl = ServerConfigurationRepository.active.value.backendUrl
            LocalSettingsImport.forgetAccountDownloadBackups(backendUrl, accountId)
            val profilePayload = """{"userId":"$accountId","profiles":[{"profile_index":1,"name":"First"},{"profile_index":2,"name":"Second"}]}"""
            AuthRepository.acceptRemoteSession(accountId, "multi@example.com")
            ProfileStorage.savePayload(profilePayload)
            ProfileRepository.ensureLoaded(accountId)
            ProfileRepository.switchToProfile(2)
            val item = DownloadItem(
                id = "saved-on-profile-two", contentType = "movie", parentMetaId = "saved-film",
                parentMetaType = "movie", videoId = "saved-film", title = "Saved film",
                streamTitle = "Offline", providerName = "Account", sourceUrl = "",
                fileName = "saved-film.mp4", status = DownloadStatus.Paused,
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            )
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(item)))
            DownloadsRepository.clearLocalState()
            AuthRepository.signOut()

            // Only reopen profile 1: the saved profile 2 record must remain in the
            // account-scoped backup even though the live profile 2 store is absent.
            AuthRepository.acceptRemoteSession(accountId, "multi@example.com")
            ProfileStorage.savePayload(profilePayload)
            ProfileRepository.ensureLoaded(accountId)
            ProfileRepository.switchToProfile(1)
            assertTrue(DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).isEmpty())
            AuthRepository.signOut()

            AuthRepository.acceptRemoteSession(accountId, "multi@example.com")
            ProfileStorage.savePayload(profilePayload)
            ProfileRepository.ensureLoaded(accountId)
            ProfileRepository.switchToProfile(2)
            assertEquals(listOf(item.id), DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).map { it.id })

            // Explicit empty JSON is a deliberate deletion and supersedes older backups.
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(emptyList()))
            DownloadsRepository.clearLocalState()
            AuthRepository.signOut()
            AuthRepository.acceptRemoteSession(accountId, "multi@example.com")
            ProfileStorage.savePayload(profilePayload)
            ProfileRepository.ensureLoaded(accountId)
            ProfileRepository.switchToProfile(2)
            assertTrue(DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).isEmpty())
        } finally {
            AuthRepository.signOut()
            LocalSettingsImport.forgetAccountDownloadBackups(
                ServerConfigurationRepository.active.value.backendUrl, "multi-profile-account-backup",
            )
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
        }
    }

    @Test
    fun legacyGuestDownloadsFromProfileTwoImportIntoAccountProfileOneWithoutReplacingItsQueue() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        ServerConfigurationRepository.saveCustom(ServerConfiguration("", "", ServerCapabilities(true, false), true))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue("userId", null)
        AnonymousWorkspaceStorage.saveValue("import", null)
        try {
            AuthRepository.signInAnonymously()
            val guestId = (AuthRepository.state.value as AuthState.Authenticated).userId
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.pushProfiles(listOf(
                ProfilePushPayload(1, "Primary guest", "#123456"),
                ProfilePushPayload(2, "Guest with downloads", "#654321"),
            ))
            ProfileRepository.switchToProfile(2)
            val guestItem = DownloadItem(
                id = "guest-profile-two", contentType = "series", parentMetaId = "guest-series",
                parentMetaType = "series", videoId = "guest-episode", title = "Guest episode",
                streamTitle = "Guest", providerName = "Guest", sourceUrl = "",
                fileName = "guest-episode.mp4", status = DownloadStatus.Paused,
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            )
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(guestItem)))
            val guestLibrary = LibraryItem(id = "guest-library", type = "movie", name = "Guest Library", savedAtEpochMs = 1L)
            LibraryStorage.savePayload(2, Json.encodeToString(StoredLibraryPayload(items = listOf(guestLibrary))))
            val guestWatched = WatchedItem(id = "guest-watched", type = "movie", name = "Watched Guest", markedAtEpochMs = 1700000000000L)
            WatchedStorage.savePayload(2, Json.encodeToString(StoredWatchedPayload(items = listOf(guestWatched))))
            val guestProgress = WatchProgressEntry(
                contentType = "series", parentMetaId = "guest-series", parentMetaType = "series",
                videoId = "guest-episode", title = "Guest show", lastPositionMs = 1000L,
                durationMs = 5000L, lastUpdatedEpochMs = 1700000000000L,
            )
            WatchProgressStorage.savePayload(2, WatchProgressCodec.encodeEntries(listOf(guestProgress)))
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            AddonRepository.replaceLocalBackup(addonBackup)
            PluginRepository.importLocalSnapshot(pluginSnapshot)

            AuthRepository.acceptRemoteSession("account-with-own-downloads", "existing@example.com")
            loadAccountProfile("account-with-own-downloads") // Destination uses profile 1.
            val saved = Json.decodeFromString<LocalSettingsSnapshot>(AnonymousWorkspaceStorage.loadValue("import")!!)
            // Simulate a snapshot saved by the previous release, with no download fields.
            AnonymousWorkspaceStorage.saveValue("import", Json.encodeToString(saved.copy(
                downloads = null, downloadFolder = null, downloadProfileId = null,
                library = null, watchedHistory = null, watchProgress = null,
            )))

            val accountItem = guestItem.copy(
                id = "account-original", parentMetaId = "account-series", videoId = "account-episode",
                title = "Account episode", fileName = "account-episode.mp4",
            )
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(accountItem)))
            val accountLibrary = LibraryItem(id = "account-library", type = "movie", name = "Account Library", savedAtEpochMs = 2L)
            LibraryStorage.savePayload(1, Json.encodeToString(StoredLibraryPayload(items = listOf(accountLibrary))))
            LibraryRepository.clearLocalState()
            LibraryRepository.ensureLoaded()
            DownloadsRepository.clearLocalState()
            assertFalse(LocalSettingsImport.importIntoCurrentProfile().getOrThrow())
            val pendingKey = "pending:${ServerConfigurationRepository.active.value.backendUrl}:account-with-own-downloads:1"
            val pending = Json.decodeFromString<LocalSettingsSnapshot>(assertNotNull(AnonymousWorkspaceStorage.loadValue(pendingKey)))
            assertEquals(2, pending.downloadProfileId)
            assertEquals(listOf("guest-profile-two"), DownloadsCodec.decodeItems(pending.downloads).map { it.id })
            assertEquals(listOf("guest-library"), LibraryStoragePayloadCodec.decodeStrict(pending.library!!).items.map { it.id })
            assertEquals(listOf("guest-watched"), Json.decodeFromString<StoredWatchedPayload>(pending.watchedHistory!!).items.map { it.id })
            assertEquals(listOf("guest-episode"), WatchProgressCodec.decodeEntries(pending.watchProgress!!).map { it.videoId })
            assertEquals(setOf("account-original", "guest-profile-two"), DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).map { it.id }.toSet())
            assertEquals(setOf("guest-library", "account-library"), LibraryStoragePayloadCodec.decodeStrict(LibraryStorage.loadPayload(1)!!).items.map { it.id }.toSet())
            assertEquals(listOf("guest-watched"), Json.decodeFromString<StoredWatchedPayload>(WatchedStorage.loadPayload(1)!!).items.map { it.id })
            assertEquals(listOf("guest-episode"), WatchProgressCodec.decodeEntries(WatchProgressStorage.loadPayload(1)!!).map { it.videoId })
        } finally {
            AuthRepository.signOut()
            AnonymousWorkspaceStorage.saveValue("userId", null)
            AnonymousWorkspaceStorage.saveValue("import", null)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
        }
    }

    @Test
    fun pendingImportClearsOnlyAfterEveryCloudWriteAndRetriesALateFailure() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        val rejectPlugins = AtomicBoolean(true)
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val pendingDuringWrites = Collections.synchronizedList(mutableListOf<Boolean>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val rpc = request.requestURI.path.substringAfterLast('/')
            val body = request.requestBody.bufferedReader().use { it.readText() }
            if (rpc.startsWith("sync_push_")) {
                calls += rpc to body
                pendingDuringWrites += LocalSettingsImport.hasPendingImport()
            }
            val reject = rpc == "sync_push_plugins" && rejectPlugins.get()
            val response = if (reject) """{"message":"temporarily unavailable"}""" else "[]"
            request.responseHeaders.set("Content-Type", "application/json")
            request.sendResponseHeaders(if (reject) 503 else 200, response.toByteArray().size.toLong())
            request.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        ServerConfigurationRepository.saveCustom(ServerConfiguration(
            "http://127.0.0.1:${server.address.port}", "test-key", ServerCapabilities(true, false), true,
        ))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue("userId", null)
        try {
            AuthRepository.signInAnonymously()
            val guestId = (AuthRepository.state.value as AuthState.Authenticated).userId
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.pullProfiles()
            PlayerSettingsStorage.saveIntroDbApiKey("guest-secret")
            PlayerSettingsRepository.onProfileChanged()
            AddonRepository.replaceLocalBackup(addonBackup)
            PluginRepository.importLocalSnapshot(pluginSnapshot)
            AuthRepository.acceptRemoteSession("upload-account", "upload@example.com")
            loadAccountProfile("upload-account")

            assertFalse(LocalSettingsImport.importIntoCurrentProfile().getOrThrow())
            assertTrue(LocalSettingsImport.hasPendingImport())
            assertEquals(listOf("sync_push_profile_settings_blob", "sync_push_provider_credentials", "sync_push_addons", "sync_push_plugins"), calls.map { it.first })
            assertTrue(pendingDuringWrites.all { it })
            assertFalse(calls.first().second.contains("guest-secret"))
            assertTrue(calls.first { it.first == "sync_push_provider_credentials" }.second.contains("guest-secret"))

            calls.clear()
            PlayerSettingsStorage.savePreferredAudioLanguage("de")
            PlayerSettingsRepository.onProfileChanged()
            val repeatStages = mutableListOf<LocalImportStage>()
            val initiatingThread = Thread.currentThread()
            assertFalse(LocalSettingsImport.importIntoCurrentProfile({ stage, _ ->
                assertNotEquals(initiatingThread, Thread.currentThread(), "Import work must leave the caller thread")
                repeatStages += stage
            }).getOrThrow())
            assertEquals("de", PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertFalse(LocalImportStage.Preferences in repeatStages, "Retry must not reset already-imported preferences")
            assertTrue(LocalImportStage.SyncSettings in repeatStages)
            calls.clear()
            rejectPlugins.set(false)
            assertTrue(LocalSettingsImport.retryPendingImport(1))
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertEquals(listOf("sync_push_profile_settings_blob", "sync_push_provider_credentials", "sync_push_addons", "sync_push_plugins", "sync_push_home_catalog_settings"), calls.map { it.first })
            assertTrue(pendingDuringWrites.all { it })
            assertNull(AuthStorage.loadImportedSettingsScope())
            val corruptedKey = "pending:${ServerConfigurationRepository.active.value.backendUrl}:upload-account:1"
            AnonymousWorkspaceStorage.saveValue(corruptedKey, "{invalid-json")
            assertTrue(LocalSettingsImport.retryPendingImport(1))
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertTrue(LocalSettingsImport.hasDamagedPendingImport())
            assertEquals("{invalid-json", AnonymousWorkspaceStorage.loadValue("damaged:$corruptedKey"))
            AnonymousWorkspaceStorage.saveValue("damaged:$corruptedKey", null)
            val guestSnapshot = Json.decodeFromString<LocalSettingsSnapshot>(AnonymousWorkspaceStorage.loadValue("import")!!)
            AnonymousWorkspaceStorage.saveValue(corruptedKey, Json.encodeToString(guestSnapshot.copy(addons = "{bad-addon-json")))
            assertTrue(LocalSettingsImport.retryPendingImport(1))
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertEquals(
                Json.encodeToString(guestSnapshot.copy(addons = "{bad-addon-json")),
                AnonymousWorkspaceStorage.loadValue("damaged:$corruptedKey"),
            )
            AnonymousWorkspaceStorage.saveValue("damaged:$corruptedKey", null)
        } finally {
            ProfileSettingsSync.clearAccountState()
            AuthRepository.signOut()
            AnonymousWorkspaceStorage.saveValue("userId", null)
            AnonymousWorkspaceStorage.saveValue("import", null)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
            server.stop(0)
        }
    }

    @Test
    fun anonymousWorkspaceSurvivesLogoutAndExplicitImportStaysSeparateFromSignedAccounts() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        val guestDownloadFolder = Files.createTempDirectory("guest-download-folder").toFile()
        ServerConfigurationRepository.saveCustom(ServerConfiguration("", "", ServerCapabilities(true, false), true))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue("userId", null)
        AnonymousWorkspaceStorage.saveValue("import", null)
        try {
            AuthRepository.signInAnonymously()
            val guestId = (AuthRepository.state.value as AuthState.Authenticated).userId
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.pushProfiles(listOf(ProfilePushPayload(1, "Guest", "#123456"), ProfilePushPayload(2, "Second guest", "#654321")))
            ProfileRepository.switchToProfile(2)
            PlayerSettingsStorage.savePreferredAudioLanguage("fr")
            PluginStorage.saveScraperSettings("test-provider", "{\"language\":\"fr\"}")
            ProfileRepository.switchToProfile(1)
            PlayerSettingsStorage.savePreferredAudioLanguage("pt")
            PlayerSettingsStorage.saveIntroDbApiKey("guest-api-key")
            ThemeSettingsStorage.saveSelectedAppLanguage("pt")
            PlayerSettingsRepository.onProfileChanged()
            AddonRepository.replaceLocalBackup(addonBackup)
            PluginRepository.importLocalSnapshot(pluginSnapshot)
            DesktopStorage.store("nuvio_player_settings").putString("removed-setting", "old")
            DesktopStorage.store("desktop_download_preferences").putString("folder", guestDownloadFolder.canonicalPath)
            DesktopStorage.store("nuvio_downloads").putString("downloads_1", """{"items":[]}""")

            AuthRepository.signOut().getOrThrow()
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthStorage.loadAnonymousUserId())
            AuthRepository.signInAnonymously()
            assertEquals(guestId, (AuthRepository.state.value as AuthState.Authenticated).userId)
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.switchToProfile(1)
            assertEquals("pt", PlayerSettingsStorage.loadPreferredAudioLanguage())
            PluginRepository.initialize()
            assertEquals("function getStreams() { return []; }", PluginRepository.uiState.value.scrapers.single().code)
            assertFalse(PluginRepository.uiState.value.scrapers.single().enabled)
            assertEquals("{\"language\":\"pt\"}", PluginStorage.loadScraperSettings("test-provider"))
            assertEquals("Guest", ProfileRepository.state.value.activeProfile?.name)
            // A replacement snapshot must not resurrect values removed after the previous save.
            DesktopStorage.store("nuvio_player_settings").remove("removed-setting")

            // This is also the transition used by email and device-link sessions without a prior logout.
            AuthRepository.acceptRemoteSession("account-a", "a@example.com")
            loadAccountProfile("account-a")
            assertNull(PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            assertTrue(PluginRepository.uiState.value.repositories.isEmpty())
            assertTrue(LocalSettingsImport.isAvailable)
            // Guest data is available for explicit import after account cleanup, without
            // first restoring it over the signed-in account's own preferences.
            assertEquals(guestDownloadFolder.canonicalPath, AnonymousWorkspaceStorage.loadSavedPreference("desktop_download_preferences", "folder"))
            assertEquals("""{"items":[]}""", AnonymousWorkspaceStorage.loadSavedPreference("nuvio_downloads", "downloads_1"))
            assertNull(DesktopStorage.store("desktop_download_preferences").getString("folder"))

            // Emulate an earlier release's import JSON, which omitted download metadata.
            // The original guest properties remain saved in the anonymous backup slot.
            val earlierSnapshot = Json.decodeFromString<LocalSettingsSnapshot>(AnonymousWorkspaceStorage.loadValue("import")!!)
            AnonymousWorkspaceStorage.saveValue("import", Json.encodeToString(earlierSnapshot.copy(
                downloads = null, downloadFolder = null, downloadProfileId = null,
            )))
            assertFalse(LocalSettingsImport.importIntoCurrentProfile().getOrThrow()) // Offline cloud upload.
            assertTrue(LocalSettingsImport.hasPendingImport())
            val importKey = "pending:${ServerConfigurationRepository.active.value.backendUrl}:account-a:1"
            val restored = Json.decodeFromString<LocalSettingsSnapshot>(AnonymousWorkspaceStorage.loadValue(importKey)!!)
            assertEquals("""{"items":[]}""", restored.downloads)
            assertEquals(guestDownloadFolder.canonicalPath, restored.downloadFolder)
            assertEquals(1, restored.downloadProfileId)
            assertEquals(guestDownloadFolder.canonicalPath, DownloadDesktopFeatures.directory())
            assertEquals("pt", PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertEquals("guest-api-key", PlayerSettingsStorage.loadIntroDbApiKey())
            assertEquals("pt", ThemeSettingsStorage.loadSelectedAppLanguage())
            assertFalse(AddonRepository.uiState.value.addons.single().enabled)
            assertEquals("My addon", AddonRepository.uiState.value.addons.single().userSetName)
            assertEquals("test-provider", PluginRepository.uiState.value.scrapers.single().id)
            assertFalse(ProfileSettingsSync.pull(1))
            AddonRepository.pullFromServer(1)
            PluginRepository.pullFromServer(1)
            assertEquals(1, AddonRepository.uiState.value.addons.size)
            assertEquals(1, PluginRepository.uiState.value.scrapers.size)

            PlayerSettingsStorage.savePreferredAudioLanguage("de")
            PlayerSettingsRepository.onProfileChanged()
            val accountDownload = DownloadItem(
                id = "account-a-local", contentType = "movie", parentMetaId = "account-movie",
                parentMetaType = "movie", videoId = "account-film", title = "Account-owned download",
                streamTitle = "Account", providerName = "Account", sourceUrl = "",
                fileName = "account-film.mp4", status = DownloadStatus.Paused,
                createdAtEpochMs = 2L, updatedAtEpochMs = 2L,
            )
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(listOf(accountDownload)))
            DownloadsRepository.clearLocalState()
            AuthRepository.signOut() // Remote is unavailable; active local data must still be cleaned.
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthStorage.loadImportedSettingsScope())
            AuthRepository.acceptRemoteSession("account-b", "b@example.com")
            loadAccountProfile("account-b")
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertNull(PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertTrue(DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).isEmpty())
            assertNull(DesktopStorage.store("desktop_download_preferences").getString("folder"))

            AuthRepository.signOut()
            AuthRepository.acceptRemoteSession("account-a", "a@example.com")
            loadAccountProfile("account-a")
            assertEquals(listOf(accountDownload.id), DownloadsCodec.decodeItems(DownloadsStorage.loadPayload()).map { it.id })
            assertEquals(guestDownloadFolder.canonicalPath, DownloadDesktopFeatures.directory())
            assertTrue(LocalSettingsImport.hasPendingImport())
            assertFalse(LocalSettingsImport.retryPendingImport(1))
            assertEquals("de", PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertEquals(1, PluginRepository.uiState.value.scrapers.size)

            AuthRepository.signOut()
            AuthRepository.signInAnonymously()
            assertEquals(guestId, (AuthRepository.state.value as AuthState.Authenticated).userId)
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.switchToProfile(1)
            assertEquals("pt", PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertNull(DesktopStorage.store("nuvio_player_settings").getString("removed-setting"))
            ProfileRepository.switchToProfile(2)
            assertEquals("fr", PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertEquals("{\"language\":\"fr\"}", PluginStorage.loadScraperSettings("test-provider"))
            assertTrue(AuthRepository.signOutIfSessionInvalid(IllegalStateException("JWT expired"), "test"))
            AuthRepository.signInAnonymously()
            assertEquals(guestId, (AuthRepository.state.value as AuthState.Authenticated).userId)
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.switchToProfile(2)
            assertEquals("fr", PlayerSettingsStorage.loadPreferredAudioLanguage())
            // A corrupt local addon prevents a new snapshot, but cannot keep a revoked session active.
            AddonStorage.saveInstalledAddonUrls(2, listOf("invalid://addon"))
            AddonRepository.clearLocalState()
            assertTrue(AuthRepository.signOutIfSessionInvalid(IllegalStateException("JWT expired"), "test"))
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthStorage.loadAnonymousUserId())
            AuthRepository.signInAnonymously()
            assertEquals(guestId, (AuthRepository.state.value as AuthState.Authenticated).userId)
            ProfileRepository.ensureLoaded(guestId)
            ProfileRepository.switchToProfile(2)
            AddonStorage.saveInstalledAddonUrls(2, listOf("invalid://addon"))
            AddonRepository.clearLocalState()
            AuthRepository.acceptRemoteSession("account-during-failed-backup", "new@example.com")
            assertEquals(guestId, (AuthRepository.state.value as AuthState.Authenticated).userId)
            assertEquals(guestId, AuthStorage.loadAnonymousUserId())
            assertTrue(AuthRepository.error.value != null)
            AddonStorage.saveInstalledAddonUrls(2, emptyList())
            AddonRepository.clearLocalState()
            AuthRepository.clearError()
        } finally {
            AuthRepository.signOut()
            AnonymousWorkspaceStorage.saveValue("userId", null)
            AnonymousWorkspaceStorage.saveValue("import", null)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
            guestDownloadFolder.delete()
        }
    }

    @Test
    fun repeatedImportCancelsLoadedRefreshesSafelyAndSurvivesItsUiCaller() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            if (request.requestURI.path.endsWith("sync_push_profile_settings_blob")) {
                entered.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
            val body = "[]".toByteArray()
            request.responseHeaders.set("Content-Type", "application/json")
            request.sendResponseHeaders(200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        ServerConfigurationRepository.saveCustom(ServerConfiguration(
            "http://127.0.0.1:${server.address.port}", "test-key", ServerCapabilities(true, false), true,
        ))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue("userId", null)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AuthRepository.signInAnonymously()
            val guestId = (AuthRepository.state.value as AuthState.Authenticated).userId
            ProfileRepository.ensureLoaded(guestId)
            PlayerSettingsStorage.saveIntroDbApiKey("never-show-this-secret")
            AddonRepository.replaceLocalBackup(addonBackup)
            PluginRepository.importLocalSnapshot(pluginSnapshot)
            AuthRepository.acceptRemoteSession("repeat-import-account", "repeat@example.com")
            loadAccountProfile("repeat-import-account")
            AddonRepository.initialize()
            PluginRepository.initialize()
            // Completion handlers remove their own registration, just like real refresh jobs.
            // Previously cancelActiveRefreshes iterated this same map and threw mid-import.
            for (repository in listOf(AddonRepository, PluginRepository)) {
                val field = repository.javaClass.getDeclaredField("activeRefreshJobs").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val jobs = field.get(repository) as MutableMap<String, Job>
                repeat(4) { index ->
                    val key = "loaded-refresh-$index"
                    jobs[key] = Job().apply { invokeOnCompletion { jobs.remove(key); canceled.incrementAndGet() } }
                }
            }
            val started = CompletableDeferred<Job>()
            uiScope.launch { started.complete(checkNotNull(LocalSettingsImport.startImport())) }
            val operation = started.await()
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Import should reach its first cloud upload")
            assertSame(operation, LocalSettingsImport.startImport(), "Duplicate clicks must share the running import")
            uiScope.cancel()
            assertTrue(operation.isActive, "Disposing the settings UI must not cancel the import")
            assertEquals(8, canceled.get())
            assertEquals(LocalImportStage.SyncSettings, LocalSettingsImport.progress.value?.stage)
            assertFalse(LocalSettingsImport.progress.value.toString().contains("never-show-this-secret"))
            release.countDown()
            withTimeout(15_000) { operation.join() }
            assertEquals(LocalImportOutcome.Synced, LocalSettingsImport.progress.value?.outcome)
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertEquals(1, LocalSettingsImport.progress.value?.summary?.addons)
            LocalSettingsImport.dismissProgress()
            assertNull(LocalSettingsImport.progress.value)
            // Invalid input must not leave ordinary settings sync disabled.
            AnonymousWorkspaceStorage.saveValue("import", "{damaged")
            withTimeout(15_000) { checkNotNull(LocalSettingsImport.startImport()).join() }
            assertEquals(LocalImportOutcome.Failed, LocalSettingsImport.progress.value?.outcome)
            val observerField = ProfileSettingsSync.javaClass.getDeclaredField("observeJob").apply { isAccessible = true }
            assertTrue((observerField.get(ProfileSettingsSync) as? Job)?.isActive == true)
            LocalSettingsImport.dismissProgress()
        } finally {
            release.countDown()
            uiScope.cancel()
            LocalSettingsImport.startImport(retry = true)?.join()
            LocalSettingsImport.dismissProgress()
            AuthRepository.signOut()
            AnonymousWorkspaceStorage.saveValue("userId", null)
            AnonymousWorkspaceStorage.saveValue("import", null)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
            server.stop(0)
        }
    }

    @Test
    fun accountChangeBeforeAnImportStageCannotWriteGuestSettingsIntoTheNewAccount() = runBlocking {
        val originalServer = ServerConfigurationRepository.active.value
        ServerConfigurationRepository.saveCustom(ServerConfiguration("", "", ServerCapabilities(true, false), true))
        SupabaseProvider.reset()
        LocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.saveValue("userId", null)
        try {
            AuthRepository.signInAnonymously()
            val guestId = (AuthRepository.state.value as AuthState.Authenticated).userId
            ProfileRepository.ensureLoaded(guestId)
            PlayerSettingsStorage.savePreferredAudioLanguage("pt")
            AddonRepository.replaceLocalBackup(addonBackup)
            PluginRepository.importLocalSnapshot(pluginSnapshot)
            AuthRepository.acceptRemoteSession("import-source-account", "first@example.com")
            loadAccountProfile("import-source-account")
            val result = LocalSettingsImport.importIntoCurrentProfile({ stage, _ ->
                if (stage == LocalImportStage.Preferences) runBlocking {
                    AuthRepository.acceptRemoteSession("import-other-account", "other@example.com")
                    loadAccountProfile("import-other-account")
                }
            })
            assertTrue(result.isFailure)
            assertNull(PlayerSettingsStorage.loadPreferredAudioLanguage())
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            assertFalse(LocalSettingsImport.hasPendingImport())
        } finally {
            AuthRepository.signOut()
            AnonymousWorkspaceStorage.saveValue("userId", null)
            AnonymousWorkspaceStorage.saveValue("import", null)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            SupabaseProvider.reset()
        }
    }

    private suspend fun loadAccountProfile(userId: String) {
        ProfileStorage.savePayload("""{"userId":"$userId","profiles":[{"profile_index":1,"name":"Account profile"}]}""")
        ProfileRepository.ensureLoaded(userId)
        ProfileRepository.switchToProfile(1)
    }

    private val addonBackup = """{"format":"starlit-addons","version":1,"addons":[{"manifestUrl":"https://example.com/manifest.json","name":"My addon","enabled":false}]}"""
    private val pluginSnapshot = """{"pluginsEnabled":true,"repositories":[{"manifestUrl":"https://plugins.example/manifest.json","name":"Guest plugins","lastUpdated":9999999999999}],"scrapers":[{"id":"test-provider","repositoryUrl":"https://plugins.example/manifest.json","name":"Provider","description":"","version":"1","filename":"provider.js","supportedTypes":["movie"],"enabled":false,"manifestEnabled":true,"code":"function getStreams() { return []; }"}],"scraperSettings":{"test-provider":"{\"language\":\"pt\"}"}}"""
}
