package com.nuvio.app.features.remote

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsUiState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteDownloadsTest {
    private fun task(id: String = "abc_123", status: DownloadStatus = DownloadStatus.Downloading) =
        DownloadItem(
            id = id, contentType = "series", parentMetaId = "private", parentMetaType = "series",
            videoId = "video", title = "A\nSeries",
            streamTitle = "1080p", providerName = "Authorized",
            sourceUrl = "https://private.example/video?secret=NeverShareThis",
            sourceHeaders = mapOf("Authorization" to "Bearer NeverShareThis"),
            localFileUri = "file:///users/secret/path",
            destinationDirectory = "C:\\private\\unshared",
            fileName = "secret.mp4",
            status = status, downloadedBytes = 500, totalBytes = 1000,
            createdAtEpochMs = 1, updatedAtEpochMs = 2,
        )

    @Test
    fun snapshotOnlyIncludesSanitizedMetadataForMatchingProfile() {
        val state = DownloadsUiState(listOf(task()), profileId = 4)
        assertTrue(remoteDownloadSnapshot(state, 3).isEmpty())
        val result = remoteDownloadSnapshot(state, 4)
        assertEquals("A Series", result.single().title)
        assertEquals(500L, result.single().downloadedBytes)
        val encoded = Json.encodeToString(RemotePlayback(downloads = result, downloadProfileId = 4))
        assertFalse(encoded.contains("NeverShareThis"))
        assertFalse(encoded.contains("private.example"))
        assertFalse(encoded.contains("Authorization"))
        assertFalse(encoded.contains("file:///"))
        assertFalse(encoded.contains("secret.mp4"))
    }

    @Test
    fun onlyExplicitPauseResumeOfExistingCurrentProfileTasksIsAllowed() {
        val active = DownloadsUiState(listOf(task()), profileId = 4)
        val pause = RemoteCommand(type = "downloadPause", id = "abc_123", profileId = 4, playbackId = "playback")
        assertNotNull(pause.allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.allowedDownloadTask(active, 3, "playback"))
        assertNull(pause.allowedDownloadTask(active, 4, "new-playback"))
        assertNull(pause.copy(profileId = null).allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.copy(id = "../../other").allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.copy(value = Double.NaN).allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.copy(type = "downloadDelete").allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.copy(type = "downloadResume").allowedDownloadTask(active, 4, "playback"))
        assertNull(pause.allowedDownloadTask(active.copy(items = listOf(task(status = DownloadStatus.Completed))), 4, "playback"))
        assertNotNull(pause.copy(type = "downloadResume").allowedDownloadTask(
            active.copy(items = listOf(task(status = DownloadStatus.Paused))), 4, "playback",
        ))
        assertNull(pause.allowedDownloadTask(active.copy(profileId = 5), 4, "playback"))
    }

    @Test
    fun boundedSnapshotDropsInvalidIdsAndUnboundedTitles() {
        val many = (1..200).map { index ->
            task(id = "item_$index").copy(title = "Title" + "X".repeat(1000))
        }
        val snapshot = remoteDownloadSnapshot(DownloadsUiState(many, profileId = 1), 1)
        assertEquals(30, snapshot.size)
        assertTrue(snapshot.all { it.title.length <= 100 })
        assertEquals(0, remoteDownloadSnapshot(DownloadsUiState(listOf(task("bad/id")), profileId = 1), 1).size)
    }
}
