package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.navigation.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class StreamStartupRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun hungEpisodeMetadataFallsBackToSourceSelectionWithoutRestarting() {
        val launchId = StreamLaunchStore.put(StreamLaunch(1, "series", "playable-video", "test-anime", "series", "Test Anime",
            seasonNumber = 1, episodeNumber = 2, manualSelection = true))
        val route = StreamRoute(launchId, "Test Anime")
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute, route))
        var cancelled = false
        var started = false
        try {
            compose.setContent { NuvioTheme { Surface { Box(Modifier.size(1000.dp, 760.dp)) {
                StreamDestination(route, navigator, p2pEnabled = true, openExternalPlayback = { false }, openExternalStreamUrl = { false },
                    onLandscapeLoadingChanged = {}, fetchEpisodeMeta = { _, _ -> started = true; try { awaitCancellation() } finally { cancelled = true } })
            } } } }
            compose.waitUntil(5_000L) { started }
            compose.mainClock.advanceTimeBy(8_500L)
            // Desktop coroutine timeouts use wall time, independently of the frame clock.
            compose.waitUntil(12_000L) { cancelled }
            compose.onNodeWithTag("stream-source-picker", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(navigator.currentRoute == route)
        } finally {
            StreamsRepository.cancelLoading()
            StreamLaunchStore.remove(launchId)
            PluginRepository.setLocalPluginSearchPaused(false)
        }
    }
}
