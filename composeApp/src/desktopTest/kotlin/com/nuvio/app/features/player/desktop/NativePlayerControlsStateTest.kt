package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlSkipSegment
import com.nuvio.app.features.player.PlayerControlsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlayerControlsStateTest {
    @Test fun `new fields have safe defaults`() {
        val json = Json.parseToJsonElement(PlayerControlsState().toControlsJson()).jsonObject
        assertEquals(0L, json.getValue("bufferedPositionMs").jsonPrimitive.long)
        assertEquals(0, json.getValue("videoWidth").jsonPrimitive.int)
        assertEquals(0, json.getValue("videoHeight").jsonPrimitive.int)
        assertTrue(json.getValue("skipSegments").jsonArray.isEmpty())
        assertFalse(json.getValue("rtxSuperResolutionActive").jsonPrimitive.boolean)
    }

    @Test fun `buffer dimensions and skip chapters serialize with values`() {
        val state = PlayerControlsState(
            bufferedPositionMs = 38_500L,
            videoWidth = 3840,
            videoHeight = 2160,
            skipSegments = listOf(
                PlayerControlSkipSegment("recap", 0L, 12_000L),
                PlayerControlSkipSegment("intro", 20_000L, 90_000L),
                PlayerControlSkipSegment("outro", 1_350_000L, 1_400_000L),
            ),
        )
        val json = Json.parseToJsonElement(
            state.toControlsJson(rtxComparisonAvailable = true),
        ).jsonObject
        assertEquals(38_500L, json.getValue("bufferedPositionMs").jsonPrimitive.long)
        assertEquals(3840, json.getValue("videoWidth").jsonPrimitive.int)
        assertEquals(2160, json.getValue("videoHeight").jsonPrimitive.int)
        assertEquals(listOf("recap", "intro", "outro"),
            json.getValue("skipSegments").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(90_000L, json.getValue("skipSegments").jsonArray[1].jsonObject.getValue("endMs").jsonPrimitive.long)
        assertTrue(json.getValue("rtxSuperResolutionActive").jsonPrimitive.boolean)
    }

    @Test fun `rtx original comparison disables active filter flag`() {
        val json = Json.parseToJsonElement(
            PlayerControlsState().toControlsJson(
                rtxComparisonAvailable = true,
                rtxShowingOriginal = true,
            ),
        ).jsonObject
        assertFalse(json.getValue("rtxSuperResolutionActive").jsonPrimitive.boolean)
    }
}
