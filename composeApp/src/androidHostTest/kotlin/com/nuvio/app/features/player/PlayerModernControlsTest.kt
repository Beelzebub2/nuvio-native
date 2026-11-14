package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone controls follow the desktop redesign and fit landscape and portrait phones.
 * Screenshots land in build/starlit-android-capture for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PlayerModernControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val autoSkipIntro = mutableStateOf(false)
    private val displayedPositionMs = mutableStateOf(512_000L)
    private val events = mutableListOf<String>()
    private var finishedScrubMs: Long? = null

    private val barButtons = listOf(
        "Play", "Seek backward 10 seconds", "Seek forward 10 seconds", "Next Episode",
        "Episodes", "Sources", "Subtitles", "Audio", "Playback speed 1x", "Aspect Fit",
        "Playback options", "More controls",
    )

    private fun showControls(paused: Boolean = true) {
        compose.setContent {
            NuvioTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF34506A), Color(0xFF112233), Color(0xFF442211)))),
                ) {
                    PlayerControlsShell(
                        title = "Frieren: Beyond Journey's End",
                        streamTitle = "Frieren.S01E07.1080p.WEB-DL",
                        providerName = "Torrentio",
                        seasonNumber = 1,
                        episodeNumber = 7,
                        episodeTitle = "Like a Fairy Tale",
                        playbackSnapshot = PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = !paused,
                            durationMs = 1_440_000L,
                            positionMs = 512_000L,
                            bufferedPositionMs = 640_000L,
                            videoWidth = 1920,
                            videoHeight = 1080,
                        ),
                        displayedPositionMs = displayedPositionMs.value,
                        metrics = PlayerLayoutMetrics.fromWidth(844.dp),
                        resizeMode = PlayerResizeMode.Fit,
                        isLocked = false,
                        showMetadata = true,
                        skipSegments = listOf(
                            PlayerControlSkipSegment("recap", 0L, 60_000L),
                            PlayerControlSkipSegment("intro", 90_000L, 180_000L),
                            PlayerControlSkipSegment("outro", 1_320_000L, 1_420_000L),
                        ),
                        subtitlesActive = true,
                        onNextEpisodeClick = { events += "next" },
                        onLockToggle = { events += "lock" },
                        onBack = { events += "back" },
                        onTogglePlayback = { events += "toggle" },
                        onSeekBack = { events += "seekBack" },
                        onSeekForward = { events += "seekForward" },
                        onResizeModeClick = { events += "resize" },
                        onSpeedClick = { events += "speed" },
                        onSubtitleClick = { events += "subtitles" },
                        onAudioClick = { events += "audio" },
                        onSourcesClick = { events += "sources" },
                        onEpisodesClick = { events += "episodes" },
                        onOpenInExternalPlayer = { events += "external" },
                        onSubmitIntroClick = { events += "submitIntro" },
                        onDiagnosticsClick = { events += "diagnostics" },
                        onCastInfoClick = { events += "cast" },
                        controlToggles = listOf(
                            PlayerControlToggle("Auto Skip Intro", autoSkipIntro.value, PlayerControlIcons.AutoSkipIntro) {
                                autoSkipIntro.value = it
                            },
                            PlayerControlToggle("Auto Next Episode", true, PlayerControlIcons.AutoNextEpisode) {},
                        ),
                        onScrubChange = { displayedPositionMs.value = it },
                        onScrubFinished = { finishedScrubMs = it },
                        horizontalSafePadding = 0.dp,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String): DpRect =
        compose.onNodeWithContentDescription(description).assertIsDisplayed().getUnclippedBoundsInRoot()

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/starlit-android-capture/player-$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun landscapePhoneShowsTheDesktopLayoutWithEveryControlInTheBar() {
        showControls()
        capture("landscape")

        compose.onNodeWithText("Frieren: Beyond Journey's End").assertIsDisplayed()
        compose.onNodeWithText("S1 E7 • Like a Fairy Tale").assertIsDisplayed()
        compose.onNodeWithText("1080p").assertIsDisplayed()
        compose.onNodeWithText("Frieren.S01E07.1080p.WEB-DL · Torrentio").assertIsDisplayed()
        val back = bounds("Close player")
        val tools = listOf("Submit Intro", "Playback diagnostics", "Lock player controls").map(::bounds)

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val bar = barButtons.map(::bounds)
        val seekBar = bounds("Playback position")
        bar.zipWithNext().forEach { (left, right) ->
            assertTrue(left.right <= right.left, "bar buttons overlap: $left / $right")
        }
        assertTrue(bar.last().right <= root.right, "the bar fits the screen")
        assertTrue(back.left < tools.first().left && tools.all { it.bottom < seekBar.top }, "header sits above the seek row")
        assertTrue(back.bottom < seekBar.top && seekBar.bottom <= bar.first().bottom, "seek row sits above the bar")
        assertTrue(bar.first().left - root.left < 40.dp, "play sits at the bottom left, like desktop")

        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Seek forward 10 seconds").performClick()
        compose.onNodeWithContentDescription("Sources").performClick()
        compose.runOnIdle { assertEquals(listOf("toggle", "seekForward", "sources"), events) }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port-xxhdpi")
    fun portraitPhoneFoldsLibraryAndViewControlsIntoMore() {
        showControls()
        capture("portrait")

        listOf("Play", "Seek backward 10 seconds", "Seek forward 10 seconds", "Subtitles", "Audio", "More controls")
            .forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        listOf("Episodes", "Sources", "Playback options", "Submit Intro", "Playback diagnostics")
            .forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithText("1080p").assertDoesNotExist()
        val more = bounds("More controls")
        assertTrue(more.right <= compose.onRoot().getUnclippedBoundsInRoot().right, "More stays on screen")

        compose.onNodeWithContentDescription("More controls").performClick()
        capture("portrait-more")
        listOf("Next Episode", "Episodes", "Sources", "Playback speed", "Aspect", "Playback options",
            "Open in external player", "Casting availability", "Submit Intro", "Playback diagnostics")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Fit").assertIsDisplayed()

        compose.onNodeWithText("Episodes").performClick()
        compose.onNodeWithText("Casting availability").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("episodes"), events) }
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun playbackOptionsGatherTheToggles() {
        showControls()
        compose.onNodeWithContentDescription("Playback options").performClick()
        capture("landscape-options")

        compose.onNodeWithText("PLAYBACK").assertIsDisplayed()
        compose.onNodeWithText("Auto Skip Intro").assertIsDisplayed().assertIsOff().performClick()
        compose.onNodeWithText("Auto Skip Intro").assertIsOn()
        compose.onNodeWithText("Auto Next Episode").assertIsOn()

        // Tapping outside closes the menu without reaching the player.
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.onNodeWithText("Auto Next Episode").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList(), events) }
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun scrubbingNamesTheChapterUnderTheFinger() {
        showControls(paused = false)
        compose.onNodeWithContentDescription("Playback position").performTouchInput {
            // Drag from the recap into the intro, which spans 6.25% to 12.5% of the episode.
            down(Offset(width * 0.03f, centerY))
            moveTo(Offset(width * 0.06f, centerY))
            moveTo(Offset(width * 0.1f, centerY))
        }
        compose.onNodeWithContentDescription("Playback position").assert(
            SemanticsMatcher("announces the intro chapter") {
                it.config.getOrNull(SemanticsProperties.StateDescription)?.endsWith(" / 24:00 · Intro") == true
            },
        )
        capture("landscape-scrub")
        compose.onNodeWithContentDescription("Playback position").performTouchInput { up() }

        compose.runOnIdle {
            val target = finishedScrubMs ?: error("scrub did not finish")
            assertTrue(target in 90_000L until 180_000L, "scrubbed to $target ms")
        }
    }
}
