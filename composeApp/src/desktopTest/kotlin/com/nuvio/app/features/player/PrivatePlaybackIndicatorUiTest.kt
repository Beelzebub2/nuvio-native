package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class PrivatePlaybackIndicatorUiTest {
    @Test
    fun privateIndicatorRemainsVisibleAndNormalSessionsHaveNoBadge() = runDesktopComposeUiTest(width = 880, height = 640) {
        val privatePlayback = mutableStateOf(true)
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    PrivatePlaybackIndicator(privatePlayback = privatePlayback.value)
                }
            }
        }
        onNodeWithText("Private playback").assertIsDisplayed()
        val proof = File("build/reports/private-playback-ui").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(proof, "desktop-private-indicator.png"))

        runOnIdle { privatePlayback.value = false }
        onNodeWithText("Private playback").assertDoesNotExist()
    }
}
