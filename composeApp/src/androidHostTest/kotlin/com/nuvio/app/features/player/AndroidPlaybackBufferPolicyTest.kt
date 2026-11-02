package com.nuvio.app.features.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@androidx.annotation.OptIn(UnstableApi::class)
class AndroidPlaybackBufferPolicyTest {
    @Test
    fun loopbackTorrentBuffersMoreBeforeStartingAndAfterStalling() {
        val normal = androidPlaybackBufferPolicy(isLoopbackSource = false)
        val torrent = androidPlaybackBufferPolicy(isLoopbackSource = true)
        assertEquals(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, normal.startMs)
        assertEquals(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, normal.afterRebufferMs)
        assertEquals(8_000, torrent.startMs)
        assertEquals(12_000, torrent.afterRebufferMs)
        assertTrue(torrent.startMs > normal.startMs)
        assertTrue(torrent.afterRebufferMs > normal.afterRebufferMs)
        assertTrue(torrent.afterRebufferMs < DefaultLoadControl.DEFAULT_MAX_BUFFER_MS)
    }
}
