package com.nuvio.app.features.remote

import kotlin.test.*

class RemoteCommandPolicyTest {
    @Test
    fun onlyCurrentPlaybackAndAdvertisedChoicesReachControls() {
        val state = RemotePlayback(playbackId = "session-2", duration = 100_000,
            episodes = listOf(RemoteChoice("episode-3", "Episode 3")),
            subtitles = listOf(RemoteChoice("off", "Off"), RemoteChoice("english", "English")),
            sources = listOf(RemoteChoice("episode-3-source-0", "Source")))
        fun allowed(type: String, value: Double = 0.0, id: String = "", session: String = "session-2") =
            RemoteCommand(type, value, id, session).isAllowedFor(state)
        assertTrue(allowed("pause"))
        assertTrue(allowed("mute"))
        assertTrue(allowed("volume", 0.4))
        assertTrue(allowed("next"))
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
        assertFalse(allowed("enableP2pForPlayerControls"))
        assertFalse(allowed("submitIntroCommit"))
    }
}
