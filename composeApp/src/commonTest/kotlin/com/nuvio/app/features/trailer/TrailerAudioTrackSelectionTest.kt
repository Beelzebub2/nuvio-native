package com.nuvio.app.features.trailer

import com.nuvio.app.features.player.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerAudioTrackSelectionTest {
    private val tracks = listOf(
        AudioTrack(index = 0, id = "1", label = "Main"),
        AudioTrack(index = 1, id = "5", label = "Alternate"),
    )

    @Test
    fun `track id is preferred over list index`() {
        assertEquals(1, tracks.audioTrackIndexForControlsValue(5.0))
        assertEquals(0, tracks.audioTrackIndexForControlsValue(1.0))
    }

    @Test
    fun `index is accepted when no track has matching id`() {
        assertEquals(0, tracks.audioTrackIndexForControlsValue(0.0))
    }

    @Test
    fun `unknown and malformed selections do not change audio`() {
        assertNull(tracks.audioTrackIndexForControlsValue(3.0))
        assertNull(tracks.audioTrackIndexForControlsValue(1.5))
        assertNull(tracks.audioTrackIndexForControlsValue(Double.NaN))
        assertNull(tracks.audioTrackIndexForControlsValue(Double.POSITIVE_INFINITY))
    }
}
