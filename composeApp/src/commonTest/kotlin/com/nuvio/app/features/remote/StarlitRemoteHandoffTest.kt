package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarlitRemoteHandoffTest {
    @Test
    fun rejectsInvalidMediaBeforeSelectingDevice() {
        assertFalse(StarlitRemoteHandoff.request(RemotePlaybackHandoff(
            contentType = "movie",
            videoId = "https://secret.example/media",
            parentMetaId = "tt0001",
            title = "Example",
            profileId = 1,
        )))
        assertTrue(StarlitRemoteHandoff.pendingDeviceChoice.value == null)
    }
}
