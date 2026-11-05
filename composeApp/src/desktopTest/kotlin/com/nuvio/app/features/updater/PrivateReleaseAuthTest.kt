package com.nuvio.app.features.updater

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PrivateReleaseAuthTest {
    @Test fun publicUpdateUrlsAreRestrictedToOurReleaseAndKnownAssetHosts() {
        for (repo in listOf("nuvio-native", "starlit")) {
            val valid = "https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/Starlit-Windows-x64-1.9.2.exe"
            assertTrue(isPublicReleaseAssetUri(URI(valid)), valid)
            assertTrue(isAllowedReleaseRedirectUri(URI(valid)), valid)
            listOf(
                "http://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe",
                "https://github.com.evil.test/Beelzebub2/$repo/releases/download/v1.9.2/app.exe",
                "https://github.com@evil.test/Beelzebub2/$repo/releases/download/v1.9.2/app.exe",
                "https://other@github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe",
                "https://github.com:8443/Beelzebub2/$repo/releases/download/v1.9.2/app.exe",
                "https://github.com/other/$repo/releases/download/v1.9.2/app.exe",
                "https://github.com/Beelzebub2/$repo-extra/releases/download/v1.9.2/app.exe",
                "https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe?x=1",
                "https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe#fragment",
                "https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe/extra",
            ).forEach { assertFalse(isAllowedReleaseRedirectUri(URI(it)), it) }
        }
        assertTrue(isAllowedReleaseRedirectUri(URI("https://release-assets.githubusercontent.com/example?token=short")))
        listOf(
            "https://127.0.0.1/private",
            "https://github.com/Beelzebub2/other/releases/download/v1.9.2/app.exe",
        ).forEach { assertFalse(isAllowedReleaseRedirectUri(URI(it)), it) }
    }

    @Test fun publicReleaseSelectsUnauthenticatedBrowserUrlAndPropagatesDigest() {
        val digest = "a".repeat(64)
        for (repo in listOf("nuvio-native", "starlit")) {
            val response = """[{"tag_name":"v1.9.2","assets":[{"name":"Starlit-Windows-x64-1.9.2.exe","browser_download_url":"https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe","url":"https://api.github.com/repos/Beelzebub2/$repo/releases/assets/123","digest":"sha256:$digest"}]}]"""
            val update = AppUpdaterRepository.selectUpdate(response, UpdateChannel.ALL_RELEASES, emptyList(),
                selector = AppUpdateAssetSelector(fileExtensions = listOf(".exe")), useApiAssetUrls = false)
            assertEquals("https://github.com/Beelzebub2/$repo/releases/download/v1.9.2/app.exe", update?.assetUrl)
            assertTrue(isPublicReleaseAssetUri(URI(update!!.assetUrl)))
            assertEquals(digest, update.sha256)
        }
    }
}
