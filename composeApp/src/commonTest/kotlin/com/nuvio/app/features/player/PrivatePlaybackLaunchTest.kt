package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivatePlaybackLaunchTest {
    private fun launch(privatePlayback: Boolean = false) = PlayerLaunch(
        profileId = 1,
        title = "Example Movie",
        sourceUrl = "https://example.com/stream",
        streamTitle = "Example Stream",
        providerName = "Example Provider",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        privatePlayback = privatePlayback,
    )

    @Test
    fun normalPlaybackIsTheDefaultAndPrivateOptionAffectsOnlyItsOwnLaunch() {
        val normal = launch()
        val private = launch(privatePlayback = true)

        assertFalse(normal.privatePlayback)
        assertFalse(normal.externalPlaybackSession().privatePlayback)
        assertTrue(private.privatePlayback)
        assertTrue(private.externalPlaybackSession().privatePlayback)
        assertEquals(normal.parentMetaId, private.parentMetaId)
        assertFalse(private.copy(privatePlayback = false).privatePlayback)
    }

    @Test
    fun launchStoreKeepsPrivateAndNormalSessionsSeparate() {
        val privateId = PlayerLaunchStore.put(launch(privatePlayback = true))
        val normalId = PlayerLaunchStore.put(launch())
        try {
            assertTrue(PlayerLaunchStore.get(privateId)?.privatePlayback == true)
            assertFalse(PlayerLaunchStore.get(normalId)?.privatePlayback ?: true)

            PlayerLaunchStore.update(privateId) { it.copy(streamTitle = "Other stream") }
            assertTrue(PlayerLaunchStore.get(privateId)?.privatePlayback == true)
            assertFalse(PlayerLaunchStore.get(normalId)?.privatePlayback ?: true)
        } finally {
            PlayerLaunchStore.remove(privateId)
            PlayerLaunchStore.remove(normalId)
        }
    }

    @Test
    fun playerRuntimeKeepsItsOriginalPrivateFlagDuringSourceSwitches() {
        val privateRuntime = PlayerScreenRuntime(testPlayerScreenArgs().copy(privatePlayback = true))
        val ordinaryRuntime = PlayerScreenRuntime(testPlayerScreenArgs())

        assertTrue(privateRuntime.privatePlayback)
        assertTrue(privateRuntime.playbackSession.privatePlayback)
        assertFalse(ordinaryRuntime.privatePlayback)
        assertFalse(ordinaryRuntime.playbackSession.privatePlayback)

        privateRuntime.activeSourceUrl = "https://example.com/new-source"
        privateRuntime.activeVideoId = "tt1234567:1:2"
        assertTrue(privateRuntime.playbackSession.privatePlayback)
        assertFalse(ordinaryRuntime.playbackSession.privatePlayback)
    }
}
