package com.nuvio.app.features.remote

import kotlin.test.*

class RemoteCommandPolicyTest {
    @Test
    fun onlyCurrentPlaybackAndAdvertisedChoicesReachControls() {
        val state = RemotePlayback(playbackId = "session-2", duration = 100_000,
            skip = true, nextEpisodeAvailable = true, skipIntro = true, fullscreenSupported = true,
            volumeMax = REMOTE_MAX_VOLUME_LEVEL,
            episodes = listOf(RemoteChoice("episode-3", "Episode 3")),
            subtitles = listOf(RemoteChoice("off", "Off"), RemoteChoice("english", "English")),
            sources = listOf(RemoteChoice("episode-3-source-0", "Source")))
        fun allowed(type: String, value: Double = 0.0, id: String = "", session: String = "session-2") =
            RemoteCommand(type, value, id, session).isAllowedFor(state)
        assertTrue(allowed("pause"))
        assertTrue(allowed("mute"))
        assertTrue(allowed("volume", 0.4))
        assertTrue(allowed("volume", 1.5))
        assertTrue(allowed("volume", 2.0))
        assertTrue(allowed("next"))
        assertTrue(allowed("skipInterval"))
        assertTrue(allowed("skipIntro")) // Legacy PR #153 client remains supported.
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
        assertFalse(allowed("volume", 2.01))
        assertFalse(allowed("volume", -0.01))
        assertFalse(allowed("volume", Double.NaN))
        assertFalse(allowed("skipInterval", value = 1.0))
        assertFalse(allowed("skipInterval", session = "session-1"))
        assertFalse(allowed("next", id = "old-episode"))
        assertFalse(allowed("next", session = "session-1"))
        assertFalse(allowed("skipIntro", value = 1.0))
        assertFalse(allowed("skipIntro", session = "session-1"))
        assertFalse(allowed("setFullscreen", value = 0.5))
        assertFalse(allowed("setFullscreen", id = "other-window"))
        assertFalse(RemoteCommand(type = "next", playbackId = state.playbackId)
            .isAllowedFor(state.copy(nextEpisodeAvailable = false)))
        assertFalse(RemoteCommand(type = "skipInterval", playbackId = state.playbackId)
            .isAllowedFor(state.copy(skip = false)))
        assertFalse(RemoteCommand(type = "skipIntro", playbackId = state.playbackId)
            .isAllowedFor(state.copy(skipIntro = false)))
        assertFalse(RemoteCommand(type = "skipIntro", playbackId = state.playbackId)
            .isAllowedFor(state.copy(skip = false)))
        assertFalse(allowed("enableP2pForPlayerControls"))
        assertFalse(allowed("submitIntroCommit"))
    }

    @Test
    fun boostedVolumeIsAcceptedOnlyWhenPcAdvertisesIt() {
        val legacy = RemotePlayback(playbackId = "old-pc")
        val boosted = legacy.copy(volumeMax = REMOTE_MAX_VOLUME_LEVEL)
        fun allowed(playback: RemotePlayback, volume: Double) =
            RemoteCommand(type = "volume", value = volume, playbackId = playback.playbackId).isAllowedFor(playback)
        assertEquals(1f, legacy.effectiveVolumeMax())
        assertTrue(allowed(legacy, 1.0))
        assertFalse(allowed(legacy, 1.01))
        assertEquals(2f, boosted.effectiveVolumeMax())
        assertTrue(allowed(boosted, 1.5))
        assertTrue(allowed(boosted, 2.0))
        assertFalse(allowed(boosted, 2.01))
        assertFalse(allowed(boosted, Double.POSITIVE_INFINITY))
        // A corrupted advertised maximum must never increase the allowed command range.
        assertEquals(1f, boosted.copy(volumeMax = Float.NaN).effectiveVolumeMax())
        assertEquals(2f, boosted.copy(volumeMax = Float.MAX_VALUE).effectiveVolumeMax())
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
