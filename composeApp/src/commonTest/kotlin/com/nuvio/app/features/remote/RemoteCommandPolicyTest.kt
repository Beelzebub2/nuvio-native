package com.nuvio.app.features.remote

import kotlin.test.*

class RemoteCommandPolicyTest {
    @Test
    fun onlyCurrentPlaybackAndAdvertisedChoicesReachControls() {
        val state = RemotePlayback(playbackId = "session-2", duration = 100_000,
            nextEpisodeAvailable = true, skipIntro = true, fullscreenSupported = true,
            episodes = listOf(RemoteChoice("episode-3", "Episode 3")),
            subtitles = listOf(RemoteChoice("off", "Off"), RemoteChoice("english", "English")),
            sources = listOf(RemoteChoice("episode-3-source-0", "Source")))
        fun allowed(type: String, value: Double = 0.0, id: String = "", session: String = "session-2") =
            RemoteCommand(type, value, id, session).isAllowedFor(state)
        assertTrue(allowed("pause"))
        assertTrue(allowed("mute"))
        assertTrue(allowed("volume", 0.4))
        assertTrue(allowed("next"))
        assertTrue(allowed("skipIntro"))
        assertTrue(allowed("setFullscreen", 1.0))
        assertTrue(allowed("setFullscreen", 0.0))
        assertTrue(allowed("previous"))
        assertTrue(allowed("episode", id = "episode-3"))
        assertTrue(allowed("subtitle", id = "english"))
        assertTrue(allowed("subtitle", id = "off"))
        assertTrue(allowed("source", id = "episode-3-source-0"))
        assertFalse(allowed("pause", session = "session-1"))
        assertFalse(allowed("episode", id = "episode-from-previous-show"))
        assertFalse(allowed("subtitle", id = "unknown-track"))
        assertFalse(allowed("source", id = "other-episode-source-0"))
        assertFalse(allowed("seek", Double.NaN))
        assertFalse(allowed("seek", 100_001.0))
        assertFalse(allowed("seekBy", Double.POSITIVE_INFINITY))
        assertFalse(allowed("volume", 2.0))
        assertFalse(allowed("skipInterval"))
        assertFalse(allowed("next", id = "old-episode"))
        assertFalse(allowed("next", session = "session-1"))
        assertFalse(allowed("skipIntro", value = 1.0))
        assertFalse(allowed("skipIntro", session = "session-1"))
        assertFalse(allowed("setFullscreen", value = 0.5))
        assertFalse(allowed("setFullscreen", id = "other-window"))
        assertFalse(RemoteCommand(type = "next", playbackId = state.playbackId)
            .isAllowedFor(state.copy(nextEpisodeAvailable = false)))
        assertFalse(RemoteCommand(type = "skipIntro", playbackId = state.playbackId)
            .isAllowedFor(state.copy(skipIntro = false)))
        assertFalse(allowed("enableP2pForPlayerControls"))
        assertFalse(allowed("submitIntroCommit"))
    }

    @Test
    fun fullscreenWorksWithoutVideoButNeverBypassesSessionAndCapabilityChecks() {
        val idle = RemotePlayback(fullscreenSupported = true)
        assertTrue(RemoteCommand(type = "setFullscreen", value = 1.0).isAllowedFor(idle))
        assertTrue(RemoteCommand(type = "setFullscreen", value = 0.0).isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "play").isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "next").isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "skipIntro").isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "setFullscreen", playbackId = "stale").isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "setFullscreen", value = Double.NaN).isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "setFullscreen", value = 2.0).isAllowedFor(idle))
        assertFalse(RemoteCommand(type = "setFullscreen", value = 1.0)
            .isAllowedFor(idle.copy(fullscreenSupported = false)))
    }
}
