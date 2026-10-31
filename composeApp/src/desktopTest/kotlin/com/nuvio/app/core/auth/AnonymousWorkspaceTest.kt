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
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.ProfilePushPayload
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            assertFalse(LocalSettingsImport.importIntoCurrentProfile().getOrThrow()) // Offline cloud upload.
            assertTrue(LocalSettingsImport.hasPendingImport())
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
            AuthRepository.signOut() // Remote is unavailable; active local data must still be cleaned.
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthStorage.loadImportedSettingsScope())
            AuthRepository.acceptRemoteSession("account-b", "b@example.com")
            loadAccountProfile("account-b")
            assertFalse(LocalSettingsImport.hasPendingImport())
            assertNull(PlayerSettingsStorage.loadPreferredAudioLanguage())

            AuthRepository.signOut()
            AuthRepository.acceptRemoteSession("account-a", "a@example.com")
            loadAccountProfile("account-a")
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
