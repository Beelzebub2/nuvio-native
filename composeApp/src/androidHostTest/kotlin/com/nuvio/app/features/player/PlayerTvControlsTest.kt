package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals

/** Android TV uses the same controls; they must work with a remote's D-pad. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class PlayerTvControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun showTvControls() {
        compose.setContent {
            // A TV has no touchscreen, so its window starts in key input mode.
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalTelevisionUi provides true) {
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
                                isPlaying = true,
                                durationMs = 1_440_000L,
                                positionMs = 120_000L,
                                bufferedPositionMs = 300_000L,
                                videoWidth = 3840,
                                videoHeight = 2160,
                            ),
                            displayedPositionMs = 120_000L,
                            metrics = PlayerLayoutMetrics.fromWidth(960.dp),
                            resizeMode = PlayerResizeMode.Fit,
                            isLocked = false,
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
                            onDiagnosticsClick = { events += "diagnostics" },
                            onCastInfoClick = { events += "cast" },
                            controlToggles = listOf(
                                PlayerControlToggle("Auto Skip Intro", true, PlayerControlIcons.AutoSkipIntro) {},
                            ),
                            onScrubChange = {},
                            onScrubFinished = {},
                            horizontalSafePadding = 0.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/starlit-android-capture/player-$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun playTakesFocusAndTheRemoteWalksTheBar() {
        showTvControls()
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
        capture("tv")

        compose.onNodeWithContentDescription("Lock player controls").assertDoesNotExist()
        compose.onNodeWithText("4K").assertExists()

        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Seek backward 10 seconds").assertIsFocused()
        press(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("seekBack"), events) }
    }

    @Test
    fun moreMenuTakesFocusAndGivesItBackToMore() {
        showTvControls()
        compose.onNodeWithContentDescription("More controls").requestFocus()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Open in external player").assertIsFocused()
        capture("tv-more")
        press(Key.DirectionDown)
        compose.onNodeWithText("Casting availability").assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Casting availability").assertDoesNotExist()
        compose.onNodeWithContentDescription("More controls").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("cast"), events) }
    }

    @Test
    fun upFromPlayFocusesTheSeekBarAndNamesTheChapter() {
        showTvControls()
        press(Key.DirectionUp)

        compose.onNodeWithContentDescription("Playback position")
            .assertIsFocused()
            .assert(
                SemanticsMatcher("announces the intro") {
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "02:00 / 24:00 · Intro"
                },
            )
        capture("tv-seek")

        press(Key.DirectionDown)
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
    }
}
