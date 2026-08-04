package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

private data class SkiaGifAnimation(
    val frames: List<ImageBitmap>,
    val delaysMs: List<Long>,
)

private val desktopGifHttpClient by lazy { HttpClient(CIO) }
private val desktopGifCache = mutableMapOf<String, SkiaGifAnimation?>()

private suspend fun loadDesktopGifAnimation(url: String): SkiaGifAnimation? {
    if (desktopGifCache.containsKey(url)) {
        return desktopGifCache[url]
    }
    val anim = withContext(Dispatchers.IO) {
        try {
            val bytes = desktopGifHttpClient.get(url).body<ByteArray>()
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            val count = codec.frameCount
            if (count <= 1) return@withContext null
            val width = codec.width
            val height = codec.height
            if (width <= 0 || height <= 0) return@withContext null

            val frames = mutableListOf<ImageBitmap>()
            val delays = mutableListOf<Long>()

            for (i in 0 until count) {
                val bitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(width, height))
                }
                codec.readPixels(bitmap, i)
                frames.add(bitmap.asComposeImageBitmap())
                val duration = codec.getFrameInfo(i).duration
                delays.add(if (duration > 0) duration.toLong() else 100L)
            }
            SkiaGifAnimation(frames, delays)
        } catch (_: Exception) {
            null
        }
    }
    desktopGifCache[url] = anim
    return anim
}

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val isGifUrl = remember(imageUrl) {
        imageUrl.contains(".gif", ignoreCase = true)
    }

    if (animateIfPossible && isGifUrl) {
        var animation by remember(imageUrl) { mutableStateOf(desktopGifCache[imageUrl]) }

        LaunchedEffect(imageUrl) {
            if (animation == null && !desktopGifCache.containsKey(imageUrl)) {
                animation = loadDesktopGifAnimation(imageUrl)
            }
        }

        val currentAnimation = animation
        if (currentAnimation != null && currentAnimation.frames.isNotEmpty()) {
            var frameIndex by remember(imageUrl) { mutableStateOf(0) }

            LaunchedEffect(imageUrl, currentAnimation) {
                while (true) {
                    val delayMs = currentAnimation.delaysMs.getOrElse(frameIndex) { 100L }
                    delay(delayMs)
                    frameIndex = (frameIndex + 1) % currentAnimation.frames.size
                }
            }

            Image(
                bitmap = currentAnimation.frames[frameIndex],
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
            )
            return
        }
    }

    val context = LocalPlatformContext.current
    val request = remember(context, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("home-collection:$imageUrl")
            .diskCacheKey(imageUrl)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
