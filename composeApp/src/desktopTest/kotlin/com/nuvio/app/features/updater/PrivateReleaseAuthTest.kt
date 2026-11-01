package com.nuvio.app.features.updater

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PrivateReleaseAuthTest {
    @Test fun publicUpdateUrlsAreRestrictedToOurReleaseAndKnownAssetHosts() {
        assertTrue(isPublicReleaseAssetUri(URI("https://github.com/Beelzebub2/nuvio-native/releases/download/v1.0.47/Starlit-Windows-x64-1.0.47.exe")))
        assertTrue(isAllowedReleaseRedirectUri(URI("https://release-assets.githubusercontent.com/example?token=short")))
        listOf(
            "http://github.com/Beelzebub2/nuvio-native/releases/download/v1.0.47/app.exe",
            "https://github.com.evil.test/Beelzebub2/nuvio-native/releases/download/v1.0.47/app.exe",
            "https://github.com@evil.test/Beelzebub2/nuvio-native/releases/download/v1.0.47/app.exe",
            "https://github.com/other/repo/releases/download/v1.0.47/app.exe",
            "https://github.com:8443/Beelzebub2/nuvio-native/releases/download/v1.0.47/app.exe",
            "https://127.0.0.1/private",
        ).forEach { assertFalse(isAllowedReleaseRedirectUri(URI(it)), it) }
    }

    @Test fun publicReleaseSelectsUnauthenticatedBrowserUrlAndPropagatesDigest() {
        val digest = "a".repeat(64)
        val response = """[{"tag_name":"v1.0.1","assets":[{"name":"Nuvio-Native-Windows-x64-1.0.1.exe","browser_download_url":"https://github.com/Beelzebub2/nuvio-native/releases/download/v1.0.1/app.exe","url":"https://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123","digest":"sha256:$digest"}]}]"""
        val update = AppUpdaterRepository.selectUpdate(response, UpdateChannel.ALL_RELEASES, emptyList(),
            selector = AppUpdateAssetSelector(fileExtensions = listOf(".exe")), useApiAssetUrls = false)
        assertEquals("https://github.com/Beelzebub2/nuvio-native/releases/download/v1.0.1/app.exe", update?.assetUrl)
        assertEquals(digest, update?.sha256)
    }
}
