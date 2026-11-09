package com.nuvio.app.features.watchorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeWatchOrderTest {
    private val example = """
      {
        "franchiseId": "junban:7404", "franchiseName": "KonoSuba",
        "mode": "recommended", "confidence": "verified",
        "steps": [
          {"malId":30831,"title":"Season 1","kind":"tv","date":"2016-01-14","required":true},
          {"malId":32937,"title":"Season 2","kind":"tv","date":"2017-01-12","required":true},
          {"malId":38040,"title":"Legend of Crimson","kind":"movie","date":"2019-08-30","required":true},
          {"malId":51958,"title":"Explosion prequel","kind":"tv","date":"2023-04-06","required":false},
          {"malId":49458,"title":"Season 3","kind":"tv","date":"2024-04-10","required":true}
        ]
      }
    """.trimIndent()

    @Test fun movieIsNextAfterKonosubaSeasonTwo() {
        val order = AnimeWatchOrderRepository.parse(example)!!
        assertTrue(order.verified)
        assertEquals(38040, order.nextAfter(32937, includeOptional = false)?.malId)
        assertEquals(49458, order.nextAfter(38040, includeOptional = false)?.malId)
        assertEquals(51958, order.nextAfter(38040, includeOptional = true)?.malId)
        assertNull(order.nextAfter(49458, includeOptional = false))
        assertNull(order.nextAfter(49458, includeOptional = true))
    }

    @Test fun inferredOrderIsNotMarkedVerified() {
        assertFalse(AnimeWatchOrderRepository.parse(example.replace("verified", "inferred"))!!.verified)
    }

    @Test fun malformedAndDuplicateResponsesFailClosed() {
        assertNull(AnimeWatchOrderRepository.parse("{}"))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"malId\":38040", "\"malId\":32937")))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"kind\":\"movie\"", "\"kind\":\"torrent\"")))
        assertNull(AnimeWatchOrderRepository.parse(example.replace("\"required\":true", "\"required\":\"true\"")))
    }
}
