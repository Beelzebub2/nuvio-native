package com.nuvio.app.features.updater

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PrivateReleaseAuthTest {
    @Test fun credentialsAreOnlyAllowedOnOurGithubAssetEndpoint() {
        assertTrue(isPrivateReleaseAssetUri(URI("https://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123")))
        listOf(
            "http://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123",
            "https://api.github.com.evil.test/repos/Beelzebub2/nuvio-native/releases/assets/123",
            "https://api.github.com@evil.test/repos/Beelzebub2/nuvio-native/releases/assets/123",
            "https://api.github.com/repos/other/repo/releases/assets/123",
            "https://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123?redirect=evil",
            "https://api.github.com:8443/repos/Beelzebub2/nuvio-native/releases/assets/123",
        ).forEach { assertFalse(isPrivateReleaseAssetUri(URI(it)), it) }
    }

    @Test fun privateReleaseSelectsAuthenticatedUrlAndPropagatesDigest() {
        val digest = "a".repeat(64)
        val response = """[{"tag_name":"v1.0.1","assets":[{"name":"Nuvio-Native-Windows-x64-1.0.1.exe","browser_download_url":"https://github.com/Beelzebub2/nuvio-native/releases/download/v1.0.1/app.exe","url":"https://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123","digest":"sha256:$digest"}]}]"""
        val update = AppUpdaterRepository.selectUpdate(response, UpdateChannel.ALL_RELEASES, emptyList(),
            selector = AppUpdateAssetSelector(fileExtensions = listOf(".exe")), useApiAssetUrls = true)
        assertEquals("https://api.github.com/repos/Beelzebub2/nuvio-native/releases/assets/123", update?.assetUrl)
        assertEquals(digest, update?.sha256)
    }
}
