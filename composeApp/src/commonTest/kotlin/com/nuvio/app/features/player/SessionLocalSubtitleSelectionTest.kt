package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionLocalSubtitleSelectionTest {
    @Test
    fun aLocalSubtitleIsVisibleOnlyDuringItsOriginalPlayback() {
        val initialKey = PlaybackKey(
            sourceIdentity = "https://example.test/video-1",
            videoId = "episode-1",
            seasonNumber = 1,
            episodeNumber = 1,
        )
        val selected = SessionLocalSubtitleSelection(initialKey, "legendas-ação.srt")

        assertEquals("legendas-ação.srt", selected.visibleNameFor(initialKey, true, null))
        assertNull(selected.visibleNameFor(initialKey.copy(videoId = "episode-2"), true, null))
        assertNull(selected.visibleNameFor(initialKey.copy(sourceIdentity = "https://example.test/video-2"), true, null))
        assertNull(selected.visibleNameFor(initialKey, false, null))
        assertNull(selected.visibleNameFor(initialKey, true, "addon-subtitle"))
        assertNull((null as SessionLocalSubtitleSelection?).visibleNameFor(initialKey, true, null))
    }
}
