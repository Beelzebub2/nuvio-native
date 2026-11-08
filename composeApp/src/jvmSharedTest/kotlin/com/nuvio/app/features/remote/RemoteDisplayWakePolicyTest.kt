package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteDisplayWakePolicyTest {
    private val playback = RemotePlayback(
        playbackId = "player-active",
        nextEpisodeAvailable = true,
        episodes = listOf(RemoteChoice("episode-2", "Episode 2")),
        sources = listOf(RemoteChoice("source-1", "Source 1")),
    )

    private fun shouldWake(
        type: String,
        id: String = "",
        playbackId: String = playback.playbackId,
        profileId: Int = 7,
        value: Double = 0.0,
        state: RemotePlayback = playback,
    ): Boolean = RemoteCommand(
        type = type, id = id, value = value, playbackId = playbackId, profileId = profileId,
    ).requestsDisplayWakeForPlayback(state, activeProfileId = 7)

    @Test
    fun explicitAuthorizedStartResumeAndEpisodeActionsWakeDisplay() {
        assertTrue(shouldWake("play"))
        assertTrue(shouldWake("next"))
        assertTrue(shouldWake("previous"))
        assertTrue(shouldWake("episode", id = "episode-2"))
        assertTrue(shouldWake("source", id = "source-1"))
    }

    @Test
    fun backgroundPollingAndNonPlaybackCommandsNeverWakeDisplay() {
        for (command in listOf("state", "pause", "volume", "mute", "seek", "seekBy",
            "skipInterval", "subtitle", "fetchSubtitles", "downloadResume", "setFullscreen",
            "handoffRequest", "handoffCancel", "handoffSource")) {
            assertFalse(shouldWake(command), "Unexpected monitor wake on $command")
        }
    }

    @Test
    fun staleOrUnauthorizedPlaybackCannotWakeDisplay() {
        assertFalse(shouldWake("play", profileId = 8))
        assertFalse(shouldWake("play", playbackId = "stale"))
        assertFalse(shouldWake("play", id = "extra"))
        assertFalse(shouldWake("play", value = 1.0))
        assertFalse(shouldWake("play", state = RemotePlayback()))
        assertFalse(shouldWake("next", state = playback.copy(nextEpisodeAvailable = false)))
        assertFalse(shouldWake("episode", id = "unknown"))
        assertFalse(shouldWake("source", id = "unknown"))
    }
}
