package com.nuvio.app.features

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.remote.DesktopRemote
import com.nuvio.app.features.remote.DesktopRemoteDialogs
import com.nuvio.app.features.remote.RememberedRemoteDevice
import com.nuvio.app.features.remote.RemoteDesktop
import com.nuvio.app.features.streams.SourceFilterAvailability
import com.nuvio.app.features.streams.SourceResultsFilterControls
import com.nuvio.app.features.streams.SourceResultsFilters
import com.nuvio.app.features.streams.StreamQuality
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentityStorage
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.catalog.CatalogSortSettingsStorage
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.library.LibraryDisplaySettingsStorage
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.PlatformMdbListSyncStorage
import com.nuvio.app.features.membership.MemberAssetStorage
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsStorage
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.search.DiscoverSelectionStorage
import com.nuvio.app.features.search.SearchHistoryStorage
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.simkl.SimklAuthStorage
import com.nuvio.app.features.simkl.SimklSyncStorage
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktLibraryStorage
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ResumePromptStorage
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/**
 * Renders the phone-only layouts of the redesigned screens (the Android build, where the desktop
 * branches are off) and saves screenshots to build/starlit-android-capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class StarlitPhoneCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun initializeStorage() {
        // The same storages MainActivity prepares before the first screen draws.
        runCatching { ThemeSettingsStorage.initialize(context) }
        runCatching { SentrySettingsStorage.initialize(context) }
        runCatching { SyncClientIdentityStorage.initialize(context) }
        runCatching { AddonStorage.initialize(context) }
        runCatching { AuthStorage.initialize(context) }
        runCatching { ServerConfigurationStorage.initialize(context) }
        runCatching { LibraryStorage.initialize(context) }
        runCatching { WatchedStorage.initialize(context) }
        runCatching { MetaScreenSettingsStorage.initialize(context) }
        runCatching { com.nuvio.app.features.shuffle.EpisodeShuffleStorage.initialize(context) }
        runCatching { HomeCatalogSettingsStorage.initialize(context) }
        runCatching { PlayerSettingsStorage.initialize(context) }
        runCatching { PlayerTrackPreferenceStorage.initialize(context) }
        runCatching { P2pSettingsStorage.initialize(context) }
        runCatching { ProfileStorage.initialize(context) }
        runCatching { AvatarStorage.initialize(context) }
        runCatching { ProfilePinCacheStorage.initialize(context) }
        runCatching { MemberAssetStorage.initialize(context) }
        runCatching { DiscoverSelectionStorage.initialize(context) }
        runCatching { SearchHistoryStorage.initialize(context) }
        runCatching { SeasonViewModeStorage.initialize(context) }
        runCatching { PosterCardStyleStorage.initialize(context) }
        runCatching { CustomPosterUrlStorage.initialize(context) }
        runCatching { CardDepthStyleStorage.initialize(context) }
        runCatching { DebridSettingsStorage.initialize(context) }
        runCatching { TmdbSettingsStorage.initialize(context) }
        runCatching { MdbListSettingsStorage.initialize(context) }
        runCatching { TraktAuthStorage.initialize(context) }
        runCatching { TraktCommentsStorage.initialize(context) }
        runCatching { TraktLibraryStorage.initialize(context) }
        runCatching { TraktSettingsStorage.initialize(context) }
        runCatching { PlatformMdbListSyncStorage.initialize(context) }
        runCatching { SimklAuthStorage.initialize(context) }
        runCatching { SimklSyncStorage.initialize(context) }
        runCatching { LibraryDisplaySettingsStorage.initialize(context) }
        runCatching { CatalogSortSettingsStorage.initialize(context) }
        runCatching { ContinueWatchingPreferencesStorage.initialize(context) }
        runCatching { ResumePromptStorage.initialize(context) }
        runCatching { ContinueWatchingEnrichmentStorage.initialize(context) }
        runCatching { EpisodeReleaseNotificationsStorage.initialize(context) }
        runCatching { WatchProgressStorage.initialize(context) }
        runCatching { StreamLinkCacheStorage.initialize(context) }
        runCatching { StreamBadgeSettingsStorage.initialize(context) }
        runCatching { BingeGroupCacheStorage.initialize(context) }
        runCatching { PluginStorage.initialize(context) }
        runCatching { CollectionMobileSettingsStorage.initialize(context) }
        runCatching { CollectionStorage.initialize(context) }
        runCatching { DownloadsStorage.initialize(context) }
        runCatching { PlatformLocalAccountDataCleaner.initialize(context) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/starlit-android-capture/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun download(
        id: String,
        status: DownloadStatus,
        show: String? = null,
        episode: Int? = null,
        downloaded: Long = 0,
        total: Long? = null,
    ): DownloadItem {
        val file = File(context.filesDir, "downloads/$id.mp4").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        return DownloadItem(
            id, if (show == null) "movie" else "series", show ?: id, if (show == null) "movie" else "series", id, show ?: id,
            seasonNumber = if (show == null) null else 1, episodeNumber = episode,
            episodeTitle = if (show == null) null else "Episode $episode",
            streamTitle = "1080p WEB-DL", providerName = "Fixture", sourceUrl = "https://example.test/$id.mp4",
            localFileUri = if (status == DownloadStatus.Completed) file.toURI().toString() else null,
            destinationDirectory = file.parentFile!!.absolutePath, fileName = file.name, status = status,
            downloadedBytes = downloaded, totalBytes = total,
            createdAtEpochMs = 0, updatedAtEpochMs = 0,
        )
    }

    @Test
    fun downloadsPhoneLayout() {
        val gib = 1024L * 1024L * 1024L
        val items = listOf(
            download("Dune Part Two", DownloadStatus.Paused, downloaded = 2 * gib, total = 5 * gib),
            download("Arrival", DownloadStatus.Failed),
            download("Past Lives", DownloadStatus.Completed, downloaded = 3 * gib, total = 3 * gib),
            download("Severance S2E1", DownloadStatus.Completed, show = "Severance", episode = 1, downloaded = gib, total = gib),
            download("Severance S2E2", DownloadStatus.Completed, show = "Severance", episode = 2, downloaded = gib, total = gib),
        )
        DownloadsRepository.clearLocalState()
        DownloadsStorage.savePayload("{\"items\":" + Json.encodeToString(items) + "}")
        screen { DownloadsScreen(onBack = {}, onOpenDownload = {}) }
        capture("downloads")
        compose.onNodeWithText("Select").performClick()
        capture("downloads-selecting")
    }

    @Test
    fun libraryCloudTab() {
        screen { LibraryScreen() }
        compose.onNodeWithText("Cloud files").performClick()
        capture("library-cloud")
    }

    @Test
    fun sourceFiltersOnPhone() {
        screen {
            var filters by remember {
                mutableStateOf(SourceResultsFilters(hdrOnly = true, minSeeders = 50))
            }
            SourceResultsFilterControls(
                filters = filters,
                availability = SourceFilterAvailability(
                    qualityCounts = mapOf(StreamQuality.entries[0] to 3, StreamQuality.entries[1] to 14, StreamQuality.entries[2] to 7),
                    maxSeeders = 1200, hasHdr = true, hasAtmos = true, hasSurround = true, hasSize = true,
                ),
                onFiltersChange = { filters = it },
                resultCount = 4,
            )
        }
        capture("source-filters")
    }

    @Test
    fun pcControllerOnPhone() {
        DesktopRemote.desktops.value = listOf(RemoteDesktop("192.168.1.50", 47789, "Living room PC"))
        DesktopRemote.remembered.value = listOf(RememberedRemoteDevice("Office PC", "192.168.1.60", 0L, "AA:BB:CC:DD:EE:FF"))
        DesktopRemote.visible.value = true
        screen { DesktopRemoteDialogs(desktopLayout = false, inline = true) }
        capture("pc-controller-pcs")
    }
}
