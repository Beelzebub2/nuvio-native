package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private val proof = File("build/episode-images-proof").apply { mkdirs() }
private val images = ConcurrentHashMap<String, ByteArray>()
private val imageServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange ->
        val bytes = images[exchange.requestURI.path]
        if (bytes == null) exchange.sendResponseHeaders(404, -1)
        else {
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }
}
private val imageBase get() = "http://127.0.0.1:${imageServer.address.port}"
private fun still(name: String, color: Color): String {
    val file = File(proof, "$name.png")
    val image = BufferedImage(200, 112, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        this.color = color; fillRect(0, 0, 200, 112)
        this.color = Color.WHITE; fillRect(96, 0, 8, 112)
        dispose()
    }
    ImageIO.write(image, "png", file)
    images["/$name.png"] = file.readBytes()
    return "$imageBase/$name.png"
}
private fun BufferedImage.coloredPixels(red: Boolean): Int =
    (0 until height).sumOf { y -> (0 until width).count { x ->
        val c = Color(getRGB(x, y))
        if (red) c.red > 200 && c.green < 70 && c.blue < 70
        else c.blue > 200 && c.red < 70 && c.green < 70
    } }

@OptIn(ExperimentalTestApi::class)
class EpisodeArtworkUiTest {
    companion object {
        @JvmStatic @BeforeClass fun startImages() = imageServer.start()
        @JvmStatic @AfterClass fun stopImages() = imageServer.stop(0)
    }
    @Test fun desktopRowsShowDistinctStillsAndHonestMissingOrBrokenPlaceholders() =
        runDesktopComposeUiTest(width = 1000, height = 760) {
            val videos = listOf(
                MetaVideo("one", "First episode", thumbnail = still("fixture-red", Color.RED), season = 1, episode = 1, runtime = 24),
                MetaVideo("two", "Second episode", thumbnail = still("fixture-blue", Color.BLUE), season = 1, episode = 2),
                MetaVideo("three", "No episode still", released = "2099-10-26T12:00:00Z", available = false, season = 1, episode = 3),
                MetaVideo("four", "Broken episode still", thumbnail = "$imageBase/absent.png", season = 1, episode = 4),
            )
            var selected: String? = null
            setContent { NuvioTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Episodes", style = MaterialTheme.typography.headlineSmall)
                    videos.forEach { video -> DesktopEpisodeRow(video, null, null, false,
                        EpisodeRatingsVisibility.SHOW_ALL, false, false, onClick = { selected = video.id }) }
                }
            } } }
            waitUntil(timeoutMillis = 10_000) {
                val shot = onRoot().captureToImage().toAwtImage()
                shot.coloredPixels(true) > 10_000 && shot.coloredPixels(false) > 10_000 &&
                    onAllNodesWithText("S1 E4").fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("S1 E3").assertCountEquals(1)
            val codeBounds = onNodeWithText("S1 E3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val date = com.nuvio.app.core.format.formatReleaseDateForDisplay("2099-10-26T12:00:00Z")
            val dateBounds = onAllNodesWithText(date, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
            assertTrue(dateBounds.top > codeBounds.bottom, "Upcoming date must not overlap the episode code")
            onNodeWithText("No episode still").performClick()
            runOnIdle { assertEquals("three", selected) }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(proof, "desktop-episode-rows.png"))
        }

    @Test fun failedArtworkRecoversOnUrlChangeAndSpoilerBlurStillApplies() =
        runDesktopComposeUiTest(width = 480, height = 320) {
            val valid = still("fixture-spoiler", Color.RED)
            val url = mutableStateOf("$imageBase/also-absent.png")
            setContent { NuvioTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EpisodeArtwork(MetaVideo("changing", "Changing still", thumbnail = url.value, season = 1, episode = 1),
                        blur = false, modifier = Modifier.size(200.dp, 112.dp))
                    EpisodeArtwork(MetaVideo("spoiler", "Hidden spoiler", thumbnail = valid, season = 1, episode = 2),
                        blur = true, modifier = Modifier.size(200.dp, 112.dp))
                }
            } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("S1 E1").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { url.value = valid }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("S1 E1").fetchSemanticsNodes().isEmpty() &&
                    onRoot().captureToImage().toAwtImage().coloredPixels(true) > 26_000
            }
            val shot = onRoot().captureToImage().toAwtImage()
            fun stripeContrast(y: Int): Int = Color(shot.getRGB(116, y)).green - Color(shot.getRGB(106, y)).green
            assertTrue(stripeContrast(65) > 150)
            assertTrue(stripeContrast(185) < 80)
            ImageIO.write(shot, "png", File(proof, "spoiler-blur-and-recovered-image.png"))
        }

    @Test fun narrowHorizontalCardsKeepUnmappedEpisodesDistinctAndReachable() =
        runDesktopComposeUiTest(width = 360, height = 760) {
            val repeatedPoster = still("fixture-series-poster", Color.BLUE)
            val meta = MetaDetails("nativeairing:218532", "series", "Maomao no Hitorigoto 3rd Season",
                poster = repeatedPoster, background = repeatedPoster, hasScheduledVideos = true,
                videos = (1..4).map { MetaVideo("mal:65119:$it", "Episode $it", season = 1, episode = it) })
            var selected: String? = null
            setContent { NuvioTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text(meta.name, style = MaterialTheme.typography.headlineSmall)
                    DetailSeriesContent(meta, blurUnwatchedEpisodes = true, onEpisodeClick = { selected = it.id })
                }
            } } }
            onAllNodesWithText("S1 E1")[0].assertIsDisplayed()
            assertEquals(0, onRoot().captureToImage().toAwtImage().coloredPixels(false), "Parent artwork must not masquerade as an episode still")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(proof, "narrow-unmapped-episodes.png"))
            onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Episode 4"))
            onNodeWithText("Episode 4").assertIsDisplayed().performClick()
            runOnIdle { assertEquals("mal:65119:4", selected) }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(proof, "narrow-last-episode.png"))
        }
}
