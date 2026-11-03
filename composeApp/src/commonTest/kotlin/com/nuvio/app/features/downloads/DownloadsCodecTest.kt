package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsCodecTest {
    @Test
    fun missingQueueIsEmptyButPresentMalformedDataMustFailClosed() {
        assertEquals(emptyList(), DownloadsCodec.decodeItems(null))
        assertEquals(emptyList(), DownloadsCodec.decodeItems("{}"))
        assertEquals(emptyList(), DownloadsCodec.decodeItems("""{"items":[]}"""))
        for (broken in listOf("", "null", "{", """{"items":null}""", """{"items":[{"id":"partial"}]}""")) {
            assertFails { DownloadsCodec.decodeItems(broken) }
            assertFalse(DownloadsCodec.isValid(broken))
        }
        assertTrue(DownloadsCodec.isValid("""{"items":[]}"""))
    }

    @Test
    fun playlistAndTorrentUrlsWithFragmentsAreNotDirectDownloads() {
        for (extension in listOf("m3u8", "mpd", "torrent")) {
            assertFalse("https://cdn.example/video.$extension#chapter".isSupportedDownloadUrl())
            assertFalse("https://cdn.example/video.$extension?token=abc#chapter".isSupportedDownloadUrl())
        }
        assertTrue("https://cdn.example/video.mp4?token=abc#chapter".isSupportedDownloadUrl())
        assertFalse("magnet:?xt=urn:btih:abc".isSupportedDownloadUrl())
    }
}
