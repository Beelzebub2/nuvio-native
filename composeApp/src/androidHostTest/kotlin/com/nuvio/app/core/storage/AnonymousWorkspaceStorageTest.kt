package com.nuvio.app.core.storage

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnonymousWorkspaceStorageTest {
    @Test
    fun damagedBackupFallsBackToPreviousCompleteSlotWithoutOverwritingCurrentAccount() {
        val context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        val settings = context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        settings.edit().putString("language_1", "first").commit()
        AnonymousWorkspaceStorage.save("guest-recovery")
        val older = AnonymousWorkspaceStorage.loadValue("slot")!!
        settings.edit().putString("language_1", "second").commit()
        AnonymousWorkspaceStorage.save("guest-recovery")
        val latest = AnonymousWorkspaceStorage.loadValue("slot")!!

        context.getSharedPreferences("anonymous_workspace_${latest}_nuvio_player_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings.edit().putString("language_1", "other-account").commit()
        assertEquals("guest-recovery", AnonymousWorkspaceStorage.restore())
        assertEquals("first", settings.getString("language_1", null))

        context.getSharedPreferences("anonymous_workspace_${older}_nuvio_player_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings.edit().putString("language_1", "other-account").commit()
        assertFailsWith<IllegalStateException> { AnonymousWorkspaceStorage.restore() }
        assertEquals("other-account", settings.getString("language_1", null))
    }

    @Test
    fun backupSurvivesAccountWipesAndRestoresTypedPreferencesAndOfflinePluginCode() {
        val context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        val settings = context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        val auth = context.getSharedPreferences("nuvio_auth", Context.MODE_PRIVATE)
        val downloads = context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE)
        val savedDownloads = """{"items":[{"id":"guest-episode"}]}"""
        downloads.edit().putString("downloads_payload_1", savedDownloads).commit()
        settings.edit().putString("language_1", "pt").putString("language_2", "fr")
            .putFloat("size_1", 21.5f).putBoolean("enabled_1", false)
            .putStringSet("providers_1", setOf("one", "two")).commit()
        auth.edit().putString("anonymous_user_id", "active-session-marker").commit()
        val code = context.filesDir.resolve("nuvio_plugin_scrapers/1/provider.js")
        code.parentFile!!.mkdirs()
        code.writeText("original plugin code")

        AnonymousWorkspaceStorage.save("stable-guest-id")
        PlatformLocalAccountDataCleaner.wipe()
        assertFalse(code.exists())
        assertNull(downloads.getString("downloads_payload_1", null))
        assertEquals(savedDownloads, AnonymousWorkspaceStorage.loadSavedPreference("nuvio_downloads", "downloads_payload_1"))
        settings.edit().putString("language_1", "signed-account-language").commit()
        PlatformLocalAccountDataCleaner.wipe()

        assertEquals("stable-guest-id", AnonymousWorkspaceStorage.restore())
        assertNull(auth.getString("anonymous_user_id", null))
        assertEquals("pt", settings.getString("language_1", null))
        assertEquals("fr", settings.getString("language_2", null))
        assertEquals(21.5f, settings.getFloat("size_1", 0f))
        assertFalse(settings.getBoolean("enabled_1", true))
        assertEquals(setOf("one", "two"), settings.getStringSet("providers_1", null))
        assertEquals("original plugin code", code.readText())
        assertEquals(savedDownloads, downloads.getString("downloads_payload_1", null))

        settings.edit().remove("language_1").commit()
        code.writeText("updated plugin code")
        AnonymousWorkspaceStorage.save("stable-guest-id")
        PlatformLocalAccountDataCleaner.wipe()
        AnonymousWorkspaceStorage.restore()
        assertFalse(settings.contains("language_1"))
        assertEquals("updated plugin code", code.readText())
        assertTrue(code.isFile)
    }

    @Test
    fun oldGuestBackupsWithoutDownloadsPreferencesRemainRestorable() {
        val context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        val settings = context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        settings.edit().putString("language_1", "pt").commit()
        AnonymousWorkspaceStorage.save("older-guest")
        val slot = AnonymousWorkspaceStorage.loadValue("slot")!!
        context.getSharedPreferences("anonymous_workspace_${slot}_nuvio_downloads", Context.MODE_PRIVATE)
            .edit().clear().commit()

        PlatformLocalAccountDataCleaner.wipe()
        assertEquals("older-guest", AnonymousWorkspaceStorage.restore())
        assertEquals("pt", settings.getString("language_1", null))
        assertNull(AnonymousWorkspaceStorage.loadSavedPreference("nuvio_downloads", "downloads_payload_1"))
    }
}
