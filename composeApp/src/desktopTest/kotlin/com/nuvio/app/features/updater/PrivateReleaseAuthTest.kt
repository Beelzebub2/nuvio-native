package com.nuvio.app.features.updater

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PrivateReleaseAuthTest {
    @Test fun credentialsAreOnlyAllowedOnOurGithubAssetEndpoint() {
        for (repo in listOf("starlit", "nuvio-native")) {
            val asset = "/repos/Beelzebub2/$repo/releases/assets/123"
            assertTrue(isPrivateReleaseAssetUri(URI("https://api.github.com$asset")))
            assertTrue(isPrivateReleaseAssetUri(URI("https://api.github.com:443$asset")))
            listOf(
                "http://api.github.com$asset",
                "https://api.github.com.evil.test$asset",
                "https://api.github.com@evil.test$asset",
                "https://other@api.github.com$asset",
                "https://api.github.com$asset?redirect=evil",
                "https://api.github.com$asset#fragment",
                "https://api.github.com:8443$asset",
                "https://api.github.com$asset/extra",
                "https://api.github.com/repos/other/$repo/releases/assets/123",
                "https://api.github.com/repos/Beelzebub2/$repo-other/releases/assets/123",
            ).forEach { assertFalse(isPrivateReleaseAssetUri(URI(it)), it) }
        }
        assertFalse(isPrivateReleaseAssetUri(URI("https://api.github.com/repos/other/repo/releases/assets/123")))
    }

    @Test fun updatesUseRenamedRepository() {
        assertEquals("Beelzebub2", AppUpdaterPlatform.releaseSource.owner)
        assertEquals("starlit", AppUpdaterPlatform.releaseSource.repo)
    }

    @Test fun privateReleaseSelectsAuthenticatedUrlAndPropagatesDigest() {
        val digest = "a".repeat(64)
        for (repo in listOf("starlit", "nuvio-native")) {
            val response = """[{"tag_name":"v1.0.1","assets":[{"name":"Nuvio-Native-Windows-x64-1.0.1.exe","browser_download_url":"https://github.com/Beelzebub2/$repo/releases/download/v1.0.1/app.exe","url":"https://api.github.com/repos/Beelzebub2/$repo/releases/assets/123","digest":"sha256:$digest"}]}]"""
            val update = AppUpdaterRepository.selectUpdate(response, UpdateChannel.ALL_RELEASES, emptyList(),
                selector = AppUpdateAssetSelector(fileExtensions = listOf(".exe")), useApiAssetUrls = true)
            assertEquals("https://api.github.com/repos/Beelzebub2/$repo/releases/assets/123", update?.assetUrl)
            assertEquals(digest, update?.sha256)
        }
    }
}
