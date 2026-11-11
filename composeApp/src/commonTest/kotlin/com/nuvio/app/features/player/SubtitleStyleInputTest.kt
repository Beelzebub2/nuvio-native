package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleStyleInputTest {
    @Test
    fun `signed millisecond input preserves exact delay`() {
        assertEquals(1250, parseSubtitleDelayInput("1250"))
        assertEquals(-75, parseSubtitleDelayInput("-75"))
        assertEquals(0, parseSubtitleDelayInput("0"))
        assertEquals(SUBTITLE_DELAY_MAX_MS, parseSubtitleDelayInput("60000"))
        assertEquals(SUBTITLE_DELAY_MIN_MS, parseSubtitleDelayInput("-60000"))
    }

    @Test
    fun `unfinished and invalid values cannot change the delay`() {
        assertNull(parseSubtitleDelayInput(""))
        assertNull(parseSubtitleDelayInput("-"))
        assertNull(parseSubtitleDelayInput("1.5"))
        assertNull(parseSubtitleDelayInput("99999"))
        assertNull(parseSubtitleDelayInput("-60001"))
        assertNull(parseSubtitleDelayInput("9999999999999999999"))
    }
}
