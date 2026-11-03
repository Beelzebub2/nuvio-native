package com.nuvio.app.features.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePlaybackHandoffTest {
    private fun handoff() = RemotePlaybackHandoff(
        contentType = "series",
        videoId = "tt1234567:1:2",
        parentMetaId = "tt1234567",
        title = "The Episode",
        profileId = 2,
        season = 1,
        episode = 2,
        resumePositionMs = 120_000,
    )

    @Test
    fun metadataOnlyRequestContainsNoRemoteMediaUrlOrCredential() {
        val valid = handoff()
        assertTrue(valid.isAllowed())
        val encoded = Json.encodeToString(valid)
        assertTrue(encoded.contains("tt1234567"))
        assertFalse(encoded.contains("sourceUrl"))
        assertFalse(encoded.contains("Authorization"))
        assertFalse(encoded.contains("headers"))
        assertFalse(encoded.contains("accessToken"))
    }

    @Test
    fun profileGenerationCommandAndBoundsMustMatch() {
        val intent = handoff()
        val command = RemoteCommand(
            type = "handoffRequest",
            playbackId = "current-playback",
            profileId = 2,
            handoff = intent,
        )
        assertEquals(intent, command.acceptableHandoff(2, "current-playback"))
        assertNull(command.acceptableHandoff(1, "current-playback"))
        assertNull(command.acceptableHandoff(2, "different"))
        assertNull(command.copy(profileId = 1).acceptableHandoff(2, "current-playback"))
        assertNull(command.copy(id = "arbitrary-url").acceptableHandoff(2, "current-playback"))
        assertNull(command.copy(type = "openUrl").acceptableHandoff(2, "current-playback"))
        assertNull(command.copy(value = 1.0).acceptableHandoff(2, "current-playback"))
        assertNull(command.copy(handoff = intent.copy(profileId = 3)).acceptableHandoff(2, "current-playback"))
    }

    @Test
    fun maliciousIdentifiersAndUrlPayloadsAreRejectedBeforeDesktopNavigation() {
        val valid = handoff()
        listOf("https://example.org/path", "../private.mp4", "C:\\secrets", "bad\nmedia", "id?auth=secret")
            .forEach { assertFalse(valid.copy(videoId = it).isAllowed(), it) }
        assertFalse(valid.copy(parentMetaId = "https://example.org/stream").isAllowed())
        assertFalse(valid.copy(title = "\u0000hidden").isAllowed())
        assertFalse(valid.copy(contentType = "file").isAllowed())
        assertFalse(valid.copy(resumePositionMs = Long.MAX_VALUE).isAllowed())
        assertFalse(valid.copy(season = 1, episode = null).isAllowed())
        assertFalse(valid.copy(season = -1).isAllowed())
        assertFalse(valid.copy(episode = 10000).isAllowed())
    }
}
