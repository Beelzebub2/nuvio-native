package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddonSubtitleDisplayLabelTest {
    @Test
    fun trackMetadataOverridesGenericTrackNamesInPriorityOrder() {
        assertEquals("Track A", addonSubtitleDisplayLabel("Track A", "Track B", "Track C"))
        assertEquals("Track B", addonSubtitleDisplayLabel(" ", "Track B", "Track C"))
        assertEquals("Track C", addonSubtitleDisplayLabel(null, null, "Track C"))
        assertNull(addonSubtitleDisplayLabel(null, "  ", null))
    }

    @Test
    fun untrustedTrackLabelsAreLengthLimitedAndSafeToRender() {
        assertEquals(240, addonSubtitleDisplayLabel("x".repeat(500), null, null)?.length)
        assertEquals("safe", addonSubtitleDisplayLabel("\u202Esafe\u202C\u0000", null, null))
        assertEquals("字幕", addonSubtitleDisplayLabel("字幕", null, null))
    }
}
