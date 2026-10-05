package com.nuvio.app.features.watchlater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomePosterHoverPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class UniversalWatchLaterClickTest(private val case: Case) {
    enum class Case {
        CatalogImage, CatalogTitle, CatalogHoverEnabled,
        DiscoverImage, DiscoverTitle, DiscoverHoverEnabled,
        CollectionImage, CollectionTitle,
        ContinueCard, ContinueWide, ContinuePoster, Hero,
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = Case.entries.map { arrayOf(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun `middle click toggles exactly once without navigating playing or changing Library`() {
        val preview = MetaPreview("universal-middle-${case.name}", "series", "Test ${case.name}")
        PosterCardStyleRepository.ensureLoaded()
        val previousHover = PosterCardStyleRepository.uiState.value.hoverPreviewEnabled
        PosterCardStyleRepository.setHoverPreviewEnabled(case == Case.CatalogHoverEnabled || case == Case.DiscoverHoverEnabled)
        WatchLaterRepository.ensureLoaded()
        fun isSaved() = WatchLaterRepository.items.value.any { it.id == preview.id && it.type == preview.type }
        if (isSaved()) WatchLaterRepository.toggle(preview)
        var navigations = 0
        var actions = 0
        try {
            compose.setContent {
                NuvioTheme {
                    Box(Modifier.size(960.dp, 700.dp)) {
                        when (case) {
                            Case.CatalogImage, Case.CatalogTitle, Case.CatalogHoverEnabled -> HomePosterCard(
                                preview, modifier = Modifier.testTag("target"),
                                onClick = { navigations++ }, onLongClick = { actions++ },
                            )
                            Case.DiscoverImage, Case.DiscoverTitle, Case.DiscoverHoverEnabled -> PosterGridRow(
                                listOf(preview), columns = 1, modifier = Modifier.width(200.dp).testTag("target"),
                                onPosterClick = { navigations++ }, onPosterLongClick = { actions++ },
                            )
                            Case.CollectionImage, Case.CollectionTitle -> HomePosterHoverPreview(
                                preview, isWatched = false, onClick = { navigations++ }, onLongClick = { actions++ },
                                modifier = Modifier.testTag("target"),
                            ) { modifier ->
                                NuvioPosterCard(preview.name, preview.poster, modifier = modifier,
                                    onClick = { navigations++ }, onLongClick = { actions++ })
                            }
                            Case.ContinueCard, Case.ContinueWide, Case.ContinuePoster -> HomeContinueWatchingSection(
                                items = listOf(ContinueWatchingItem(
                                    parentMetaId = preview.id, parentMetaType = preview.type, videoId = "${preview.id}:1:2",
                                    title = preview.name, subtitle = "Season 1 Episode 2", imageUrl = null,
                                    seasonNumber = 1, episodeNumber = 2, resumePositionMs = 100, durationMs = 200,
                                    progressFraction = 0.5f,
                                )),
                                style = when (case) {
                                    Case.ContinueCard -> ContinueWatchingSectionStyle.Card
                                    Case.ContinueWide -> ContinueWatchingSectionStyle.Wide
                                    else -> ContinueWatchingSectionStyle.Poster
                                },
                                dataSourceKey = WatchProgressSource.NUVIO_SYNC,
                                onItemClick = { navigations++ }, onItemLongPress = { actions++ },
                            )
                            Case.Hero -> HomeHeroSection(listOf(preview), modifier = Modifier.testTag("target"),
                                onItemClick = { navigations++ })
                        }
                    }
                }
            }
            fun clickMiddle() {
                if (case in listOf(Case.ContinueCard, Case.ContinueWide, Case.ContinuePoster)) {
                    val titles = compose.onAllNodesWithText(preview.name, useUnmergedTree = true)
                    titles[titles.fetchSemanticsNodes().lastIndex].performMouseInput { click(button = MouseButton.Tertiary) }
                } else {
                    compose.onNodeWithTag("target").performMouseInput {
                        val titleArea = case in listOf(Case.CatalogTitle, Case.DiscoverTitle, Case.CollectionTitle)
                        click(position = Offset(center.x, if (titleArea) height - 3f else height * 0.25f), button = MouseButton.Tertiary)
                    }
                }
            }
            clickMiddle()
            compose.runOnIdle {
                assertTrue(isSaved(), "${case.name} must save the parent title")
                assertEquals(1, WatchLaterRepository.items.value.count { it.id == preview.id && it.type == preview.type })
                assertEquals(0, navigations)
                assertEquals(0, actions)
                assertFalse(LibraryRepository.isSaved(preview.id, preview.type))
                assertTrue(NuvioToastController.currentToast.value!!.message.contains("added to Watch Later"))
            }
            clickMiddle()
            compose.runOnIdle { assertFalse(isSaved(), "The second middle click must remove the title") }
        } finally {
            if (isSaved()) WatchLaterRepository.toggle(preview)
            PosterCardStyleRepository.setHoverPreviewEnabled(previousHover)
            NuvioToastController.dismiss()
        }
    }
}
