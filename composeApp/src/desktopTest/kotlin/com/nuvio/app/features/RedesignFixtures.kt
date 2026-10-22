package com.nuvio.app.features

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.library.LibraryItem
import com.sun.net.httpserver.HttpServer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Sample artwork, add-on manifests and library items for the desktop page screenshots. A local
 * server hands out generated posters and logos, so pages render real images without the network.
 */
internal object RedesignFixtures {
    private val routes = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
            createContext("/") { exchange ->
                val route = routes[exchange.requestURI.path]
                if (route == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.set("Content-Type", route.first)
                    exchange.sendResponseHeaders(200, route.second.size.toLong())
                    exchange.responseBody.use { it.write(route.second) }
                }
                exchange.close()
            }
            start()
        }
    }
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun serve(path: String, type: String, bytes: ByteArray): String {
        routes[path] = type to bytes
        return "$base$path"
    }

    /** A 2:3 poster: a two-tone gradient, a few soft shapes and the title. */
    fun poster(title: String, hue: Float, width: Int = 300, height: Int = 450): String {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paint = GradientPaint(
                0f, 0f, Color.getHSBColor(hue, 0.55f, 0.55f),
                width.toFloat(), height.toFloat(), Color.getHSBColor((hue + 0.12f) % 1f, 0.75f, 0.16f),
            )
            fillRect(0, 0, width, height)
            color = Color(255, 255, 255, 34)
            fillOval(width / 2, height / 6, width, width)
            stroke = BasicStroke(width / 60f)
            color = Color(255, 255, 255, 60)
            drawOval(-width / 3, height / 2, width, width)
            color = Color.WHITE
            font = Font(Font.SANS_SERIF, Font.BOLD, width / 9)
            val words = title.uppercase().split(" ")
            var y = height - (words.size * font.size) - height / 14
            words.forEach { word ->
                y += font.size
                drawString(word, width / 12, y)
            }
            dispose()
        }
        return serve("/poster/${slug(title)}-${width}x$height.png", "image/png", png(image))
    }

    fun landscape(title: String, hue: Float): String = poster(title, hue, width = 480, height = 270)

    fun logo(label: String, hue: Float): String {
        val size = 128
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color.getHSBColor(hue, 0.6f, 0.75f)
            fillRect(0, 0, size, size)
            color = Color.WHITE
            font = Font(Font.SANS_SERIF, Font.BOLD, 56)
            val metrics = fontMetrics
            val text = label.take(2).uppercase()
            drawString(text, (size - metrics.stringWidth(text)) / 2, (size + metrics.ascent - metrics.descent) / 2)
            dispose()
        }
        return serve("/logo/${slug(label)}.png", "image/png", png(image))
    }

    /** A Stremio-style add-on manifest served at /addon/[id]/manifest.json. */
    fun manifest(
        id: String,
        name: String,
        description: String,
        version: String,
        resources: List<String>,
        types: List<String>,
        catalogs: Int = 0,
        configurable: Boolean = false,
        p2p: Boolean = false,
        hue: Float = 0.5f,
    ): String {
        fun list(values: List<String>) = values.joinToString(",") { "\"$it\"" }
        val catalogJson = (1..catalogs).joinToString(",") { index ->
            """{"type":"${types[index % types.size]}","id":"list$index","name":"List $index"}"""
        }
        val json = """
            {"id":"org.example.$id","version":"$version","name":"$name","description":"$description",
             "logo":"${logo(name, hue)}","resources":[${list(resources)}],"types":[${list(types)}],
             "idPrefixes":["tt"],"catalogs":[$catalogJson],
             "behaviorHints":{"configurable":$configurable,"p2p":$p2p}}
        """.trimIndent()
        return serve("/addon/$id/manifest.json", "application/json", json.toByteArray())
    }

    @OptIn(ExperimentalTestApi::class)
    fun capture(test: ComposeUiTest, name: String) {
        test.waitForIdle()
        val shot = test.onRoot().captureToImage().toAwtImage()
        val directory = File("build/redesign-capture/latest").apply { mkdirs() }
        ImageIO.write(shot, "png", File(directory, "$name.png"))
    }

    fun collections(): List<Collection> {
        fun folder(id: String, title: String, hue: Float, shape: String = "poster", emoji: String? = null) = CollectionFolder(
            id = id,
            title = title,
            coverImageUrl = if (emoji == null) {
                if (shape == "landscape") landscape(title, hue) else poster(title, hue)
            } else {
                null
            },
            coverEmoji = emoji,
            tileShape = shape,
        )
        return listOf(
            Collection(
                id = "studios", title = "Studios", pinToTop = true,
                folders = listOf(
                    folder("ghibli", "Studio Ghibli", 0.08f), folder("a24", "A24", 0.95f),
                    folder("pixar", "Pixar", 0.55f), folder("neon", "Neon", 0.82f),
                    folder("blumhouse", "Blumhouse", 0.0f), folder("laika", "Laika", 0.3f),
                ),
            ),
            Collection(
                id = "moods", title = "Moods", viewMode = "ROWS",
                folders = listOf(
                    folder("cozy", "Cozy nights", 0f, emoji = "🛋️"), folder("brainy", "Brainy", 0f, emoji = "🧠"),
                    folder("thrills", "Thrills", 0f, emoji = "⚡"),
                ),
            ),
            Collection(
                id = "networks", title = "Streaming networks",
                folders = listOf(
                    folder("north", "Northwind", 0.6f, shape = "landscape"),
                    folder("harbor", "Harbor+", 0.15f, shape = "landscape"),
                    folder("summit", "Summit TV", 0.72f, shape = "landscape"),
                ),
            ),
            Collection(
                id = "decades", title = "Decades",
                folders = listOf(
                    folder("80s", "The 80s", 0.88f), folder("90s", "The 90s", 0.5f),
                    folder("00s", "The 2000s", 0.2f),
                ),
            ),
            Collection(id = "draft", title = "Weekend watchlist (draft)"),
        )
    }

    /** Twenty-four saved titles across movies, series and anime, newest first. */
    fun libraryItems(): List<LibraryItem> {
        val titles = listOf(
            Triple("Dune Part Two", "movie", null), Triple("Severance", "series", null),
            Triple("Past Lives", "movie", null), Triple("Frieren", "series", "anime"),
            Triple("Shogun", "series", null), Triple("Arrival", "movie", null),
            Triple("The Bear", "series", null), Triple("Perfect Days", "movie", null),
            Triple("Mushishi", "series", "anime"), Triple("Dune", "movie", null),
            Triple("Andor", "series", null), Triple("Aftersun", "movie", null),
            Triple("Blue Eye Samurai", "series", "anime"), Triple("Prisoners", "movie", null),
            Triple("Dark", "series", null), Triple("Drive My Car", "movie", null),
            Triple("Pluto", "series", "anime"), Triple("Sicario", "movie", null),
            Triple("Fargo", "series", null), Triple("Columbus", "movie", null),
            Triple("Monster", "series", "anime"), Triple("Heat", "movie", null),
            Triple("Mindhunter", "series", null), Triple("Paterson", "movie", null),
        )
        val now = 1_760_000_000_000L
        return titles.mapIndexed { index, (name, type, category) ->
            LibraryItem(
                id = "tt${1_000_000 + index}",
                type = type,
                name = name,
                poster = poster(name, (index * 0.137f) % 1f),
                releaseInfo = (1995 + (index * 7) % 30).toString(),
                mediaCategory = category,
                savedAtEpochMs = now - index * 3_600_000L,
            )
        }
    }
}
