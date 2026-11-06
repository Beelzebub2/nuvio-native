package com.nuvio.app.features.updater

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class PrivateUpdateSmokeTest {
    @Test fun `public release downloads through the actual updater and verifies SHA256`() = runBlocking {
        assumeTrue(System.getProperty("nuvio.updateSmoke") == "true")
        val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/Beelzebub2/nuvio-native/releases?per_page=10"))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "NuvioNative-Test")
            .GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        val release = AppUpdaterRepository.selectUpdate(response.body(), UpdateChannel.ALL_RELEASES, emptyList(),
            selector = AppUpdateAssetSelector(fileExtensions = listOf(".exe")), useApiAssetUrls = false)
        assertNotNull("A tagged public Windows release must exist", release)
        assertNotNull("Public installer requires a digest", release!!.sha256)
        val path = AppUpdaterPlatform.downloadUpdateAsset(release.assetUrl, release.assetName, release.sha256) { _, _ -> }.getOrThrow()
        try { assertTrue(Files.size(Path.of(path)) > 100_000_000) }
        finally { AppUpdaterPlatform.deleteDownloadedUpdate(path) }
    }
}
