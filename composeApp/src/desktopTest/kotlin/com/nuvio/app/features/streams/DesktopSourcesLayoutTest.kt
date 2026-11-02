package com.nuvio.app.features.streams

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.RedesignFixtures
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopSourcesLayoutTest {
    private val featured = StreamItem(
        name = "1080p\n(Torrent)\n★★★★★\nAVC / AAC 2.0\nEnglish\nReady to play",
        description = "Clayface (2026)\nAVC · AAC 2.0\n1.45 GB · 1h 32m\nMeteor release\nEnglish · Portuguese\nFull source details remain available",
        url = "https://example.invalid/featured.mp4",
        addonName = "AIOStreams | A very long provider name",
        addonId = "aio",
        behaviorHints = StreamBehaviorHints(videoSize = 1_450_000_000),
    )
    private val alternative = featured.copy(
        name = "720p alternative",
        description = "Clayface (2026) · HEVC · AAC 2.0 · 850 MB",
        url = "https://example.invalid/alternative.mp4",
        addonName = "MediaFusion | ElfHosted",
        addonId = "mediafusion",
    )
    private val state = StreamsUiState(groups = listOf(
        AddonStreamGroup(featured.addonName, featured.addonId, listOf(featured)),
        AddonStreamGroup(alternative.addonName, alternative.addonId, listOf(alternative)),
        AddonStreamGroup("Torrentio", "torrentio", emptyList()),
        AddonStreamGroup("AnimeSub", "animesub", emptyList()),
    ))

    // Logical client sizes for a 1080p display at 100%, 125% and 150%, allowing
    // for the desktop title bar and taskbar. Theme typography/scaling is production's.
    @Test fun sourcesAt100Percent() = verifyLayout(1920, 1020, "100-percent")
    @Test fun sourcesAt125Percent() = verifyLayout(1536, 816, "125-percent")
    @Test fun sourcesAt150Percent() = verifyLayout(1280, 680, "150-percent")

    private fun verifyLayout(width: Int, height: Int, name: String) =
        runDesktopComposeUiTest(width = width, height = height) {
            val selected = mutableListOf<Triple<StreamItem, Long?, Float?>>()
            var refreshes = 0
            val artwork = RedesignFixtures.landscape("Clayface", 0.02f)
            val scale = desktopUiScaleForWindow(width.toFloat(), height.toFloat())
            StreamBadgeSettingsRepository.ensureLoaded()
            val previousSettings = StreamBadgeSettingsRepository.uiState.value
            StreamBadgeSettingsRepository.setShowAddonLogo(true)
            try {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        NuvioTheme(desktopUiScale = scale) {
                            TabletStreamsLayout(
                                isEpisode = false,
                                title = "Clayface",
                                logo = null,
                                poster = null,
                                background = artwork,
                                episodeThumbnail = null,
                                seasonNumber = null,
                                episodeNumber = null,
                                episodeTitle = null,
                                uiState = state,
                                debridEnabled = false,
                                appendInstantServiceToDefaultName = true,
                                resumePositionMs = 58_000L,
                                resumeProgressFraction = 0.1f,
                                dominantColorEnabled = false,
                                onStreamSelected = { stream, position, progress -> selected += Triple(stream, position, progress) },
                                onStreamLongPress = {},
                                onStreamSecondaryClick = { _, _ -> },
                                onRefresh = { refreshes++ },
                                modifier = Modifier,
                            )
                        }
                    }
                }
                waitForIdle()
                Thread.sleep(1_500)
                waitForIdle()
                onNodeWithText("Choose a source").assertIsDisplayed()
                onNodeWithContentDescription("Refresh streams").assertIsDisplayed().performClick()
                assertEquals(1, refreshes)
                val directory = File("build/source-picker-capture").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
                val listBounds = onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
                assertTrue(listBounds.height / scale >= 250f, "Source list has only ${listBounds.height / scale}dp at $width x $height")
                val compactName = featured.streamLabel.lineSequence().joinToString(" · ")
                val inList = hasAnyAncestor(hasScrollToIndexAction())
                onNode(hasText(compactName) and !inList).assertIsDisplayed().performClick()
                assertEquals(Triple(featured, 58_000L, 0.1f), selected.single())
                onNodeWithText(alternative.streamLabel).assertIsDisplayed()

                // The list retains every part of the featured source's metadata.
                val fullSubtitle = featured.streamSubtitle!!.lineSequence().joinToString(" · ")
                onNode(hasText(fullSubtitle) and inList).performScrollTo().assertIsDisplayed()
                onNode(hasText(compactName) and inList).performScrollTo().performClick()
                assertEquals(featured, selected.last().first)
                onNodeWithText("720p", substring = false).performClick()
                onNodeWithText(alternative.streamLabel).performScrollTo().assertIsDisplayed().performClick()
                assertEquals(alternative, selected.last().first)
                onNode(hasText(compactName) and inList).assertDoesNotExist()
            } finally {
                StreamBadgeSettingsRepository.setShowAddonLogo(previousSettings.showAddonLogo)
            }
        }
}
