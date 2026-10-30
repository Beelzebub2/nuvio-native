package com.nuvio.app.features.home

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCatalogSettingsRepositoryTest {
    private lateinit var preferences: RecordingPreferences
    private lateinit var originalLocale: Locale

    @BeforeTest
    fun initialize() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("nuvio_home_catalog_settings", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        context.getSharedPreferences("nuvio_install_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferences = RecordingPreferences(stored)
        HomeCatalogSettingsStorage.initialize(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "nuvio_home_catalog_settings") preferences
                else super.getSharedPreferences(name, mode)
        })
        CollectionRepository.clearLocalState()
        HomeRepository.clear()
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.setHeroEnabled(false)
    }

    @AfterTest
    fun clearState() {
        Locale.setDefault(originalLocale)
        HomeCatalogSettingsRepository.clearLocalState()
        CollectionRepository.clearLocalState()
        HomeRepository.clear()
    }

    @Test
    fun unchangedCatalogsDoNotRewriteSettings(): Unit = runBlocking {
        val addons = listOf(addon())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        val editCount = preferences.editCount

        HomeCatalogSettingsRepository.syncCatalogs(addons.map { it.copy() })

        assertEquals(editCount, preferences.editCount)
        assertEquals("test:movie:popular", HomeCatalogSettingsRepository.uiState.value.items.single().key)
    }

    @Test
    fun unchangedCollectionsDoNotRewriteSettings(): Unit = runBlocking {
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCollections(collections)
        val editCount = preferences.editCount

        HomeCatalogSettingsRepository.syncCollections(collections.map { it.copy() })

        assertEquals(editCount, preferences.editCount)
        assertEquals("Favorites", HomeCatalogSettingsRepository.uiState.value.items.single().defaultTitle)
    }

    @Test
    fun collectionSyncReappliesInputsAfterCatalogSyncReplacedCollections(): Unit = runBlocking {
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCollections(collections)
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon()))
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.none { it.isCollection })

        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(
            listOf("Favorites"),
            HomeCatalogSettingsRepository.uiState.value.items.filter { it.isCollection }.map { it.defaultTitle },
        )
    }

    @Test
    fun catalogSyncReappliesInputsAfterCollectionSyncReplacedCollections(): Unit = runBlocking {
        val addons = listOf(addon())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.any { it.isCollection })

        HomeCatalogSettingsRepository.syncCatalogs(addons)

        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.none { it.isCollection })
        assertEquals("test:movie:popular", HomeCatalogSettingsRepository.uiState.value.items.single().key)
    }

    @Test
    fun catalogSyncPreservesPendingCollectionHeroSourceChanges(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_addons", "nuvio_collections", "nuvio_collection_mobile_settings").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AddonStorage.initialize(context)
        CollectionStorage.initialize(context)
        CollectionMobileSettingsStorage.initialize(context)
        AddonRepository.clearLocalState()
        CollectionMobileSettingsRepository.clearLocalState()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"id":"test","name":"Catalogs","description":"","version":"1","resources":["catalog"],
                 "types":["movie"],"catalogs":[{"type":"movie","id":"old"},{"type":"movie","id":"new"}]}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"old","type":"movie","name":"Old"}]}"""))
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"new","type":"movie","name":"New"}]}"""))
            try {
                AddonStorage.saveInstalledAddonUrls(ProfileRepository.activeProfileId, listOf(server.url("/manifest.json").toString()))
                AddonRepository.initialize()
                withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
                val addons = AddonRepository.uiState.value.addons
                assertNotNull(addons.single().manifest)
                HomeCatalogSettingsRepository.setHeroEnabled(true)
                val initialCollection = collection().copy(
                    folders = listOf(CollectionFolder(
                        id = "movies",
                        title = "Movies",
                        sources = listOf(CollectionSource(addonId = "test", type = "movie", catalogId = "old")),
                    )),
                )
                CollectionRepository.setCollections(listOf(initialCollection))
                HomeCatalogSettingsRepository.syncCatalogs(addons)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)
                withTimeout(5_000) { HomeRepository.uiState.first { it.heroItems.singleOrNull()?.id == "old" } }
                val settings = HomeCatalogSettingsRepository.uiState.value
                CollectionRepository.updateCollection(initialCollection.copy(
                    folders = initialCollection.folders.map { folder ->
                        folder.copy(sources = listOf(CollectionSource(addonId = "test", type = "movie", catalogId = "new")))
                    },
                ))

                HomeCatalogSettingsRepository.syncCatalogs(addons)
                val editCount = preferences.editCount
                assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
                assertEquals("old", HomeRepository.uiState.value.heroItems.single().id)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)

                withTimeout(5_000) { HomeRepository.uiState.first { it.heroItems.singleOrNull()?.id == "new" } }
                assertEquals(editCount, preferences.editCount)
                HomeCatalogSettingsRepository.syncCatalogs(addons)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)
                assertEquals(editCount, preferences.editCount)
                assertEquals(3, server.requestCount)
            } finally {
                HomeRepository.clear()
                AddonRepository.clearLocalState()
                CollectionMobileSettingsRepository.clearLocalState()
            }
        }
    }

    @Test
    fun collectionSourceChangesAreAppliedWithTheSameTitleAndFolderCount(): Unit = runBlocking {
        val collection = collection()
        HomeCatalogSettingsRepository.syncCollections(listOf(collection))
        val editCount = preferences.editCount
        val settings = HomeCatalogSettingsRepository.uiState.value
        val changed = collection.copy(
            folders = collection.folders.map { folder ->
                folder.copy(sources = listOf(CollectionSource(addonId = "other", type = "movie", catalogId = "new")))
            },
        )

        HomeCatalogSettingsRepository.syncCollections(listOf(changed))

        assertEquals(editCount, preferences.editCount)
        assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
    }

    @Test
    fun localeChangesRebuildCatalogAndCollectionLabels(): Unit = runBlocking {
        val addons = listOf(addon())
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)
        val editCount = preferences.editCount

        Locale.setDefault(Locale.FRENCH)
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(editCount, preferences.editCount)
    }

    @Test
    fun profileChangesRebuildTheSameCatalogAndCollectionInputs(): Unit = runBlocking {
        val addons = listOf(addon())
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)
        val settings = HomeCatalogSettingsRepository.uiState.value

        HomeCatalogSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
    }

    @Test
    fun firstRunAsksAboutAiringNowAndRemembersTheAnswer() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_home_catalog_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        HomeCatalogSettingsRepository.clearLocalState()

        HomeCatalogSettingsRepository.snapshot()
        assertTrue(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.builtInAiringPreferenceLoaded)

        HomeCatalogSettingsRepository.setShowBuiltInAiring(false)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)

        HomeCatalogSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.snapshot()
        assertFalse(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)

        // Editing the layout of an existing profile must not change the first-install answer.
        HomeCatalogSettingsRepository.setShowBuiltInAiring(true)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)

        // Sign-out can clear all account settings without clearing the installation choice.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_home_catalog_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.snapshot()
        assertFalse(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)

        HomeCatalogSettingsRepository.resetToDefaults()
        assertTrue(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)
        assertEquals(false, HomeCatalogSettingsStorage.loadBuiltInAiringChoice())
    }

    @Test
    fun legacyHomeSettingsKeepAiringNowEnabledWithoutPrompting() {
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsStorage.savePayload(
            """{"heroEnabled":false,"showCatalogType":true,"items":[]}""",
        )

        HomeCatalogSettingsRepository.snapshot()
        assertTrue(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)

        HomeCatalogSettingsRepository.setShowBuiltInAiring(false)
        HomeCatalogSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.snapshot()
        assertFalse(HomeCatalogSettingsRepository.uiState.value.showBuiltInAiring)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.askToShowBuiltInAiring)
    }

    private fun addon() = ManagedAddon(
        manifestUrl = "https://example.com/manifest.json",
        manifest = AddonManifest(
            id = "test",
            name = "Catalogs",
            description = "",
            version = "1",
            resources = emptyList(),
            types = listOf("movie"),
            catalogs = listOf(AddonCatalog(type = "movie", id = "popular", name = "Popular")),
            transportUrl = "https://example.com",
        ),
    )

    private fun collection() = Collection(
        id = "favorites",
        title = "Favorites",
        folders = listOf(CollectionFolder(id = "movies", title = "Movies")),
    )

    private class RecordingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var editCount = 0

        override fun edit(): SharedPreferences.Editor {
            editCount += 1
            return delegate.edit()
        }
    }
}
