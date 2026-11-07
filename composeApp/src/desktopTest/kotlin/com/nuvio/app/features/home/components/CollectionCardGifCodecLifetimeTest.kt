package com.nuvio.app.features.home.components

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

class CollectionCardGifCodecLifetimeTest {
    @Test
    fun evictingCachedGifDoesNotCloseDecoderStillUsedByVisibleCard() {
        // Keep the production cache private. Exercise the real LRU eviction
        // behavior with the same two-frame fixture as CollectionCardRemoteImageTest.
        val owner = Class.forName("com.nuvio.app.features.home.components.CollectionCardRemoteImage_desktopKt")
        val field = owner.getDeclaredField("gifCodecCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<String, Any?>
        val ctor = Class.forName("com.nuvio.app.features.home.components.GifCodecHolder")
            .declaredConstructors.single().apply { isAccessible = true }
        val retained = mutableListOf<Codec>()

        synchronized(cache) {
            val previous = cache.toMap()
            cache.clear()
            try {
                repeat(16) { index ->
                    val codec = Data.makeFromBytes(Base64.getDecoder().decode(GIF)).use {
                        Codec.makeFromData(it)
                    }
                    retained += codec
                    cache["starlit-gif-lifetime-fixture-$index"] =
                        ctor.newInstance(codec, listOf(200L, 200L), 2, 2, 2, 2, false)
                }
                assertEquals(15, cache.size, "The LRU must still bound cached entries")
                val stillVisibleCodec = retained.first()
                assertFalse(stillVisibleCodec.isClosed, "Eviction cannot invalidate an onscreen card")
                Bitmap().use { frame ->
                    frame.allocPixels(ImageInfo.makeN32Premul(2, 2))
                    stillVisibleCodec.readPixels(frame, 0)
                    stillVisibleCodec.readPixels(frame, 1)
                }
            } finally {
                cache.clear()
                cache.putAll(previous)
                retained.filterNot { it.isClosed }.forEach { it.close() }
            }
        }
    }

    private companion object {
        const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
