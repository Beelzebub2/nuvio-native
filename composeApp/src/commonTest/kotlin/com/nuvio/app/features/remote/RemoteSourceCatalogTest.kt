package com.nuvio.app.features.remote

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteSourceCatalogTest {
    private fun source(
        url: String? = null,
        externalUrl: String? = null,
        infoHash: String? = null,
        name: String = "1080p",
        addonName: String = "Provider",
    ) = StreamItem(
        url = url, externalUrl = externalUrl, infoHash = infoHash,
        name = name, addonName = addonName, addonId = "test",
    )

    @Test
    fun neverOfferArbitraryLocalPathExternalLinksOrDisabledTorrents() {
        assertTrue(source(url = "https://media.example/video.m3u8").isEligibleForRemotePlayback(false, false))
        assertFalse(source(url = "file:///private/movie.mp4").isEligibleForRemotePlayback(true, false))
        assertFalse(source(url = "javascript:alert(1)").isEligibleForRemotePlayback(true, false))
        assertFalse(source(externalUrl = "https://example.org/account").isEligibleForRemotePlayback(true, true))
        val torrent = source(infoHash = "a".repeat(40))
        assertFalse(torrent.isEligibleForRemotePlayback(false, false))
        assertTrue(torrent.isEligibleForRemotePlayback(true, false))
    }

    @Test
    fun sourceLabelsStripUntrustedLinksControlsAndCredentials() {
        val stream = source(
            url = "https://media.example/video",
            addonName = "Provider https://private.example/key",
            name = "1080p\nBearer:secret magnet:?xt=urn:btih:abc",
        ).copy(behaviorHints = StreamBehaviorHints(videoSize = 5L * 1024 * 1024 * 1024))
        val result = stream.remoteSourceLabel()
        assertTrue(result.contains("1080p"))
        assertTrue(result.contains("5.0 GiB"))
        assertFalse(result.contains("private.example"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("magnet:"))
        assertFalse(result.contains("\n"))
        assertTrue(result.length <= 150)
    }
}
