package com.nuvio.app.features.watchlater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PersonalMediaUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedEpisodeShowsProgressAndResumesTheSavedVideo() {
        val id = "watch-later-progress-ui"
        val videoId = "$id-addon-video"
        val item = MetaPreview(id, "series", "Test Anime",
            watchLaterEpisode = WatchLaterEpisode(videoId, "Second episode", 1, 2))
        val session = WatchProgressPlaybackSession(ProfileRepository.activeProfileId, "series", id, "series", "$id:1:2",
            item.name, seasonNumber = 1, episodeNumber = 2, episodeTitle = "Second episode")
        WatchProgressRepository.upsertPlaybackProgress(session,
            PlayerPlaybackSnapshot(isLoading = false, durationMs = 1_200_000, positionMs = 600_000), syncRemote = false)
        WatchLaterRepository.toggle(item)
        compose.waitUntil(5_000L) { WatchLaterRepository.items.value.any { it.id == id } }
        var played: ContinueWatchingItem? = null
        try {
            compose.setContent { NuvioTheme { Box(Modifier.size(1100.dp, 760.dp)) {
                WatchLaterScreen(onPosterClick = {}, onPlay = { played = it })
            } } }
            compose.onNodeWithText("S1 · E2 · Second episode").assertIsDisplayed()
            compose.onNodeWithText("50% · 10 min watched").assertIsDisplayed()
            compose.onNodeWithText("Resume").performClick()
            compose.runOnIdle {
                assertEquals(videoId, played?.videoId)
                assertEquals(600_000L, played?.resumePositionMs)
            }
            val directory = File("build/qa-1.0.5").apply { mkdirs() }
            ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "watch-later-progress.png"))
        } finally {
            WatchLaterRepository.items.value.filter { it.id == id }.forEach(WatchLaterRepository::remove)
            WatchProgressRepository.clearProgress(session.videoId, id)
        }
    }

    @Test fun myListCanChangeAStatusAndRating() {
        val item = MetaPreview("personal-ui", "series", "An anime with a long title for the new My List card")
        PersonalMediaRepository.setStatus(item, PersonalMediaStatus.WATCHING)
        PersonalMediaRepository.setRating(item, 8)
        try {
            compose.setContent { NuvioTheme { Box(Modifier.size(1100.dp, 760.dp)) {
                WatchLaterScreen(personalList = true, onPosterClick = {}, onPlay = {})
            } } }
            compose.onNodeWithText("Watching").performClick()
            compose.onNodeWithText("Dropped").performClick()
            compose.onNodeWithText("★ 8/10").performClick()
            compose.onNodeWithText("9 / 10").performClick()
            compose.runOnIdle {
                val saved = PersonalMediaRepository.items.value.single { it.item.id == item.id }
                assertEquals(PersonalMediaStatus.DROPPED, saved.status)
                assertEquals(9, saved.rating)
            }
            val directory = File("build/qa-1.0.5").apply { mkdirs() }
            ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "my-list.png"))
        } finally { PersonalMediaRepository.setStatus(item, null); PersonalMediaRepository.setRating(item, null) }
    }
}
