package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the list at common desktop widths, checks the controls fit, and saves screenshots. */
@OptIn(ExperimentalTestApi::class)
class MalListLayoutTest {
    private fun entry(
        id: Int, title: String, english: String?, status: MalStatus, score: Int, watched: Int, total: Int,
        year: Int, season: String, mean: Double?, airing: Boolean = false,
    ) = MalAnimeListEntry(
        anime = MalAnime(id, title), imageUrl = null, totalEpisodes = total,
        listStatus = MalListStatus(status, score, watched), englishTitle = english, mediaType = "tv",
        meanScore = mean, startYear = year, startSeason = season, airing = airing,
    )

    private val entries = listOf(
        entry(1, "Tsuihou sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka suru. Ore wa Buki dake ja Naku, " +
            "Arayuru Mono ni \"Kyouka Point\" wo Fuyo Dekiru shi, Ore no Ishi de Itsudemo Kouka wo Kaijo Dekiru kedo, Nokotta Hitotachi Daijoubu?",
            "The Exiled Enchanter Enjoys a Carefree Second Life", MalStatus.WATCHING, 0, 5, 0, 2025, "fall", 6.9, airing = true),
        entry(2, "Shinja Zero no Megami-sama to Hajimeru Isekai Kouryaku", "Isekai Conquest with the Zero-Follower Goddess",
            MalStatus.WATCHING, 10, 4, 0, 2025, "fall", 7.2, airing = true),
        entry(3, "Tempal: Item no Chikara", null, MalStatus.WATCHING, 10, 6, 12, 2025, "fall", 6.5),
        entry(4, "Kono Subarashii Sekai ni Shukufuku wo! 3", "KonoSuba: God's Blessing on This Wonderful World! 3",
            MalStatus.PLAN_TO_WATCH, 10, 11, 11, 2024, "spring", 8.4),
        entry(5, "Kono Subarashii Sekai ni Shukufuku wo! 2", "KonoSuba: God's Blessing on This Wonderful World! 2",
            MalStatus.COMPLETED, 10, 10, 10, 2017, "winter", 8.3),
        entry(6, "Black Clover 2nd Season", null, MalStatus.ON_HOLD, 0, 4, 0, 2018, "spring", null),
    ).let { list -> list.map { if (it.anime.id == 5) it.copy(listStatus = it.listStatus.copy(isRewatching = true)) else it } }

    private val stats = MalAnimeStatistics(7, 120, 3, 2, 40, 172, 2650, 45.32, 7.91)

    @Test fun cardsKeepTheirControlsInsideAtDesktopWidths() {
        for (width in listOf(1600, 1100, 820)) {
            runDesktopComposeUiTest(width = width, height = 1000) {
                setContent {
                    NuvioTheme {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background).testTag("page")) {
                            Text(
                                "MyAnimeList",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.nuvio.colors.textPrimary,
                                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 4.dp),
                            )
                            MalProfileSummary("Lucifer_uwu", stats, Modifier.padding(horizontal = 28.dp).padding(bottom = 16.dp))
                            MalListToolbar(MalStatus.WATCHING, {}, stats, MalListSort.UPDATED, {}, Modifier.padding(horizontal = 28.dp))
                            Spacer(Modifier.height(16.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(500.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(entries, key = { it.anime.id }) { entry ->
                                    MalEntryCard(entry, saving = entry.anime.id == 2, onChange = {}, onEdit = {}, onOpen = {}, onRemove = {})
                                }
                            }
                        }
                    }
                }
                waitForIdle()
                val cards = onAllNodes(hasTestTag("mal-entry-1") or hasTestTag("mal-entry-3") or hasTestTag("mal-entry-4"))
                    .fetchSemanticsNodes()
                val steppers = onAllNodesWithTag("mal-episode-stepper", useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue(cards.isNotEmpty() && steppers.isNotEmpty())
                cards.forEach { card ->
                    val stepper = steppers.first { card.boundsInRoot.contains(it.boundsInRoot.center) }
                    assertTrue(stepper.boundsInRoot.right <= card.boundsInRoot.right - 4f, "Episode controls overflow at $width")
                    assertTrue(card.boundsInRoot.right <= width + 1f, "Card is wider than the $width px window")
                }
                if (width == 1600) {
                    // Titles with and without an English subtitle must still line up in a row.
                    val heights = (1..6).map { onNodeWithTag("mal-entry-$it").fetchSemanticsNode().boundsInRoot.height }
                    assertTrue(heights.max() - heights.min() < 1f, "Cards should share one height: $heights")
                }
                if (width == 820) {
                    // Sorting must not cover the last status category.
                    onNodeWithTag("mal-filter-PLAN_TO_WATCH").assertIsDisplayed()
                    onNodeWithContentDescription("Sort by Last updated").assertIsDisplayed()
                }
                val directory = File("build/reports/mal-list").apply { mkdirs() }
                ImageIO.write(onNodeWithTag("page").captureToImage().toAwtImage(), "png", File(directory, "list-$width.png"))
            }
        }
    }

    @Test fun editorDialogShowsEveryEditableField() = runDesktopComposeUiTest(width = 1100, height = 900) {
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) {
                    MalEntryEditorDialog(entries[4], onDismiss = {}, onSave = {}, onRemove = {})
                }
            }
        }
        waitForIdle()
        listOf("Status", "Episodes watched", "Your score", "Rewatching", "Remove from list", "Save").forEach {
            onNodeWithText(it).assertExists()
        }
        val directory = File("build/reports/mal-list").apply { mkdirs() }
        ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(directory, "editor.png"))
    }
}
