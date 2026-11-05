package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdaterRepositoryTest {
    private val migrationSource = AppUpdateReleaseSource(
        owner = "Beelzebub2",
        repo = "nuvio-native",
        userAgent = "Starlit-Update-Test",
        fallbackRepositories = listOf("nuvio-native", "starlit"),
    )

    @Test
    fun compatibilityBuildChecksLegacyRepositoryFirst() = runBlocking {
        val requests = mutableListOf<String>()
        val response = AppUpdaterRepository.fetchReleaseMetadata(
            source = migrationSource,
            channel = UpdateChannel.STABLE,
            additionalHeaders = mapOf("X-Test" to "yes"),
        ) { url, headers ->
            requests += url
            assertEquals("application/vnd.github+json", headers["Accept"])
            assertEquals("Starlit-Update-Test", headers["User-Agent"])
            assertEquals("yes", headers["X-Test"])
            rawResponse(200)
        }

        assertEquals(200, response.status)
        assertEquals(listOf("https://api.github.com/repos/Beelzebub2/nuvio-native/releases/latest"), requests)
    }

    @Test
    fun renamedRepositoryIsUsedOnlyAfterRedirectOrMissingPrimary() = runBlocking {
        for (redirectOrMissing in listOf(301, 302, 307, 308, 404)) {
            val requests = mutableListOf<String>()
            val response = AppUpdaterRepository.fetchReleaseMetadata(
                source = migrationSource,
                channel = UpdateChannel.ALL_RELEASES,
            ) { url, _ ->
                requests += url
                rawResponse(if (requests.size == 1) redirectOrMissing else 200)
            }
            assertEquals(200, response.status)
            assertEquals(listOf(
                "https://api.github.com/repos/Beelzebub2/nuvio-native/releases?per_page=20",
                "https://api.github.com/repos/Beelzebub2/starlit/releases?per_page=20",
            ), requests)
        }
    }

    @Test
    fun gatewayOrRateLimitErrorsNeverTriggerRepositoryFallback() = runBlocking {
        for (failure in listOf(401, 403, 429, 500, 503)) {
            val requests = mutableListOf<String>()
            val response = AppUpdaterRepository.fetchReleaseMetadata(
                source = migrationSource,
                channel = UpdateChannel.BETA,
            ) { url, _ ->
                requests += url
                rawResponse(failure)
            }
            assertEquals(failure, response.status)
            assertEquals(listOf("https://api.github.com/repos/Beelzebub2/nuvio-native/releases?per_page=100"), requests)
        }
    }

    @Test
    fun renamedRepositoryCanAlsoBePrimaryWithoutDroppingLegacyFallback() = runBlocking {
        val requests = mutableListOf<String>()
        val response = AppUpdaterRepository.fetchReleaseMetadata(
            // PR #29 needs only to change the primary repo; the legacy name stays in the aliases.
            source = migrationSource.copy(repo = "starlit"),
            channel = UpdateChannel.STABLE,
        ) { url, _ ->
            requests += url
            rawResponse(if (requests.size == 1) 404 else 200)
        }
        assertEquals(200, response.status)
        assertEquals(listOf(
            "https://api.github.com/repos/Beelzebub2/starlit/releases/latest",
            "https://api.github.com/repos/Beelzebub2/nuvio-native/releases/latest",
        ), requests)
    }

    private fun rawResponse(status: Int) = RawHttpResponse(
        status = status,
        statusText = "",
        url = "",
        body = "",
        headers = emptyMap(),
    )

    @Test
    fun channelsUseTheSameReleaseEndpointsAsTv() {
        assertEquals("releases/latest", AppUpdaterRepository.releasePath(UpdateChannel.STABLE))
        assertEquals("releases?per_page=100", AppUpdaterRepository.releasePath(UpdateChannel.BETA))
        assertEquals("releases?per_page=20", AppUpdaterRepository.releasePath(UpdateChannel.ALL_RELEASES))
    }

    @Test
    fun stableAcceptsReleasesFromAnyBranch() {
        listOf("main", "master", "cmp-rewrite", "1234567").forEach { branch ->
            val response = """{
                "tag_name": "v1.1.0", "target_commitish": "$branch",
                "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}]
            }"""
            assertEquals("v1.1.0", select(response, UpdateChannel.STABLE)?.tag)
        }
    }

    @Test
    fun stableRejectsLegacyBetaNamesEvenIfGithubMarksThemStable() {
        val response = """{
            "tag_name": "1.1.0", "name": "Beta 1.1.0 Hotfix",
            "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}]
        }"""
        assertNull(select(response, UpdateChannel.STABLE))
    }

    @Test
    fun betaSkipsReleasesWithoutAnApkAndSortsByVersion() {
        val response = """[
            {"tag_name": "1.1.0-beta.9", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta9.apk"}]},
            {"tag_name": "1.2.0", "assets": [
                {"name": "app.ipa", "browser_download_url": "https://example.com/app.ipa"}]},
            {"tag_name": "1.1.0-beta.10", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta10.apk"}]}
        ]"""
        assertEquals("1.1.0-beta.10", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun betaReceivesFinalPromotion() {
        val response = """[
            {"tag_name": "1.1.0-beta.10", "prerelease": true, "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/beta.apk"}]},
            {"tag_name": "1.1.0", "assets": [
                {"name": "app.apk", "browser_download_url": "https://example.com/stable.apk"}]}
        ]"""
        assertEquals("1.1.0", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun desktopSelectsFirstPublishedReleaseInGithubOrderWithoutChannelOrSemverFiltering() {
        val response = """[
            {"tag_name": "desktop-preview", "name": "Desktop Preview", "prerelease": true, "assets": [
                {"name": "Nuvio.dmg", "browser_download_url": "https://example.com/preview.dmg"}]},
            {"tag_name": "2.0.0", "assets": [
                {"name": "Nuvio.dmg", "browser_download_url": "https://example.com/stable.dmg"}]}
        ]"""

        val update = AppUpdaterRepository.selectUpdate(
            responseBody = response,
            channel = UpdateChannel.ALL_RELEASES,
            supportedAbis = emptyList(),
            selector = AppUpdateAssetSelector(fileExtensions = listOf(".dmg")),
        )

        assertEquals("desktop-preview", update?.tag)
        assertEquals("https://example.com/preview.dmg", update?.assetUrl)
    }

    @Test
    fun apkSelectionPreservesDeviceAbiPreference() {
        val response = """{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/universal.apk"},
                {"name": "app-arm64-v8a.apk", "browser_download_url": "https://example.com/arm64.apk"},
                {"name": "app-x86_64.apk", "browser_download_url": "https://example.com/x86.apk"}
            ]
        }"""
        assertEquals("app-arm64-v8a.apk", select(response, UpdateChannel.STABLE)?.assetName)
        assertEquals(
            "app-universal.apk",
            AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("armeabi-v7a"))?.assetName,
        )
    }

    private fun select(response: String, channel: UpdateChannel): AppUpdate? =
        AppUpdaterRepository.selectUpdate(response, channel, listOf("arm64-v8a"))
}
